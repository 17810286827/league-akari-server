package com.leagueakari.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.ScoringConfig;
import com.leagueakari.dto.MvpScoringInput;
import com.leagueakari.dto.OpScoreResult;
import com.leagueakari.dto.PlayerScoreView;
import com.leagueakari.entity.ChampionClass;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.ChampionClassMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MVP/ACE 评选编排服务（OpScore 版本）
 * <p>职责：加载英雄职业映射与基线 → 从参与者 statsJson 提取维度原始值 →
 * 调用 OpScore 评分引擎 → 将 MVP/ACE 结果落库 match_mvp 表（带评分版本号）。</p>
 * <p>幂等：saveMatch 外层已按 gameId 查重，本方法仅做 DuplicateKeyException 兜底，
 * 由 uk_match_mvp(match_id, type) 唯一约束保证并发下不重复。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchMvpService {

    private final MatchMvpMapper matchMvpMapper;
    private final ChampionClassMapper championClassMapper;
    private final OpScoreEngine opScoreEngine;
    private final ScoringConfig scoringConfig;
    private final ObjectMapper objectMapper;
    private final BaselineService baselineService;
    /** stats_json 读取门面：缺失补 0 口径的唯一实现（架构清理 T4） */
    private final ParticipantStatsReader statsReader;

    /**
     * 英雄职业分类缓存：该表无写入口（随版本更新手工维护），
     * 懒加载后复用，避免每场评分都全表查询（volatile 保证多线程可见性）
     */
    private volatile Map<Integer, String> championClassCache;

    /** 当前评分版本（从配置加载） */
    private int scoringVersion() {
        return scoringConfig.getVersion();
    }

    /**
     * 对一场已落库的对局执行 MVP/ACE 评选并写入结果
     *
     * @param match        对局主表记录（id 已回填）
     * @param participants 已落库的参与者列表（id 已回填）
     */
    public void evaluateAndSave(Match match, List<MatchParticipant> participants) {
        if (match == null || participants == null || participants.size() < 2) {
            log.warn("MVP 评选跳过：对局 {} 参与者数量不足", match == null ? null : match.getGameId());
            return;
        }
        Map<Integer, String> classMap = loadChampionClassMap();
        Map<Integer, Map<String, Double>> baseline = baselineService.getBaselineMap();

        List<MvpScoringInput> inputs = participants.stream()
                .map(p -> toScoringInput(p, match))
                .toList();

        OpScoreResult result = opScoreEngine.score(inputs, classMap, baseline);

        saveOne(match, result.getMvp(), "MVP");
        saveOne(match, result.getAce(), "ACE");

        log.info("对局 {} 评选完成：MVP=participant({}), ACE=participant({})",
                match.getGameId(),
                result.getMvp() == null ? "无" : result.getMvp().getParticipantId(),
                result.getAce() == null ? "无" : result.getAce().getParticipantId());
    }

    /**
     * 累积评分基线：对一局全员逐人计算各维度每分钟值，累加进对应英雄的基线记录
     * <p>在对局落库（与 MVP 评选同事务、同一首存路径）时调用，让 scoring_baseline
     * 随对局同步持续积累；基线是 OpScore 基线混合比的分母——无积累时混合比为 0，
     * 评分退化为纯局内位次。幂等性由调用方保证（saveMatch 按 gameId 查重，
     * 仅首存触发，重复推送不会二次累加）。</p>
     *
     * @param match        对局主表记录（id 已回填）
     * @param participants 已落库的参与者列表（id 已回填）
     */
    public void collectBaselines(Match match, List<MatchParticipant> participants) {
        if (match == null || participants == null || participants.isEmpty()) {
            return;
        }
        for (MatchParticipant p : participants) {
            MvpScoringInput input = toScoringInput(p, match);
            // 与评分主流程同源的每分钟值口径（OpScoreEngine.perMinuteValues）
            Map<String, Double> perMinute = opScoreEngine.perMinuteValues(
                    input, opScoreEngine.minutes(input.getGameDurationSeconds()));
            baselineService.updateBaseline(input.getChampionId(), perMinute, input.getGameDurationSeconds());
        }
        log.info("评分基线已累积：matchId={}, participants={}", match.getId(), participants.size());
    }

    /**
     * 查询时实时计算全员评分（纯计算路径，不落库）
     * <p>详情接口调用：对全部参与者跑 OpScore 引擎，按 puuid 索引返回总分与维度明细。
     * 口径与落库评选（evaluateAndSave）完全一致——同一引擎同一权重；老对局（未评选）同样可算。</p>
     *
     * @param match        对局主表记录
     * @param participants 参与者列表（statsJson 提取维度原始值）
     * @return puuid → 评分视图（OP Score + grade + 维度明细）；参与者不足 2 人时返回空 Map
     */
    public Map<String, PlayerScoreView> computeScores(Match match, List<MatchParticipant> participants) {
        if (match == null || participants == null || participants.size() < 2) {
            return Map.of();
        }
        Map<Integer, String> classMap = loadChampionClassMap();
        Map<Integer, Map<String, Double>> baseline = baselineService.getBaselineMap();
        List<MvpScoringInput> inputs = participants.stream()
                .map(p -> toScoringInput(p, match))
                .toList();
        OpScoreResult result = opScoreEngine.score(inputs, classMap, baseline);

        Map<Long, String> puuidById = participants.stream()
                .collect(Collectors.toMap(MatchParticipant::getId, MatchParticipant::getPuuid, (a, b) -> a));
        Map<String, PlayerScoreView> out = new LinkedHashMap<>();
        for (OpScoreResult.PlayerScore ps : result.getPlayerScores()) {
            String puuid = puuidById.get(ps.getParticipantId());
            if (puuid == null) {
                continue;
            }
            Map<String, PlayerScoreView.DimensionScore> dimensions = new LinkedHashMap<>();
            ps.getDimensionScores().forEach((dim, ds) -> dimensions.put(dim,
                    PlayerScoreView.DimensionScore.builder()
                            .raw(ds.getPerMinute() == null ? 0.0 : ds.getPerMinute())
                            .score(ds.getFinalScore() == null ? 0.0 : ds.getFinalScore())
                            .build()));
            out.put(puuid, PlayerScoreView.builder()
                    .opScore(ps.getOpScore())
                    .grade(ps.getGrade())
                    .dimensions(dimensions)
                    .build());
        }
        return out;
    }

    /**
     * 供查询层判断：该对局已落库的评选记录是否使用了当前评分版本。
     * <p>版本不匹配或不存在时，调用方应回退到实时计算（computeScores）。</p>
     */
    public boolean isCurrentVersion(MatchMvp record) {
        return record != null && record.getScoringVersion() != null
                && record.getScoringVersion() == scoringVersion();
    }

    private Map<Integer, String> loadChampionClassMap() {
        Map<Integer, String> cached = championClassCache;
        if (cached == null) {
            cached = championClassMapper.selectList(null).stream()
                    .collect(Collectors.toMap(
                            ChampionClass::getChampionId, ChampionClass::getClassName, (a, b) -> a));
            championClassCache = cached;
        }
        return cached;
    }

    /**
     * 大乱斗系队列 ID（极地大乱斗 450 / 海克斯乱斗 2400、2410、2450）——大乱斗修正的判定依据。
     * <p>必须按 queueId 判定而不能用 gameMode 字符串：LCU 的 CHERRY 实为斗魂竞技场，
     * 旧评分引擎曾因此误判（该分支从未命中）。理由只在此处说一次，其余引用点见本注释。</p>
     */
    private static final Set<Integer> ARAM_QUEUE_IDS = Set.of(450, 2400, 2410, 2450);

    private MvpScoringInput toScoringInput(MatchParticipant p, Match match) {
        boolean aramMode = match.getQueueId() != null && ARAM_QUEUE_IDS.contains(match.getQueueId());
        if (aramMode) {
            // 关键评分节点：大乱斗修正入口（详见 ARAM_QUEUE_IDS 注释）
            log.debug("大乱斗系对局 queueId={}，评分输入启用大乱斗修正（辅助视野归零）", match.getQueueId());
        }
        // 统计字段经门面读取（缺失补 0），不再经 JsonNode 中转
        String statsJson = p.getStatsJson();
        return MvpScoringInput.builder()
                .participantId(p.getId())
                .championId(p.getChampionId())
                .teamId(p.getTeamId())
                .win(p.getWin())
                .aramMode(aramMode)
                .totalDamageDealtToChampions(statsReader.doubleVal(statsJson, "totalDamageDealtToChampions"))
                .kills(p.getKills())
                .deaths(p.getDeaths())
                .assists(p.getAssists())
                .goldEarned(p.getGoldEarned())
                .totalDamageTaken(statsReader.doubleVal(statsJson, "totalDamageTaken"))
                .visionScore(statsReader.doubleVal(statsJson, "visionScore"))
                .totalHeal(statsReader.doubleVal(statsJson, "totalHeal"))
                .totalDamageShieldedOnTeammates(statsReader.doubleVal(statsJson, "totalDamageShieldedOnTeammates"))
                .timeCCingOthers(statsReader.doubleVal(statsJson, "timeCCingOthers"))
                .damageDealtToTurrets(statsReader.doubleVal(statsJson, "damageDealtToTurrets"))
                .doubleKills(statsReader.intVal(statsJson, "doubleKills"))
                .tripleKills(statsReader.intVal(statsJson, "tripleKills"))
                .quadraKills(statsReader.intVal(statsJson, "quadraKills"))
                .pentaKills(statsReader.intVal(statsJson, "pentaKills"))
                .gameDurationSeconds(match.getGameDuration())
                .build();
    }

    /**
     * 写入单条评选结果：null 跳过、DuplicateKey 幂等兜底
     */
    private void saveOne(Match match, OpScoreResult.PlayerScore player, String type) {
        if (player == null) {
            return;
        }
        MatchMvp record = new MatchMvp();
        record.setMatchId(match.getId());
        record.setParticipantId(player.getParticipantId());
        record.setType(type);
        record.setScoringVersion(scoringVersion());
        record.setScore(BigDecimal.valueOf(player.getTotalScore() == null ? 0 : player.getTotalScore())
                .setScale(2, RoundingMode.HALF_UP));
        record.setOpScore(BigDecimal.valueOf(player.getOpScore() == null ? 0 : player.getOpScore())
                .setScale(1, RoundingMode.HALF_UP));
        record.setGrade(player.getGrade());
        record.setScoreDetailJson(writeDetailJson(player.getDimensionScores()));
        try {
            matchMvpMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("MVP 记录已存在，跳过重复写入：matchId={} type={}", match.getId(), type);
        }
    }

    private String writeDetailJson(Map<String, OpScoreResult.DimensionScore> detail) {
        try {
            Map<String, Map<String, Object>> out = new HashMap<>();
            detail.forEach((dim, ds) -> out.put(dim, Map.of(
                    "perMinute", ds.getPerMinute() == null ? 0.0 : ds.getPerMinute(),
                    "teamRank", ds.getTeamRank() == null ? 0.0 : ds.getTeamRank(),
                    "baselineScore", ds.getBaselineScore() == null ? 0.0 : ds.getBaselineScore(),
                    "mix", ds.getMix() == null ? 0.0 : ds.getMix(),
                    "finalScore", ds.getFinalScore() == null ? 0.0 : ds.getFinalScore())));
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("评分明细序列化失败：{}", e.getMessage());
            return null;
        }
    }
}