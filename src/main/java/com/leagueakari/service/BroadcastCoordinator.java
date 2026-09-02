package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
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

    private final MatchMapper matchMapper;
    private final MatchParticipantMapper participantMapper;
    private final MatchMvpMapper mvpMapper;
    private final PushProperties pushProperties;
    private final TeamProperties teamProperties;
    private final TeamRosterService rosterService;
    private final GameDataService gameDataService;
    private final QqBotClient qqBotClient;
    private final Clock clock;

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

        // 6. 组装战报文本并发送
        String report = buildTextReport(match, participants, memberByPuuid,
                mvpMapper.selectList(new QueryWrapper<MatchMvp>().eq("match_id", match.getId())));
        try {
            qqBotClient.sendGroupTextMessage(pushProperties.getGroupOpenId(), report);
            markSent(gameId);
            log.info("Broadcast sent: gameId={}, fleetMembers={}", gameId, fleetCount);
        } catch (QqPushException e) {
            log.error("Broadcast send failed: gameId={}", gameId, e);
            markFailed(gameId, e.getMessage());
        }
    }

    /** 推送成功：状态 SENT + 战报消息发送时间 */
    private void markSent(Long gameId) {
        matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_SENT)
                .set("push_image_at", LocalDateTime.now())
                .eq("game_id", gameId));
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

    // ---------- 战报文本组装（T2 中间形态：先图后文本中的"文本战报"，T3 起由图替换） ----------

    /**
     * 组装纯文本战报：胜负、比分、模式时长、车队成员战绩行（含 MVP 称号标注）。
     * 车队视角：车队成员多数所在队伍为主队；成员行按击杀降序
     */
    private String buildTextReport(Match match, List<MatchParticipant> participants,
                                   Map<String, TeamRosterService.RosterMember> memberByPuuid,
                                   List<MatchMvp> awards) {
        // 车队成员及其所在队伍统计
        List<MatchParticipant> fleet = participants.stream()
                .filter(p -> memberByPuuid.containsKey(p.getPuuid()))
                .toList();
        Map<Integer, Long> teamCount = new LinkedHashMap<>();
        for (MatchParticipant p : fleet) {
            teamCount.merge(p.getTeamId(), 1L, Long::sum);
        }
        // 主队 = 车队成员多数所在队伍（常规开黑为 5 人同队；分散时取人多的一侧）
        int mainTeamId = teamCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(match.getWinnerTeamId() == null ? 100 : match.getWinnerTeamId());
        boolean win = match.getWinnerTeamId() != null && match.getWinnerTeamId() == mainTeamId;

        // 比分：两队击杀合计
        int mainKills = participants.stream()
                .filter(p -> p.getTeamId() != null && p.getTeamId() == mainTeamId)
                .mapToInt(p -> p.getKills() == null ? 0 : p.getKills())
                .sum();
        int otherKills = participants.stream()
                .filter(p -> p.getTeamId() != null && p.getTeamId() != mainTeamId)
                .mapToInt(p -> p.getKills() == null ? 0 : p.getKills())
                .sum();

        // MVP/ACE 称号：participantId → 称号类型（MVP=胜方最佳 / ACE=败方最佳）
        Map<Long, String> titleByParticipant = new HashMap<>();
        for (MatchMvp award : awards) {
            if (award.getParticipantId() != null && award.getType() != null) {
                titleByParticipant.put(award.getParticipantId(), award.getType());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(win ? "🎉 胜利" : "💀 败北")
                .append(" · ").append(teamProperties.getName()).append(" 开黑局\n");
        sb.append(queueName(match.getQueueId())).append(" · ")
                .append(formatDuration(match.getGameDuration())).append("\n");
        sb.append("比分 ").append(mainKills).append(" : ").append(otherKills).append("\n");
        sb.append("━━━ 车队战绩 ━━━\n");
        fleet.stream()
                .sorted((a, b) -> (b.getKills() == null ? 0 : b.getKills())
                        - (a.getKills() == null ? 0 : a.getKills()))
                .forEach(p -> {
                    sb.append(p.getSummonerName()).append("（")
                            .append(safeChampionName(p.getChampionId())).append("）")
                            .append(" ").append(kda(p)).append("  ");
                    String title = titleByParticipant.get(p.getId());
                    if ("MVP".equals(title)) {
                        sb.append("[MVP]");
                    } else if ("ACE".equals(title) || "SVP".equals(title)) {
                        sb.append("[SVP]");
                    }
                    sb.append("\n");
                });
        return sb.toString();
    }

    /** KDA 文本：k/a/d（缺失按 0） */
    private String kda(MatchParticipant p) {
        return (p.getKills() == null ? 0 : p.getKills()) + "/"
                + (p.getDeaths() == null ? 0 : p.getDeaths()) + "/"
                + (p.getAssists() == null ? 0 : p.getAssists());
    }

    /** 英雄中文名：数据缺失时返回占位，不让文本出现 null */
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
