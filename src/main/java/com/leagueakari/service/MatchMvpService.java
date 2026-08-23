package com.leagueakari.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MvpScoringInput;
import com.leagueakari.dto.MvpScoringResult;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MVP/SVP 评选编排服务
 * <p>职责：加载英雄职业映射 → 从参与者 statsJson 提取维度原始值 →
 * 调用评分引擎 → 将 MVP/SVP 结果落库 match_mvp 表。</p>
 * <p>幂等：saveMatch 外层已按 gameId 查重，本方法仅做 DuplicateKeyException 兜底，
 * 由 uk_match_mvp(match_id, type) 唯一约束保证并发下不重复。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchMvpService {

    private final MatchMvpMapper matchMvpMapper;
    private final ChampionClassMapper championClassMapper;
    private final MvpScoringEngine mvpScoringEngine;
    private final ObjectMapper objectMapper;

    /**
     * 对一场已落库的对局执行 MVP/SVP 评选并写入结果
     *
     * @param match        对局主表记录（id 已回填）
     * @param participants 已落库的参与者列表（id 已回填）
     */
    public void evaluateAndSave(Match match, List<MatchParticipant> participants) {
        if (match == null || participants == null || participants.size() < 2) {
            log.warn("MVP 评选跳过：对局 {} 参与者数量不足", match == null ? null : match.getGameId());
            return;
        }
        // 1. 加载英雄职业映射（championId → class_name）
        Map<Integer, String> classMap = loadChampionClassMap();

        // 2. 参与者数据 → 评分引擎输入（从 statsJson 提取维度原始值）
        List<MvpScoringInput> inputs = participants.stream()
                .map(p -> toScoringInput(p, match))
                .toList();

        // 3. 调用评分引擎（大乱斗模式修正辅助视野权重）
        MvpScoringResult result = mvpScoringEngine.score(inputs, classMap, match.getGameMode());

        // 4. 分别落库 MVP 与 SVP
        saveOne(match, result.getMvp(), "MVP");
        saveOne(match, result.getSvp(), "SVP");

        log.info("对局 {} 评选完成：MVP=participant({}), SVP=participant({})",
                match.getGameId(),
                result.getMvp() == null ? "无" : result.getMvp().getParticipantId(),
                result.getSvp() == null ? "无" : result.getSvp().getParticipantId());
    }

    /**
     * 加载全量英雄职业映射
     */
    private Map<Integer, String> loadChampionClassMap() {
        List<ChampionClass> all = championClassMapper.selectList(null);
        return all.stream().collect(Collectors.toMap(
                ChampionClass::getChampionId, ChampionClass::getClassName, (a, b) -> a));
    }

    /**
     * 参与者实体 → 评分引擎输入：直显列 + statsJson 提取
     */
    private MvpScoringInput toScoringInput(MatchParticipant p, Match match) {
        JsonNode stats = parseStats(p.getStatsJson());
        return MvpScoringInput.builder()
                .participantId(p.getId())
                .championId(p.getChampionId())
                .teamId(p.getTeamId())
                .win(p.getWin())
                .totalDamageDealtToChampions(d(stats, "totalDamageDealtToChampions"))
                .kills(p.getKills())
                .deaths(p.getDeaths())
                .assists(p.getAssists())
                .goldEarned(p.getGoldEarned())
                .totalMinionsKilled(p.getCs())
                .totalDamageTaken(d(stats, "totalDamageTaken"))
                .visionScore(d(stats, "visionScore"))
                .totalHeal(d(stats, "totalHeal"))
                .totalDamageShieldedOnTeammates(d(stats, "totalDamageShieldedOnTeammates"))
                .timeCCingOthers(d(stats, "timeCCingOthers"))
                .gameDurationSeconds(match.getGameDuration())
                .build();
    }

    /**
     * 写入单条评选结果：null 跳过、DuplicateKey 幂等兜底
     */
    private void saveOne(Match match, MvpScoringResult.PlayerScore player, String type) {
        if (player == null) {
            // 一方无人或数据异常时跳过该称号
            return;
        }
        MatchMvp record = new MatchMvp();
        record.setMatchId(match.getId());
        record.setParticipantId(player.getParticipantId());
        record.setType(type);
        // 总分保留两位小数（DECIMAL(10,2)）
        record.setScore(BigDecimal.valueOf(player.getTotalScore()).setScale(2, RoundingMode.HALF_UP));
        record.setScoreDetailJson(writeDetailJson(player.getDimensionScores()));
        try {
            matchMvpMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发重复推送：唯一约束兜底，保持首次写入
            log.info("MVP 记录已存在，跳过重复写入：matchId={} type={}", match.getId(), type);
        }
    }

    /**
     * 解析 statsJson（null/坏 JSON 均返回空节点，字段按 0 兜底）
     */
    private JsonNode parseStats(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(statsJson);
        } catch (Exception e) {
            log.warn("statsJson 解析失败，按 0 兜底：{}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /** 从 stats 节点取浮点值，缺失返回 0 */
    private Double d(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null ? 0.0 : v.asDouble();
    }

    /**
     * 评分明细序列化为 JSON 字符串，失败返回 null（列允许 NULL）
     */
    private String writeDetailJson(Map<String, MvpScoringResult.DimensionScore> detail) {
        try {
            // 维度 → {raw, score} 结构
            Map<String, Map<String, Double>> out = new HashMap<>();
            detail.forEach((dim, ds) -> out.put(dim, Map.of(
                    "raw", ds.getRaw() == null ? 0.0 : ds.getRaw(),
                    "score", ds.getScore() == null ? 0.0 : ds.getScore())));
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("评分明细序列化失败：{}", e.getMessage());
            return null;
        }
    }
}