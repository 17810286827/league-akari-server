package com.leagueakari.intel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.common.exception.QqPushException;
import com.leagueakari.config.PushProperties;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.dto.intel.GameStartSignalRequest;
import com.leagueakari.entity.EnemyScoutMatch;
import com.leagueakari.entity.EnemyScoutRank;
import com.leagueakari.entity.IntelGameStart;
import com.leagueakari.mapper.EnemyScoutMatchMapper;
import com.leagueakari.mapper.EnemyScoutRankMapper;
import com.leagueakari.mapper.IntelGameStartMapper;
import com.leagueakari.qqbot.QqBotClient;
import com.leagueakari.team.TeamRosterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敌方情报推送编排（Pre-Game Intel Broadcast）：游戏开始信号驱动的发卡状态机。
 * <p>流程：收到游戏开始信号 → 按 gameId 幂等去重 → roster 多数派锚定我方 → 从侦察缓存
 * 聚合敌方情报（段位 + 历史摘要 → 开黑判定 + 窗口胜率）→ 渲染情报卡 → 发车队群。
 * 全程落库推送状态，失败不静默（对齐局后播报语义）。</p>
 * <p>状态机（intel_game_start.push_status）：PENDING →(CAS)→ PUSHING → SENT / FAILED。
 * 与局后播报（match.push_status）分属两条链路：情报推送触发于游戏开始，对局尚未入库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntelBroadcastService {

    /** 推送状态（与 V11__intel_game_start.sql 注释对应） */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUSHING = "PUSHING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    /** 红蓝方 teamId 常量（客户端惯例，见 CONTEXT.md 我方锚定） */
    private static final int TEAM_BLUE = 100;
    private static final int TEAM_RED = 200;

    private final IntelGameStartMapper gameStartMapper;
    private final EnemyScoutMatchMapper scoutMatchMapper;
    private final EnemyScoutRankMapper scoutRankMapper;
    private final TeamRosterService rosterService;
    private final QqBotClient qqBotClient;
    private final IntelCardRenderer renderer;
    private final PushProperties pushProperties;
    private final TeamProperties teamProperties;
    private final com.leagueakari.config.IntelProperties intelProperties;
    /** 游戏静态资源：英雄 ID → 中文名（情报卡英雄名展示） */
    private final com.leagueakari.gamedata.GameDataService gameDataService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 游戏开始信号入口（由 IntelController 调用）：
     * 开关关闭直接返回（零副作用）；开启则按 gameId 幂等去重后进入发卡编排。
     */
    @Transactional
    public void onGameStart(GameStartSignalRequest signal) {
        // 开关门控：未启用视为"未开通"，不落状态（配置好后自然生效）
        if (!pushProperties.isEnabled()) {
            log.info("Intel broadcast skipped: push disabled, gameId={}", signal.getGameId());
            return;
        }
        if (!pushProperties.isConfigured()) {
            log.warn("Intel broadcast skipped: push not configured, gameId={}", signal.getGameId());
            return;
        }

        // 幂等去重：同一 gameId 首次插入成功才发卡；并发时唯一键兜底
        IntelGameStart row = new IntelGameStart();
        row.setGameId(signal.getGameId());
        row.setBluePuids(writeJson(signal.getBluePuids()));
        row.setRedPuids(writeJson(signal.getRedPuids()));
        row.setReporterPuuid(signal.getReporterPuuid());
        row.setPushStatus(STATUS_PENDING);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        try {
            gameStartMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发重复信号：唯一键 uk_intel_game_start_game 兜底，跳过本次
            log.info("Intel broadcast skipped: duplicate gameId={}", signal.getGameId());
            return;
        }

        try {
            doBroadcast(signal, row.getId());
        } catch (Exception e) {
            // 编排异常不影响信号已落库（去重已生效），落库失败原因
            log.error("Intel broadcast orchestration failed: gameId={}", signal.getGameId(), e);
            markFailed(row.getId(), e.getMessage());
        }
    }

    /** 判定 + 聚合 + 渲染 + 发送主流程 */
    private void doBroadcast(GameStartSignalRequest signal, Long rowId) {
        // 1) 我方锚定：roster 多数派所在队（上报者身份仅参考）
        Integer friendlyTeamId = anchorFriendlyTeam(signal);
        if (friendlyTeamId == null) {
            // 双方均无车队成员：非车队对局，不推（情报卡只服务车队视角）
            log.info("Intel broadcast skipped: no fleet member on either side, gameId={}", signal.getGameId());
            markFailed(rowId, "双方均无车队成员，非车队对局");
            return;
        }
        gameStartMapper.update(null, new UpdateWrapper<IntelGameStart>()
                .set("friendly_team_id", friendlyTeamId)
                .eq("id", rowId));

        // 2) 敌方玩家列表 = 非我方队伍的 5 人
        List<String> enemyPuids = friendlyTeamId == TEAM_BLUE
                ? signal.getRedPuids() : signal.getBluePuids();
        if (enemyPuids == null || enemyPuids.isEmpty()) {
            log.warn("Intel broadcast: empty enemy list, gameId={}", signal.getGameId());
            markFailed(rowId, "敌方玩家列表为空");
            return;
        }

        // 3) 聚合敌方情报：段位 + 历史摘要 → 开黑判定 + 窗口胜率
        EnemyIntel intel = buildIntel(signal, friendlyTeamId, enemyPuids);

        // 4) 渲染情报卡
        byte[] png;
        try {
            png = renderer.render(intel);
        } catch (Exception e) {
            log.error("Intel card render failed: gameId={}", signal.getGameId(), e);
            markFailed(rowId, "情报卡渲染失败: " + e.getMessage());
            return;
        }

        // 5) 推送（图片走富媒体通道 msg_type=7）
        try {
            qqBotClient.sendGroupImageMessage(pushProperties.getGroupOpenId(), png);
            markSent(rowId);
            log.info("Intel card sent: gameId={}, friendlyTeam={}, enemyCount={}, pngBytes={}",
                    signal.getGameId(), friendlyTeamId, enemyPuids.size(), png.length);
        } catch (QqPushException e) {
            log.error("Intel card send failed: gameId={}", signal.getGameId(), e);
            markFailed(rowId, e.getMessage());
        }
    }

    /**
     * 我方锚定：统计双方各自车队成员数（按成员身份集合匹配），多数派所在队为我方。
     * 打平时（含双方都 0）返回 null（非车队对局）。
     */
    private Integer anchorFriendlyTeam(GameStartSignalRequest signal) {
        List<TeamRosterService.RosterMember> roster;
        try {
            roster = rosterService.requireMembers();
        } catch (Exception e) {
            log.warn("Intel broadcast: roster unavailable, gameId={}, err={}",
                    signal.getGameId(), e.getMessage());
            return null;
        }
        Map<String, TeamRosterService.RosterMember> byPuuid = new LinkedHashMap<>();
        for (TeamRosterService.RosterMember m : roster) {
            for (String puuid : m.getPuuids()) {
                byPuuid.put(puuid, m);
            }
        }
        long blueCount = countFleet(signal.getBluePuids(), byPuuid);
        long redCount = countFleet(signal.getRedPuids(), byPuuid);
        log.info("Intel anchor: blueFleet={}, redFleet={}, gameId={}", blueCount, redCount, signal.getGameId());

        if (blueCount > redCount) {
            return TEAM_BLUE;
        }
        if (redCount > blueCount) {
            return TEAM_RED;
        }
        return null; // 打平（含双方均 0）
    }

    /** 统计某队包含的车队成员数 */
    private long countFleet(List<String> puuids, Map<String, TeamRosterService.RosterMember> byPuuid) {
        if (puuids == null) {
            return 0;
        }
        return puuids.stream().filter(byPuuid::containsKey).count();
    }

    /** 聚合敌方情报：段位快照 + 历史摘要 → 玩家行 + 开黑分组 */
    private EnemyIntel buildIntel(GameStartSignalRequest signal, Integer friendlyTeamId, List<String> enemyPuids) {
        // 段位快照：按 puuid 批量读
        List<EnemyScoutRank> ranks = scoutRankMapper.selectList(
                new QueryWrapper<EnemyScoutRank>().in("puuid", enemyPuids));
        Map<String, EnemyScoutRank> rankByPuuid = new LinkedHashMap<>();
        for (EnemyScoutRank r : ranks) {
            rankByPuuid.put(r.getPuuid(), r);
        }

        // 历史摘要：按 puuid 批量读（敌方 5 人的窗口）
        List<EnemyScoutMatch> matchRows = scoutMatchMapper.selectList(
                new QueryWrapper<EnemyScoutMatch>().in("puuid", enemyPuids));

        // 玩家行：段位 + 窗口胜率 + 英雄（ID/中文名）
        List<EnemyIntel.EnemyPlayer> players = new ArrayList<>();
        for (String puuid : enemyPuids) {
            EnemyScoutRank rank = rankByPuuid.get(puuid);
            // 窗口胜率：该玩家所有摘要行的胜负
            List<Boolean> wins = matchRows.stream()
                    .filter(r -> puuid.equals(r.getPuuid()))
                    .map(EnemyScoutMatch::getWin)
                    .toList();
            WindowWinRate winRate = WindowWinRate.of(puuid, wins);
            String summonerName = rank != null ? rank.getSummonerName() : firstSummonerName(matchRows, puuid);
            // 英雄 ID 与中文名：段位快照携带的 championId，经 GameDataService 转换
            Integer championId = rank != null ? rank.getChampionId() : null;
            String championName = championId != null && championId > 0
                    ? gameDataService.championName(championId) : null;
            players.add(new EnemyIntel.EnemyPlayer(
                    puuid,
                    summonerName,
                    rank != null ? rank.getTier() : null,
                    rank != null ? rank.getRank() : null,
                    winRate,
                    championId,
                    championName));
        }

        // 开黑判定：把历史摘要按局聚合为同队分组，喂给 PremadeDetector
        List<PremadeDetector.PremadeGroup> groups = detectPremade(matchRows, enemyPuids);

        // 开黑组搭档胜率：全组同场对局（matchIds）中该组所在队伍的胜率
        List<EnemyIntel.PremadeWinRate> premadeWinRates = new ArrayList<>(groups.size());
        for (PremadeDetector.PremadeGroup group : groups) {
            premadeWinRates.add(computePremadeWinRate(group, matchRows));
        }

        return new EnemyIntel(
                teamProperties.getName(),
                friendlyTeamId,
                queueName(signal.getQueueId()),
                players,
                groups,
                premadeWinRates);
    }

    /**
     * 计算开黑组的搭档胜率：在全组同场对局（group.matchIds）中，
     * 统计该组玩家所在队伍的胜场数。组内任一玩家的 win 即代表该组本局胜负
     * （全组同场同队，win 一致）。
     */
    private EnemyIntel.PremadeWinRate computePremadeWinRate(
            PremadeDetector.PremadeGroup group, List<EnemyScoutMatch> matchRows) {
        // 全组同场对局 id 集合 + 组内玩家集合
        Set<String> matchIdSet = new HashSet<>(group.getMatchIds());
        Set<String> playerSet = new HashSet<>(group.getPlayers());
        // 命中组内任一玩家的行（match_id 在支撑集合内），这些行的 win 即该组该局胜负
        List<EnemyScoutMatch> groupRows = matchRows.stream()
                .filter(r -> matchIdSet.contains(r.getMatchId()) && playerSet.contains(r.getPuuid()))
                .toList();
        // 按 match_id 去重（一局可能命中多名组内玩家，win 一致，取一个即可）
        Map<String, Boolean> winByMatch = new LinkedHashMap<>();
        for (EnemyScoutMatch row : groupRows) {
            winByMatch.putIfAbsent(row.getMatchId(), Boolean.TRUE.equals(row.getWin()));
        }
        int wins = 0;
        for (Boolean win : winByMatch.values()) {
            if (Boolean.TRUE.equals(win)) {
                wins++;
            }
        }
        return new EnemyIntel.PremadeWinRate(wins, winByMatch.size());
    }

    /** 从摘要行按局聚合同队分组，调用开黑判定引擎 */
    private List<PremadeDetector.PremadeGroup> detectPremade(
            List<EnemyScoutMatch> rows, List<String> enemyPuids) {
        // 按 match_id 分组
        Map<String, List<EnemyScoutMatch>> byMatch = new LinkedHashMap<>();
        for (EnemyScoutMatch row : rows) {
            byMatch.computeIfAbsent(row.getMatchId(), k -> new ArrayList<>()).add(row);
        }
        // 每局按 teamId 拆成同队撮
        List<PremadeDetector.TeamMatch> matches = new ArrayList<>();
        for (Map.Entry<String, List<EnemyScoutMatch>> e : byMatch.entrySet()) {
            Map<Integer, List<String>> byTeam = new LinkedHashMap<>();
            for (EnemyScoutMatch row : e.getValue()) {
                byTeam.computeIfAbsent(row.getTeamId(), k -> new ArrayList<>()).add(row.getPuuid());
            }
            for (Map.Entry<Integer, List<String>> t : byTeam.entrySet()) {
                matches.add(new PremadeDetector.TeamMatch(e.getKey(), t.getValue()));
            }
        }
        return PremadeDetector.detect(matches, enemyPuids, intelProperties.getPremadeDetectThreshold());
    }

    /** 从摘要行取某玩家首个召唤师名（段位快照缺失时的兜底） */
    private String firstSummonerName(List<EnemyScoutMatch> rows, String puuid) {
        return rows.stream()
                .filter(r -> puuid.equals(r.getPuuid()) && r.getSummonerName() != null)
                .map(EnemyScoutMatch::getSummonerName)
                .findFirst()
                .orElse(null);
    }

    /** 队列中文名（未知队列返回 ID 字符串兜底） */
    private String queueName(Integer queueId) {
        if (queueId == null) {
            return null;
        }
        // 内联常用映射（避免编排层额外依赖 GameDataService；与 GameDataService.QUEUE_NAMES 对齐）
        return switch (queueId) {
            case 420 -> "单双排";
            case 430 -> "匹配";
            case 440 -> "灵活组排";
            case 450 -> "极地大乱斗";
            default -> "队列 " + queueId;
        };
    }

    private void markSent(Long rowId) {
        gameStartMapper.update(null, new UpdateWrapper<IntelGameStart>()
                .set("push_status", STATUS_SENT)
                .eq("id", rowId));
    }

    private void markFailed(Long rowId, String error) {
        String truncated = error == null ? "" : (error.length() > 500 ? error.substring(0, 500) : error);
        gameStartMapper.update(null, new UpdateWrapper<IntelGameStart>()
                .set("push_status", STATUS_FAILED)
                .set("push_error", truncated)
                .eq("id", rowId));
    }

    /** 列表序列化为 JSON（null 安全） */
    private String writeJson(List<String> list) {
        if (list == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("Intel serialize puids failed: {}", e.getMessage());
            return "[]";
        }
    }
}
