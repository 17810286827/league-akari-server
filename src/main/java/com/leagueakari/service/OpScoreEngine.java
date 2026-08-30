package com.leagueakari.service;

import com.leagueakari.config.ScoringConfig;
import com.leagueakari.dto.MvpScoringInput;
import com.leagueakari.dto.OpScoreResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OpScore 评分引擎（纯函数，无数据库/HTTP 依赖）
 * <p>按英雄职业差异化权重对每个评分维度做 {@code 局内位次分×混合比 + 基线分×(1-混合比)}
 * 合成，加权平均得 0-100 总分，除以 10 映射到 0-10 OP Score，再加多杀加分。</p>
 * <p>胜方 op_score 最高者为 MVP，败方 op_score 最高者为 ACE。
 * 评选以 op_score（含多杀加分，与界面展示的评分一致）为准，总分相同加分反超时同样生效；
 * op_score 平局时按加权总分决胜。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpScoreEngine {

    /** 评分维度名常量 */
    public static final String DIM_DAMAGE = "damage";
    public static final String DIM_KDA = "kda";
    public static final String DIM_GOLD = "gold";
    public static final String DIM_TANK = "tank";
    public static final String DIM_VISION = "vision";
    public static final String DIM_HEAL = "healShield";
    public static final String DIM_CC = "cc";
    public static final String DIM_TURRET = "turret";

    /** 维度顺序（保持输出确定性） */
    static final List<String> DIMENSION_NAMES = List.of(
            DIM_DAMAGE, DIM_KDA, DIM_GOLD, DIM_TANK, DIM_VISION, DIM_HEAL, DIM_CC, DIM_TURRET);

    /** 职业常量 */
    static final String CLASS_UNKNOWN = "UNKNOWN";

    private final ScoringConfig config;

    /**
     * 评分入口
     *
     * @param inputs          全队参与者的原始表现数据
     * @param championClassMap 英雄职业映射（championId → class_name），由调用方从数据库加载
     * @param baseline        每个参与的英雄的可信基线（championId → {dim → 每分钟均值}，null 表示该英雄无基线样本）
     */
    public OpScoreResult score(List<MvpScoringInput> inputs, Map<Integer, String> championClassMap,
                               Map<Integer, Map<String, Double>> baseline) {
        if (inputs == null || inputs.size() < 2) {
            throw new IllegalArgumentException("评分至少需要 2 名参与者");
        }
        Map<Integer, List<MvpScoringInput>> byTeam = inputs.stream()
                .collect(Collectors.groupingBy(MvpScoringInput::getTeamId));

        List<OpScoreResult.PlayerScore> playerScores = new ArrayList<>();
        for (MvpScoringInput in : inputs) {
            playerScores.add(scorePlayer(in, byTeam.get(in.getTeamId()), championClassMap,
                    baseline == null ? Map.of() : baseline));
        }

        List<OpScoreResult.PlayerScore> winners = playerScores.stream()
                .filter(p -> Boolean.TRUE.equals(p.getWin())).collect(Collectors.toList());
        List<OpScoreResult.PlayerScore> losers = playerScores.stream()
                .filter(p -> !Boolean.TRUE.equals(p.getWin())).collect(Collectors.toList());

        return OpScoreResult.builder()
                .playerScores(playerScores)
                .mvp(winners.isEmpty() ? null : best(winners))
                .ace(losers.isEmpty() ? null : best(losers))
                .build();
    }

    /**
     * 选择 op_score 最高者（与界面展示的评分一致，含多杀加分）；
     * op_score 平局时按加权总分决胜，仍平局取先出现者
     */
    private OpScoreResult.PlayerScore best(List<OpScoreResult.PlayerScore> group) {
        return group.stream()
                .max((a, b) -> {
                    int byOpScore = Double.compare(a.getOpScore(), b.getOpScore());
                    return byOpScore != 0 ? byOpScore
                            : Double.compare(a.getTotalScore(), b.getTotalScore());
                })
                .orElse(null);
    }

    /**
     * 计算单名参与者的评分
     */
    OpScoreResult.PlayerScore scorePlayer(MvpScoringInput in, List<MvpScoringInput> team,
                                          Map<Integer, String> championClassMap,
                                          Map<Integer, Map<String, Double>> baseline) {
        // 1. 确定职业（未知 → 回退均衡）
        String classKey = resolveClass(in.getChampionId(), championClassMap);
        Map<String, Double> weights = effectiveWeights(classKey);

        // 2. 各维度每分钟值
        double minutes = minutes(in.getGameDurationSeconds());
        Map<String, Double> rawPerMinute = perMinuteValues(in, minutes);

        // 3. 队内位次分（最高 100，最低 0）
        Map<String, Double> teamRank = teamRankScores(rawPerMinute, team);

        // 4. 基线分（无基线样本时退化为纯局内）
        Map<String, Double> baselineScores = baselineScores(rawPerMinute, baseline.get(in.getChampionId()), weights);

        // 5. 各维度混合分 → 加权总分
        double totalWeight = 0;
        double weightedSum = 0;
        Map<String, OpScoreResult.DimensionScore> details = new HashMap<>();
        for (String dim : DIMENSION_NAMES) {
            double w = weights.getOrDefault(dim, 0.0);
            if (w <= 0) {
                continue;
            }
            totalWeight += w;
            double mix = mixRatio(baseline.get(in.getChampionId()), dim);
            double finalDim = clamp(teamRank.getOrDefault(dim, 0.0) * (1 - mix)
                    + baselineScores.getOrDefault(dim, 0.0) * mix, 0, 100);
            weightedSum += finalDim * w;
            details.put(dim, OpScoreResult.DimensionScore.builder()
                    .perMinute(rawPerMinute.getOrDefault(dim, 0.0))
                    .teamRank(round1(teamRank.getOrDefault(dim, 0.0)))
                    .baselineScore(round1(baselineScores.getOrDefault(dim, 0.0)))
                    .mix(round2(mix))
                    .finalScore(round1(finalDim))
                    .build());
        }
        double totalScore = totalWeight == 0 ? 0 : weightedSum / totalWeight;

        // 6. 多杀加分
        double multiKillBonus = multiKillBonus(in);

        // 7. OP Score = clamp(总分/10 + 多杀加分, 0, 10)
        double opScore = clamp(totalScore / 10 + multiKillBonus, 0, 10);

        return OpScoreResult.PlayerScore.builder()
                .participantId(in.getParticipantId())
                .championId(in.getChampionId())
                .teamId(in.getTeamId())
                .win(in.getWin())
                .totalScore(round1(totalScore))
                .opScore(round1(opScore))
                .grade(grade(opScore))
                .multiKillBonus(round2(multiKillBonus))
                .dimensionScores(details)
                .build();
    }

    /**
     * 某维度当前混合比：基线样本量不足 thresholdMin 时为 0（纯局内），
     * thresholdMin~thresholdMax 间线性过渡，达到 thresholdMax 后锁定 baselineMixMax
     *
     * @param baselineOfChampion 该英雄的基线（null = 无样本）
     */
    double mixRatio(Map<String, Double> baselineOfChampion, String dim) {
        if (baselineOfChampion == null || !baselineOfChampion.containsKey("sampleCount")
                || !baselineOfChampion.containsKey(dim)) {
            return 0.0;
        }
        double n = baselineOfChampion.get("sampleCount");
        int min = config.getBaselineThresholdMin();
        int max = config.getBaselineThresholdMax();
        double mixMax = config.getBaselineMixMax();
        if (n <= 0) {
            return 0.0;
        }
        if (n < min) {
            return 0.0;
        }
        if (n >= max) {
            return mixMax;
        }
        // 线性过渡：(n - min) / (max - min) * mixMax
        return (n - min) / (double) (max - min) * mixMax;
    }

    /** 生成有效权重表：权重为 0 的维度不出现在结果中 */
    Map<String, Double> effectiveWeights(String classKey) {
        Map<String, Double> all = config.getWeights()
                .getOrDefault(classKey, config.getWeights().getOrDefault(CLASS_UNKNOWN, Map.of()));
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> e : all.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** 解析英雄职业：未找到回退 UNKNOWN */
    String resolveClass(Integer championId, Map<Integer, String> championClassMap) {
        if (championId == null || championClassMap == null) {
            return CLASS_UNKNOWN;
        }
        return championClassMap.getOrDefault(championId, CLASS_UNKNOWN);
    }

    /** 游戏时长秒 → 分钟（不足 1 局按 1 分钟兜底，避免除零） */
    double minutes(Integer gameDurationSeconds) {
        if (gameDurationSeconds == null || gameDurationSeconds <= 0) {
            return 1.0;
        }
        return gameDurationSeconds / 60.0;
    }

    /** 计算各维度每分钟值 */
    Map<String, Double> perMinuteValues(MvpScoringInput in, double minutes) {
        Map<String, Double> m = new HashMap<>();
        m.put(DIM_DAMAGE, orZero(in.getTotalDamageDealtToChampions()) / minutes);
        m.put(DIM_GOLD, (in.getGoldEarned() == null ? 0 : in.getGoldEarned()) / minutes);
        m.put(DIM_TANK, orZero(in.getTotalDamageTaken()) / minutes);
        m.put(DIM_VISION, orZero(in.getVisionScore()) / minutes);
        m.put(DIM_HEAL, (orZero(in.getTotalHeal()) + orZero(in.getTotalDamageShieldedOnTeammates())) / minutes);
        m.put(DIM_CC, orZero(in.getTimeCCingOthers()) / minutes);
        m.put(DIM_TURRET, orZero(in.getDamageDealtToTurrets()) / minutes);
        // KDA：死亡为 0 时按 1 计，避免除零；KDA 本身无量纲，不随时间增长
        int deaths = in.getDeaths() == null ? 0 : in.getDeaths();
        double kdaNumerator = (in.getKills() == null ? 0 : in.getKills())
                + (in.getAssists() == null ? 0 : in.getAssists());
        m.put(DIM_KDA, kdaNumerator / (double) Math.max(deaths, 1));
        return m;
    }

    /**
     * 队内位次分（同队 5 人内）：最高 100、75、50、25、最低 0
     * <p>若维度全员同值或不足 2 人，位次不打分差（一律 50）。</p>
     */
    Map<String, Double> teamRankScores(Map<String, Double> selfPerMinute, List<MvpScoringInput> team) {
        Map<String, Double> result = new HashMap<>();
        if (team == null || team.size() < 2) {
            return result;
        }
        double minutes = minutes(team.get(0).getGameDurationSeconds());
        for (String dim : DIMENSION_NAMES) {
            List<Double> values = team.stream()
                    .map(t -> perMinuteValues(t, minutes).getOrDefault(dim, 0.0))
                    .sorted((a, b) -> Double.compare(b, a)) // 降序
                    .collect(Collectors.toList());
            if (values.get(0).equals(values.get(values.size() - 1))) {
                // 全员同值：位次分无区分度，给 50
                result.put(dim, 50.0);
            } else {
                double self = selfPerMinute.getOrDefault(dim, 0.0);
                long ahead = values.stream().filter(v -> v > self).count();
                long equal = values.stream().filter(v -> v.equals(self)).count();
                // 并列名次取平均：如两人并列第一共享 (100+75)/2
                long rankStart = ahead + 1;
                long rankEnd = ahead + equal;
                double avgRank = (rankStart + rankEnd) / 2.0;
                double score = 100 - (avgRank - 1) * 25; // 第 1=100，第 2=75，...，第 5=0
                result.put(dim, clamp(score, 0, 100));
            }
        }
        return result;
    }

    /**
     * 基线分：{@code 我的每分钟值 / 基线均值 × 100}，截断 0-100。
     * 英雄无基线样本或该维度无基线值时返回 0（此时混合比必为 0，不参与总分）。
     */
    Map<String, Double> baselineScores(Map<String, Double> selfPerMinute,
                                       Map<String, Double> baselineOfChampion,
                                       Map<String, Double> weights) {
        Map<String, Double> result = new HashMap<>();
        if (baselineOfChampion == null) {
            return result;
        }
        for (String dim : DIMENSION_NAMES) {
            if (weights.getOrDefault(dim, 0.0) <= 0) {
                continue;
            }
            Double base = baselineOfChampion.get(dim);
            if (base == null || base <= 0) {
                result.put(dim, 0.0);
                continue;
            }
            double score = selfPerMinute.getOrDefault(dim, 0.0) / base * 100;
            result.put(dim, clamp(score, 0, 100));
        }
        return result;
    }

    /** 多杀加分：双杀 0.2、三杀 0.5、四杀 1.0、五杀 2.0 */
    double multiKillBonus(MvpScoringInput in) {
        Map<String, Double> bonusMap = config.getMultiKillBonus();
        if (bonusMap == null || bonusMap.isEmpty()) {
            return 0.0;
        }
        double bonus = 0;
        bonus += (in.getDoubleKills() == null ? 0 : in.getDoubleKills()) * orZero(bonusMap.get("double"));
        bonus += (in.getTripleKills() == null ? 0 : in.getTripleKills()) * orZero(bonusMap.get("triple"));
        bonus += (in.getQuadraKills() == null ? 0 : in.getQuadraKills()) * orZero(bonusMap.get("quadra"));
        bonus += (in.getPentaKills() == null ? 0 : in.getPentaKills()) * orZero(bonusMap.get("penta"));
        return bonus;
    }

    /** OP Score → 文字等级（8 档，复刻 op.gg 区间） */
    String grade(double opScore) {
        if (opScore >= 9.0) {
            return "完美";
        }
        if (opScore >= 8.0) {
            return "卓越";
        }
        if (opScore >= 7.0) {
            return "优秀";
        }
        if (opScore >= 6.0) {
            return "良好";
        }
        if (opScore >= 5.0) {
            return "一般";
        }
        if (opScore >= 4.0) {
            return "偏低";
        }
        if (opScore >= 3.0) {
            return "较差";
        }
        return "糟糕";
    }

    private double orZero(Double v) {
        return v == null ? 0.0 : v;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}