package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.PushProperties;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.leagueakari.qqbot.QqBotClient;
import com.leagueakari.qqbot.QqPushException;
import com.leagueakari.reportimage.ReportImageData;
import com.leagueakari.reportimage.ReportImageRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局后播报编排（Post-Game Broadcast）：
 * 对局数据入库后由 controller 触发 {@link #maybeBroadcast}，内部判定
 * "是否刚结束的车队对局"（状态门控 + 车队成员数 + 时间窗 + 开关），
 * 通过后组装战报并向车队群发送，全程落库推送状态。
 * <p>状态机（match.push_status）：
 * PENDING/FAILED →(CAS)→ PUSHING → 成功 SENT / 失败 FAILED。
 * 发送失败置 FAILED，桌面端轮询补推同一局时会再次进入，天然重试；
 * 服务重启中断的 PUSHING 由 {@link #recoverInterruptedPush} 启动时恢复为 FAILED。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastCoordinator {

    /** 推送状态（与 V7__match_push_status.sql 注释对应） */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUSHING = "PUSHING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_AI_FAILED = "AI_FAILED";

    /** 错误原因落库上限（列 VARCHAR(512)） */
    private static final int ERROR_MAX_LENGTH = 500;

    /** AI 全挂时的缺席提示（战报图已送达后补发，保证群里永远有交代） */
    private static final String AI_ABSENCE_TIP =
            "🤖 AI 评阅官本局不在线，锐评缺席一次——战报图已送达，欢迎人工复盘。";

    private final MatchMapper matchMapper;
    private final MatchParticipantMapper participantMapper;
    private final MatchMvpMapper mvpMapper;
    private final PushProperties pushProperties;
    private final TeamProperties teamProperties;
    private final TeamRosterService rosterService;
    private final GameDataService gameDataService;
    private final QqBotClient qqBotClient;
    /** 战报图渲染器：对局数据 → PNG 字节（Java2D，见 reportimage 包） */
    private final ReportImageRenderer reportImageRenderer;
    /** 局后锐评：图发送后生成第二条文本消息；AI 不可用由本编排降级 */
    private final PostGameCommentService postGameCommentService;
    /** 锐评输入摘要：双方 10 人全量数据（伤害/承伤/经济/称号），供 AI 火力全开地点名 */
    private final PostGameSummaryBuilder postGameSummaryBuilder;
    private final Clock clock;
    /** JSON 解析：teams_json 资源快照与 stats_json 统计字段 */
    private final ObjectMapper objectMapper;

    /**
     * 服务重启恢复：启动时把残留的 PUSHING（上一进程中断在发送途中）恢复为 FAILED，
     * 使桌面端补推同一局时可再次触发播报，避免永久卡死
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedPush() {
        int recovered = matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_FAILED)
                .set("push_error", "服务重启中断发送，待补推重试")
                .eq("push_status", STATUS_PUSHING));
        if (recovered > 0) {
            log.warn("Recovered interrupted broadcasts: count={}", recovered);
        }
    }

    /**
     * 局后播报入口（同步执行，由对局同步接口在落库后调用）：
     * 全部判定不通过时零副作用；判定通过则发送并落库状态
     */
    public void maybeBroadcast(Long gameId) {
        if (gameId == null) {
            return;
        }
        try {
            doBroadcast(gameId);
        } catch (Exception e) {
            // 兜底：编排异常（如状态更新失败）不向同步接口传播，落库失败原因等待补推
            log.error("Broadcast orchestration failed: gameId={}", gameId, e);
            markFailed(gameId, e.getMessage());
        }
    }

    /** 判定 + 发送主流程 */
    private void doBroadcast(Long gameId) {
        // 1. 状态门控：读库判定是否已处理/处理中，避免重复播报与并发双发
        Match match = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", gameId));
        if (match == null) {
            log.info("Broadcast skipped: match not found, gameId={}", gameId);
            return;
        }
        String status = match.getPushStatus();
        if (STATUS_SENT.equals(status) || STATUS_AI_FAILED.equals(status) || STATUS_PUSHING.equals(status)) {
            log.info("Broadcast skipped: status={}, gameId={}", status, gameId);
            return;
        }

        // 2. 开关与配置门控：未启用/未配置视为"未开通"，不落状态（配置好后自然生效）
        if (!pushProperties.isEnabled()) {
            log.info("Broadcast skipped: push disabled, gameId={}", gameId);
            return;
        }
        if (!pushProperties.isConfigured()) {
            log.warn("Broadcast skipped: push not configured (group/appId/secret), gameId={}", gameId);
            return;
        }

        // 3. 时间窗门控：距估算的对局结束时刻超过窗口视为旧局（backfill/补推历史），不播报
        long endAtMs = match.getGameCreation() + match.getGameDuration() * 1000L;
        long windowMs = pushProperties.getRecentWindowMinutes() * 60_000L;
        if (clock.millis() - endAtMs > windowMs) {
            log.info("Broadcast skipped: stale game, gameId={}, endAt={}, window={}min",
                    gameId, endAtMs, pushProperties.getRecentWindowMinutes());
            // 置 SENT 防补推反复检查该旧局
            markSent(gameId);
            return;
        }

        // 4. 车队局门控：同局车队成员数 ≥ 阈值才播报（口径与周报一致：成员身份集合匹配）
        List<MatchParticipant> participants = participantMapper.selectList(
                new QueryWrapper<MatchParticipant>().eq("match_id", match.getId()));
        List<TeamRosterService.RosterMember> roster;
        try {
            roster = rosterService.requireMembers();
        } catch (Exception e) {
            // 名单解析依赖 Riot API（首次/改名后）：暂时跳过，补推时再试
            log.warn("Broadcast skipped: roster unavailable, gameId={}, err={}", gameId, e.getMessage());
            return;
        }
        Map<String, TeamRosterService.RosterMember> memberByPuuid = memberIndex(roster);
        long fleetCount = participants.stream()
                .filter(p -> memberByPuuid.containsKey(p.getPuuid()))
                .count();
        if (fleetCount < Math.max(1, teamProperties.getMinSharedMembers())) {
            log.info("Broadcast skipped: not a fleet game, gameId={}, fleetMembers={}",
                    gameId, fleetCount);
            // 个人局永远不播报：置 SENT 免去后续每次补推的重复判定
            markSent(gameId);
            return;
        }

        // 5. 抢占：CAS 推进 PUSHING，仅成功者继续（并发双发防线）
        int claimed = matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_PUSHING)
                .eq("game_id", gameId)
                .in("push_status", STATUS_PENDING, STATUS_FAILED));
        if (claimed == 0) {
            log.info("Broadcast skipped: claim failed (concurrent), gameId={}", gameId);
            return;
        }
        // 抢占成功：PENDING/FAILED → PUSHING，开始本局播报（补推重试也走此路径，可据日志数重试次数）
        log.info("Broadcast claimed: gameId={}, status={}->PUSHING", gameId, status);

        // 6. 组装战报图数据 → 渲染 PNG → 图片消息发送（图只管数据，不依赖 AI）
        List<MatchMvp> awards = mvpMapper.selectList(
                new QueryWrapper<MatchMvp>().eq("match_id", match.getId()));
        ReportImageData imageData = buildImageData(match, participants, memberByPuuid, awards);
        byte[] png;
        try {
            png = reportImageRenderer.renderPng(imageData);
        } catch (Exception e) {
            log.error("Broadcast render failed: gameId={}", gameId, e);
            markFailed(gameId, "战报图渲染失败: " + e.getMessage());
            return;
        }
        try {
            qqBotClient.sendGroupImageMessage(pushProperties.getGroupOpenId(), png);
            // 图已送达：先落 SENT 与发送时间，锐评结果随后补状态（comment 阶段失败不重发图）
            markSent(gameId);
            log.info("Broadcast image sent: gameId={}, fleetMembers={}, pngBytes={}",
                    gameId, fleetCount, png.length);
        } catch (QqPushException e) {
            log.error("Broadcast send failed: gameId={}", gameId, e);
            markFailed(gameId, e.getMessage());
            return;
        }

        // 7. 局后锐评（第二条消息）：AI 生成失败重试耗尽后发缺席提示；
        //    提示也失败则整局 FAILED，由桌面端补推整局重播
        if (pushProperties.isAiCommentEnabled()) {
            broadcastComment(gameId, match, participants, memberByPuuid, awards);
        }
    }

    /** 局后锐评阶段：生成 → 发 Markdown（**加粗** 醒目标记）→ 记送达；AI/发送失败 → 缺席提示兜底 */
    private void broadcastComment(Long gameId, Match match, List<MatchParticipant> participants,
                                  Map<String, TeamRosterService.RosterMember> memberByPuuid,
                                  List<MatchMvp> awards) {
        String comment;
        try {
            comment = postGameCommentService.generateComment(
                    postGameSummaryBuilder.build(match, participants, memberByPuuid, awards));
        } catch (Exception e) {
            // AI 不可用（key 未配/接口失败/重试后空正文）：发缺席提示，不静默
            log.warn("Post-game comment failed, send absence tip: gameId={}, err={}",
                    gameId, e.getMessage());
            sendAbsenceTip(gameId);
            return;
        }
        try {
            // 锐评用 Markdown 通道（msg_type=2）：人名/称号/关键数据以 **加粗** 渲染
            qqBotClient.sendGroupMarkdownMessage(pushProperties.getGroupOpenId(), comment);
            markCommentDelivered(gameId);
            log.info("Post-game comment sent: gameId={}, length={}", gameId, comment.length());
        } catch (QqPushException e) {
            log.error("Post-game comment send failed, send absence tip: gameId={}", gameId, e);
            sendAbsenceTip(gameId);
        }
    }

    /** 发送 AI 缺席提示：成功 → AI_FAILED（图已发、AI 缺席已提示）；失败 → FAILED 等补推 */
    private void sendAbsenceTip(Long gameId) {
        try {
            qqBotClient.sendGroupTextMessage(pushProperties.getGroupOpenId(), AI_ABSENCE_TIP);
            markAiAbsent(gameId);
            log.info("AI absence tip sent: gameId={}", gameId);
        } catch (QqPushException e) {
            log.error("AI absence tip send failed: gameId={}", gameId, e);
            markFailed(gameId, "AI 缺席提示发送失败: " + e.getMessage());
        }
    }

    /** 推送成功：状态 SENT + 战报消息发送时间（终态，此后补推不再重发） */
    private void markSent(Long gameId) {
        matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_SENT)
                .set("push_image_at", LocalDateTime.now())
                .eq("game_id", gameId));
        // 状态机审计：任一状态变更都留痕，便于按 gameId 还原整局推送轨迹
        log.info("Broadcast status -> SENT: gameId={}", gameId);
    }

    /** 推送失败：状态 FAILED + 错误原因（截断落库），等待桌面端补推重试 */
    private void markFailed(Long gameId, String error) {
        String detail = error == null ? "unknown" : error;
        if (detail.length() > ERROR_MAX_LENGTH) {
            detail = detail.substring(0, ERROR_MAX_LENGTH);
        }
        matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_FAILED)
                .set("push_error", detail)
                .eq("game_id", gameId));
        // 状态机审计：FAILED 是补推重试的入口，日志含原因，可按 gameId 聚合重试次数
        log.warn("Broadcast status -> FAILED: gameId={}, reason={}", gameId, detail);
    }

    /** 锐评文本已送达：补记发送时间（状态保持 SENT） */
    private void markCommentDelivered(Long gameId) {
        matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_comment_at", LocalDateTime.now())
                .eq("game_id", gameId));
        log.info("Broadcast comment delivered: gameId={}", gameId);
    }

    /** AI 缺席：状态 AI_FAILED（图已发、缺席提示已发）+ 提示发送时间 */
    private void markAiAbsent(Long gameId) {
        matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_AI_FAILED)
                .set("push_comment_at", LocalDateTime.now())
                .eq("game_id", gameId));
        log.warn("Broadcast status -> AI_FAILED: gameId={} (image sent, AI comment absent)",
                gameId);
    }

    /**
     * 成员身份索引：puuid → 成员（跨两种 puuid 体系，与 TeamStatsService 口径一致）。
     * 车队对局判定与"车队成员行"筛选都用它
     */
    private Map<String, TeamRosterService.RosterMember> memberIndex(
            List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> index = new HashMap<>();
        for (TeamRosterService.RosterMember member : roster) {
            for (String puuid : member.puuids()) {
                index.put(puuid, member);
            }
        }
        return index;
    }

    // ---------- 战报图数据组装（方案 C v2：顶栏/资源/焦点卡/双列阵容） ----------

    /**
     * 聚合战报图渲染数据：车队视角（主队 = 车队成员多数所在队），
     * 三指标口径：输出/承伤占比为全 10 人口径，伤转 = 伤害 ÷ 经济
     */
    private ReportImageData buildImageData(Match match, List<MatchParticipant> participants,
                                           Map<String, TeamRosterService.RosterMember> memberByPuuid,
                                           List<MatchMvp> awards) {
        ReportImageData d = new ReportImageData();
        d.teamName = teamProperties.getName();

        // 车队成员与其所在队伍：主队取成员多数侧（常规开黑 5 人同队）
        List<MatchParticipant> fleet = participants.stream()
                .filter(p -> memberByPuuid.containsKey(p.getPuuid()))
                .toList();
        Map<Integer, Long> teamCount = new LinkedHashMap<>();
        for (MatchParticipant p : fleet) {
            teamCount.merge(p.getTeamId(), 1L, Long::sum);
        }
        int mainTeamId = teamCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(match.getWinnerTeamId() == null ? 100 : match.getWinnerTeamId());
        boolean win = match.getWinnerTeamId() != null && match.getWinnerTeamId() == mainTeamId;
        d.win = win;
        d.resultLabel = win ? "VICTORY · 胜利" : "DEFEAT · 败北";
        d.metaLine = queueName(match.getQueueId()) + " · " + formatDuration(match.getGameDuration())
                + " · " + formatGameTime(match.getGameCreation());

        // 资源对比与一血：从 teams_json 快照解析（缺失按 -1/空处理）
        fillResources(d, match.getTeamsJson(), mainTeamId);

        // 全 10 人伤害/承伤合计（占比分母）
        double totalDamage = 0;
        double totalTaken = 0;
        for (MatchParticipant p : participants) {
            totalDamage += statInt(p.getStatsJson(), "totalDamageDealtToChampions");
            totalTaken += statInt(p.getStatsJson(), "totalDamageTaken");
        }

        // 称号索引：participantId → award（MVP=胜方最佳 / ACE=败方最佳）
        Map<Long, MatchMvp> awardByParticipant = new HashMap<>();
        for (MatchMvp award : awards) {
            if (award.getParticipantId() != null && award.getType() != null) {
                awardByParticipant.put(award.getParticipantId(), award);
            }
        }

        // 双列组装：主队（车队侧）与对方，行内按击杀降序（车队成员不打散，置前列便于群友认领）
        List<ReportImageData.Player> mainRows = buildTeamRows(participants, mainTeamId, true,
                awardByParticipant, memberByPuuid, totalDamage, totalTaken);
        int otherTeamId = mainTeamId == 100 ? 200 : 100;
        List<ReportImageData.Player> otherRows = buildTeamRows(participants, otherTeamId, false,
                awardByParticipant, Map.of(), totalDamage, totalTaken);
        d.mainTeam = mainRows;
        d.otherTeam = otherRows;
        // 比分 = 双方击杀合计（顶栏右上角展示；漏填会导致图上恒显 0 : 0）
        d.mainScore = teamKills(participants, mainTeamId);
        d.otherScore = teamKills(participants, otherTeamId);

        // 焦点卡：车队内 MVP → 尽力（ACE）→ 默认队内击杀最高（后两者 titleTag 为空则卡上无徽章）
        ReportImageData.Player hero = mainRows.stream()
                .filter(p -> "MVP".equals(p.titleTag))
                .findFirst()
                .orElse(mainRows.stream().filter(p -> "尽力".equals(p.titleTag)).findFirst().orElse(null));
        if (hero == null && !mainRows.isEmpty()) {
            hero = mainRows.get(0); // mainRows 已按击杀降序
        }
        d.hero = hero;

        d.footerLeft = teamProperties.getName();
        d.footerRight = "LEAGUE AKARI 对局战报";
        return d;
    }

    /** 组装一列 5 行：车队成员置前（保持相对击杀序），其后路人/对手按击杀降序 */
    private List<ReportImageData.Player> buildTeamRows(List<MatchParticipant> participants, int teamId,
                                                       boolean isMain,
                                                       Map<Long, MatchMvp> awardByParticipant,
                                                       Map<String, TeamRosterService.RosterMember> memberByPuuid,
                                                       double totalDamage, double totalTaken) {
        List<MatchParticipant> team = participants.stream()
                .filter(p -> p.getTeamId() != null && p.getTeamId() == teamId)
                .sorted(Comparator.comparingInt(
                        (MatchParticipant p) -> p.getKills() == null ? 0 : p.getKills()).reversed())
                .toList();
        // 主队：车队成员排前面（同队内仍按击杀降序）
        List<MatchParticipant> ordered = new ArrayList<>();
        if (isMain) {
            List<MatchParticipant> fleet = team.stream()
                    .filter(p -> memberByPuuid.containsKey(p.getPuuid())).toList();
            List<MatchParticipant> others = team.stream()
                    .filter(p -> !memberByPuuid.containsKey(p.getPuuid())).toList();
            ordered.addAll(fleet);
            ordered.addAll(others);
        } else {
            ordered.addAll(team);
        }

        List<ReportImageData.Player> rows = new ArrayList<>();
        for (MatchParticipant p : ordered) {
            ReportImageData.Player row = new ReportImageData.Player();
            row.summonerName = p.getSummonerName();
            row.championName = safeChampionName(p.getChampionId());
            row.championId = p.getChampionId() == null ? 0 : p.getChampionId();
            row.kills = p.getKills() == null ? 0 : p.getKills();
            row.deaths = p.getDeaths() == null ? 0 : p.getDeaths();
            row.assists = p.getAssists() == null ? 0 : p.getAssists();
            row.damageShare = totalDamage > 0
                    ? statInt(p.getStatsJson(), "totalDamageDealtToChampions") / totalDamage : 0;
            row.damageTakenShare = totalTaken > 0
                    ? statInt(p.getStatsJson(), "totalDamageTaken") / totalTaken : 0;
            double gold = statInt(p.getStatsJson(), "goldEarned");
            row.damagePerGold = gold > 0
                    ? statInt(p.getStatsJson(), "totalDamageDealtToChampions") / gold : 0;
            // 称号：MVP（胜方最佳）恒标；ACE（败方最佳）仅在车队侧标"尽力"
            MatchMvp award = awardByParticipant.get(p.getId());
            if (award != null) {
                if ("MVP".equals(award.getType())) {
                    row.titleTag = "MVP";
                    row.opScore = award.getOpScore() == null ? -1 : award.getOpScore().doubleValue();
                } else if (isMain) {
                    row.titleTag = "尽力";
                    row.opScore = award.getOpScore() == null ? -1 : award.getOpScore().doubleValue();
                } else {
                    row.opScore = -1;
                }
            } else {
                row.opScore = -1;
            }
            rows.add(row);
        }
        return rows;
    }

    /** 一队击杀合计（战报图比分口径，与锐评摘要一致） */
    private int teamKills(List<MatchParticipant> participants, int teamId) {
        return participants.stream()
                .filter(p -> p.getTeamId() != null && p.getTeamId() == teamId)
                .mapToInt(p -> p.getKills() == null ? 0 : p.getKills())
                .sum();
    }

    /** 资源与一血：解析 teams_json（[{teamId, towerKills, dragonKills, baronKills, firstBlood}]） */
    private void fillResources(ReportImageData d, String teamsJson, int mainTeamId) {
        if (teamsJson == null || teamsJson.isBlank()) {
            return;
        }
        try {
            JsonNode teams = objectMapper.readTree(teamsJson);
            if (!teams.isArray()) {
                return;
            }
            for (JsonNode t : teams) {
                if (t.path("teamId").asInt(-1) != mainTeamId) {
                    continue;
                }
                d.mainTower = t.path("towerKills").asInt(-1);
                d.mainDragon = t.path("dragonKills").asInt(-1);
                d.mainBaron = t.path("baronKills").asInt(-1);
                if (t.hasNonNull("firstBlood")) {
                    d.mainFirstBlood = t.path("firstBlood").asBoolean();
                }
                return;
            }
            // 主队记录缺失时退而求其次：对方数值放主队槽位会误导，保持 -1 不展示
        } catch (Exception e) {
            log.warn("Parse teamsJson failed, resources hidden: {}", e.getMessage());
        }
    }

    /** 读取 stats_json 数值字段：缺失/非数字返回 0 */
    private int statInt(String statsJson, String key) {
        if (statsJson == null || statsJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode stats = objectMapper.readTree(statsJson);
            if (stats.has(key) && !stats.get(key).isNull()) {
                return stats.get(key).asInt(0);
            }
        } catch (Exception e) {
            log.warn("Parse statsJson failed: {}", e.getMessage());
        }
        return 0;
    }

    /** 英雄中文名：数据缺失/查询失败返回占位，不让图出现 null */
    private String safeChampionName(Integer championId) {
        if (championId == null) {
            return "?";
        }
        try {
            String name = gameDataService.championName(championId);
            return name == null ? "英雄" + championId : name;
        } catch (Exception e) {
            log.warn("Champion name lookup failed: championId={}", championId, e);
            return "英雄" + championId;
        }
    }

    /** 对局创建时间 → "08-30 21:47"（北京时间，对齐群里看图的直觉） */
    private String formatGameTime(Long gameCreationMs) {
        if (gameCreationMs == null) {
            return "--";
        }
        java.time.LocalDateTime time = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(gameCreationMs),
                java.time.ZoneId.of("Asia/Shanghai"));
        return String.format("%02d-%02d %02d:%02d", time.getMonthValue(), time.getDayOfMonth(),
                time.getHour(), time.getMinute());
    }

    /** 常用队列中文名（缺失回退数字），供战报文本可读展示 */
    private String queueName(Integer queueId) {
        if (queueId == null) {
            return "对局";
        }
        return switch (queueId) {
            case 420 -> "单双排";
            case 430 -> "匹配";
            case 440 -> "灵活组排";
            case 450 -> "极地大乱斗";
            case 1700 -> "斗魂竞技场";
            case 2400, 2410, 2450 -> "海克斯乱斗";
            default -> "队列" + queueId;
        };
    }

    /** 对局时长：秒 → "28分42秒" */
    private String formatDuration(Integer durationSeconds) {
        if (durationSeconds == null) {
            return "--";
        }
        int minutes = durationSeconds / 60;
        int seconds = durationSeconds % 60;
        return minutes + "分" + String.format("%02d", seconds) + "秒";
    }
}
