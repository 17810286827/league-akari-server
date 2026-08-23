package com.leagueakari.service;

import com.leagueakari.dto.MvpScoringInput;
import com.leagueakari.dto.MvpScoringResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MVP/SVP 评分引擎（纯函数，无数据库/HTTP 依赖）
 * <p>按英雄职业差异化权重对每个评分维度做同队归一化，加权平均得总分；
 * 胜方总分最高者为 MVP，负方总分最高者为 SVP。</p>
 * <p>编码约定：图片模式（ARAM/CHERRY）下辅助职业的视野维度权重设为 0，完全去除。</p>
 */
@Slf4j
@Component
public class MvpScoringEngine {

    /** 评分维度名常量 */
    public static final String DIM_DAMAGE = "damage";
    public static final String DIM_KDA = "kda";
    public static final String DIM_GOLD = "gold";
    public static final String DIM_TANK = "tank";
    public static final String DIM_VISION = "vision";
    public static final String DIM_SUPPORT = "support";
    public static final String DIM_CC = "cc";

    /** 大乱斗模式（召唤师峡谷为 CLASSIC） */
    private static final String ARAM_MODE = "CHERRY";

    /**
     * 职业权重表：各职业各维度的权重（0 表示该职业不考察此维度）
     * <p>设计原则：ADC/法师看伤害与 KDA；坦克看承伤与控制；辅助看视野/治疗/控制；刺客战士全能均衡。</p>
     */
    private static final Map<String, Map<String, Double>> CLASS_WEIGHTS = buildClassWeights();

    /** 空间维度名顺序（保持输出确定性） */
    private static final List<String> DIMENSION_NAMES = List.of(
            DIM_DAMAGE, DIM_KDA, DIM_GOLD, DIM_TANK, DIM_VISION, DIM_SUPPORT, DIM_CC);

    /**
     * 构建职业权重表
     */
    private static Map<String, Map<String, Double>> buildClassWeights() {
        Map<String, Map<String, Double>> w = new HashMap<>();
        // 射手 ADC：伤害、KDA、经济为核心
        w.put("ADC", Map.of(DIM_DAMAGE, 3.0, DIM_KDA, 2.0, DIM_GOLD, 1.0, DIM_TANK, 0.5, DIM_VISION, 0.5, DIM_SUPPORT, 0.0, DIM_CC, 0.0));
        // 法师 MAGE：伤害、KDA 为核心，兼顾控制
        w.put("MAGE", Map.of(DIM_DAMAGE, 3.0, DIM_KDA, 2.0, DIM_GOLD, 1.0, DIM_TANK, 0.5, DIM_VISION, 0.5, DIM_SUPPORT, 0.0, DIM_CC, 1.0));
        // 坦克 TANK：承伤、控制为核心，兼顾 KDA
        w.put("TANK", Map.of(DIM_DAMAGE, 0.5, DIM_KDA, 1.5, DIM_GOLD, 0.5, DIM_TANK, 3.0, DIM_VISION, 0.5, DIM_SUPPORT, 0.5, DIM_CC, 2.0));
        // 刺客 ASSASSIN：全能均衡
        w.put("ASSASSIN", Map.of(DIM_DAMAGE, 2.0, DIM_KDA, 2.0, DIM_GOLD, 1.0, DIM_TANK, 1.0, DIM_VISION, 0.5, DIM_SUPPORT, 0.0, DIM_CC, 0.5));
        // 战士 FIGHTER：全能均衡（与刺客一致）
        w.put("FIGHTER", Map.of(DIM_DAMAGE, 2.0, DIM_KDA, 2.0, DIM_GOLD, 1.0, DIM_TANK, 1.0, DIM_VISION, 0.5, DIM_SUPPORT, 0.0, DIM_CC, 0.5));
        // 辅助 SUPPORT：视野、治疗、控制为核心
        w.put("SUPPORT", Map.of(DIM_DAMAGE, 0.5, DIM_KDA, 1.5, DIM_GOLD, 0.5, DIM_TANK, 0.5, DIM_VISION, 3.0, DIM_SUPPORT, 3.0, DIM_CC, 2.0));
        // 未知职业：全能均衡（回退）
        w.put("UNKNOWN", Map.of(DIM_DAMAGE, 2.0, DIM_KDA, 2.0, DIM_GOLD, 1.0, DIM_TANK, 1.0, DIM_VISION, 0.5, DIM_SUPPORT, 0.5, DIM_CC, 0.5));
        return w;
    }

    /**
     * 评分入口（默认经典模式）
     *
     * @param inputs          全队参与者的原始表现数据
     * @param championClassMap 英雄职业映射（championId → class_name），由调用方从数据库加载
     */
    public MvpScoringResult score(List<MvpScoringInput> inputs, Map<Integer, String> championClassMap) {
        return score(inputs, championClassMap, "CLASSIC");
    }

    /**
     * 评分入口：按模式提供权重修正（大乱斗去除辅助视野）
     *
     * @param inputs          全队参与者的原始表现数据
     * @param championClassMap 英雄职业映射（championId → class_name），由调用方从数据库加载
     * @param gameMode        对局模式（CHERRY 视为大乱斗）
     */
    public MvpScoringResult score(List<MvpScoringInput> inputs, Map<Integer, String> championClassMap, String gameMode) {
        if (inputs == null || inputs.size() < 2) {
            throw new IllegalArgumentException("评分至少需要 2 名参与者");
        }
        boolean isAram = ARAM_MODE.equalsIgnoreCase(gameMode);

        List<MvpScoringResult.PlayerScore> playerScores = new ArrayList<>();
        // 先按队分组计算各维度原始值
        Map<Integer, List<MvpScoringInput>> byTeam = inputs.stream()
                .collect(Collectors.groupingBy(MvpScoringInput::getTeamId));

        for (MvpScoringInput in : inputs) {
            playerScores.add(scorePlayer(in, byTeam.get(in.getTeamId()), isAram, championClassMap));
        }

        // 按胜方/负方分组评选
        List<MvpScoringResult.PlayerScore> winners = playerScores.stream()
                .filter(p -> Boolean.TRUE.equals(p.getWin())).collect(Collectors.toList());
        List<MvpScoringResult.PlayerScore> losers = playerScores.stream()
                .filter(p -> Boolean.FALSE.equals(p.getWin()) || p.getWin() == null).collect(Collectors.toList());

        return MvpScoringResult.builder()
                .playerScores(playerScores)
                // 平局取先出现者（输入顺序稳定）
                .mvp(winners.isEmpty() ? null : best(winners))
                .svp(losers.isEmpty() ? null : best(losers))
                .build();
    }

    /**
     * 选择总分最高的参与者（平局取先出现者）
     */
    private MvpScoringResult.PlayerScore best(List<MvpScoringResult.PlayerScore> group) {
        // 保持输入顺序稳定：总分相等时靠前的优先
        return group.stream().max((a, b) -> Double.compare(a.getTotalScore(), b.getTotalScore())).orElse(null);
    }

    /**
     * 计算单名参与者的评分
     *
     * @param in              参与者原始数据
     * @param team            同队参与者列表（归一化基准）
     * @param isAram          是否大乱斗模式
     * @param championClassMap 英雄职业映射
     */
    private MvpScoringResult.PlayerScore scorePlayer(MvpScoringInput in, List<MvpScoringInput> team, boolean isAram,
                                                      Map<Integer, String> championClassMap) {
        // 1. 确定职业（未知 → 回退均衡）
        String classKey = resolveClass(in.getChampionId(), championClassMap);
        Map<String, Double> weights = new HashMap<>(CLASS_WEIGHTS.getOrDefault(classKey, CLASS_WEIGHTS.get("UNKNOWN")));
        // 大乱斗：辅助视野权重归零
        if (isAram && "SUPPORT".equals(classKey)) {
            weights.put(DIM_VISION, 0.0);
        }
        // 权重为 0 的维度从 weights 中移除，使其不参与归一化输出
        Map<String, Double> effectiveWeights = new HashMap<>();
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            if (e.getValue() > 0) {
                effectiveWeights.put(e.getKey(), e.getValue());
            }
        }

        // 2. 计算各维度原始值
        Map<String, Double> raw = rawValues(in);

        // 3. 同队归一化（team 内组内比较）
        Map<String, Double> normalized = normalize(raw, team, isAram);

        // 4. 加权平均总分
        double totalWeight = effectiveWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        double totalScore = DIMENSION_NAMES.stream()
                .mapToDouble(dim -> normalized.getOrDefault(dim, 0.0) * effectiveWeights.getOrDefault(dim, 0.0))
                .sum() / (totalWeight == 0 ? 1 : totalWeight);

        // 5. 组装明细：只输出有效权重的维度
        Map<String, MvpScoringResult.DimensionScore> dimensions = new HashMap<>();
        for (String dim : DIMENSION_NAMES) {
            if (!effectiveWeights.containsKey(dim)) {
                continue;
            }
            dimensions.put(dim, MvpScoringResult.DimensionScore.builder()
                    .raw(raw.getOrDefault(dim, 0.0))
                    .score(normalized.getOrDefault(dim, 0.0))
                    .build());
        }

        return MvpScoringResult.PlayerScore.builder()
                .participantId(in.getParticipantId())
                .championId(in.getChampionId())
                .teamId(in.getTeamId())
                .win(in.getWin())
                .totalScore(totalScore)
                .dimensionScores(dimensions)
                .build();
    }

    /**
     * 解析英雄职业：从 championClassMap 中查找，未找到则回退 UNKNOWN
     *
     * @param championId       英雄 ID
     * @param championClassMap 职业映射（由调用方从数据库加载）
     */
    private String resolveClass(Integer championId, Map<Integer, String> championClassMap) {
        if (championId == null || championClassMap == null) {
            return "UNKNOWN";
        }
        return championClassMap.getOrDefault(championId, "UNKNOWN");
    }

    /**
     * 计算并归一化各维度（同队 5 人内 0-100）
     */
    private Map<String, Double> normalize(Map<String, Double> raw, List<MvpScoringInput> team, boolean isAram) {
        Map<String, Double> result = new HashMap<>();
        for (String dim : DIMENSION_NAMES) {
            if (DIM_VISION.equals(dim) && isAram) {
                // 大乱斗视野维度不参与归一化（配合权重 0 完全去除）
                result.put(dim, 0.0);
                continue;
            }
            List<MvpScoringInput> base = team == null ? List.of() : team;
            double min = base.stream()
                    .mapToDouble(t -> valueFor(t, dim)).min().orElse(0);
            double max = base.stream()
                    .mapToDouble(t -> valueFor(t, dim)).max().orElse(0);
            double selfValue = raw.getOrDefault(dim, 0.0);
            if (max == min) {
                result.put(dim, 100.0); // 全员同值 → 都 100
            } else {
                result.put(dim, (selfValue - min) / (max - min) * 100.0);
            }
        }
        return result;
    }

    /**
     * 从某参与者的原始数据中取某维度原始值
     */
    private double valueFor(MvpScoringInput in, String dim) {
        return rawValues(in).getOrDefault(dim, 0.0);
    }

    /**
     * 计算一名参与者的各维度原始值
     */
    private Map<String, Double> rawValues(MvpScoringInput in) {
        Map<String, Double> m = new HashMap<>();
        m.put(DIM_DAMAGE, orZero(in.getTotalDamageDealtToChampions()));
        // KDA：死亡为 0 时按 1 计，避免除零
        int deaths = in.getDeaths() == null ? 0 : in.getDeaths();
        int kdaNumerator = (in.getKills() == null ? 0 : in.getKills())
                + (in.getAssists() == null ? 0 : in.getAssists());
        m.put(DIM_KDA, kdaNumerator / (double) Math.max(deaths, 1));
        m.put(DIM_GOLD, orZero((double) (in.getGoldEarned() == null ? 0 : in.getGoldEarned())));
        m.put(DIM_TANK, orZero(in.getTotalDamageTaken()));
        m.put(DIM_VISION, orZero(in.getVisionScore()));
        // 辅助贡献：治疗 + 护盾
        m.put(DIM_SUPPORT, orZero(in.getTotalHeal()) + orZero(in.getTotalDamageShieldedOnTeammates()));
        m.put(DIM_CC, orZero(in.getTimeCCingOthers()));
        return m;
    }

    /** null to 0 */
    private double orZero(Double v) {
        return v == null ? 0.0 : v;
    }
}