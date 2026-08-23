package com.leagueakari.service;

import com.leagueakari.dto.MvpScoringInput;
import com.leagueakari.dto.MvpScoringResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MvpScoringEngine 评分引擎单元测试
 * <p>覆盖核心契约：职业权重、同队归一化、大乱斗辅助视野去除、缺失职业回退、平局确定性。</p>
 */
class MvpScoringEngineTest {

    /** 英雄职业映射（championId → class_name），模拟 champion_class 表数据 */
    private static final Map<Integer, String> CLASS_MAP = Map.ofEntries(
            Map.entry(22, "ADC"),    // 艾希
            Map.entry(51, "ADC"),    // 希维尔
            Map.entry(57, "TANK"),   // 茂凯
            Map.entry(81, "ADC"),    // 伊泽瑞尔
            Map.entry(96, "ADC"),    // 克格莫
            Map.entry(16, "SUPPORT"), // 索拉卡
            Map.entry(84, "ASSASSIN"), // 阿卡丽
            Map.entry(24, "FIGHTER")   // 贾克斯
    );

    private final MvpScoringEngine engine = new MvpScoringEngine();

    /**
     * 构造一名参与者的评分输入
     *
     * @param championId   英雄 ID
     * @param teamId       队伍 ID（100/200）
     * @param win          是否获胜
     * @param damage       对英雄伤害
     * @param kills        击杀
     * @param deaths       死亡
     * @param assists      助攻
     * @param goldEarned   金币
     * @param cs           补刀
     * @param damageTaken  承伤
     * @param vision       视野得分
     * @param heal         治疗量
     * @param shield       护盾量
     * @param cc           控制时长
     */
    private MvpScoringInput player(int championId, int teamId, boolean win,
                                   int damage, int kills, int deaths, int assists,
                                   int goldEarned, int cs, int damageTaken,
                                   int vision, int heal, int shield, int cc) {
        return MvpScoringInput.builder()
                .participantId((long) championId) // 用 championId 充当 participantId 便于断言
                .championId(championId)
                .teamId(teamId)
                .win(win)
                .totalDamageDealtToChampions((double) damage)
                .kills(kills)
                .deaths(deaths)
                .assists(assists)
                .goldEarned(goldEarned)
                .totalMinionsKilled(cs)
                .totalDamageTaken((double) damageTaken)
                .visionScore((double) vision)
                .totalHeal((double) heal)
                .totalDamageShieldedOnTeammates((double) shield)
                .timeCCingOthers((double) cc)
                .gameDurationSeconds(1800)
                .build();
    }

    // ============ 1. 职业权重生效 ============

    private List<MvpScoringInput> inputs(MvpScoringInput... ps) {
        return List.of(ps);
    }

    private MvpScoringResult score(List<MvpScoringInput> inputs) {
        return engine.score(inputs, CLASS_MAP);
    }

    private MvpScoringResult score(List<MvpScoringInput> inputs, String gameMode) {
        return engine.score(inputs, CLASS_MAP, gameMode);
    }

    /**
     * 用例：ADC 靠伤害 + KDA 优势胜出，验证伤害维度权重生效
     * <p>ADC 伤害/KDA/经济全面碾压，坦克仅承伤/控制占优，ADC 总分更高。</p>
     */
    @Test
    @DisplayName("职业权重生效：高输出 ADC 总分高于坦克")
    void adcOutscoresTankWithDamageAdvantage() {
        List<MvpScoringInput> inputs = inputs(
                player(22, 100, true, 50000, 15, 1, 10, 20000, 300, 10000, 20, 0, 0, 0),   // 艾希 ADC：伤害碾压
                player(57, 100, true, 5000, 2, 5, 10, 10000, 150, 60000, 20, 0, 0, 40));  // 茂凯 TANK：承伤碾压

        MvpScoringResult result = score(inputs);

        // 职业化归一化：ADC 伤害维度满分，坦克承伤维度满分（各得其所）
        assertThat(result.getPlayerScores().get(0).getDimensionScores().get("damage").getScore()).isEqualTo(100.0);
        assertThat(result.getPlayerScores().get(1).getDimensionScores().get("tank").getScore()).isEqualTo(100.0);
        // ADC 以伤害(3.0)+KDA(2.0)+经济(1.0) 三项满分的权重优势胜出
        assertThat(result.getPlayerScores().get(0).getTotalScore())
                .isGreaterThan(result.getPlayerScores().get(1).getTotalScore());
    }

    /**
     * 用例：坦克承伤 + 高 KDA 时，评分高于伤害高但其他贡献少的 ADC
     * <p>坦克 5/2/12 承伤 6 万控制 40 秒是完美坦克表现，
     * ADC 9/5/6 虽然输出 3 万但死亡多、无其他贡献，坦克应胜出。</p>
     */
    @Test
    @DisplayName("坦克承伤权重生效：完美坦克表现优于单纯输出的 ADC")
    void tankScoresDamageTakenHigher() {
        List<MvpScoringInput> inputs = inputs(
                player(57, 100, true, 10000, 5, 2, 12, 10000, 150, 60000, 30, 2000, 1000, 40), // 茂凯：承伤+KDA+控制
                player(22, 100, true, 30000, 9, 5, 6, 15000, 280, 18000, 15, 0, 0, 5));        // 艾希：高输出但贡献单一

        MvpScoringResult result = score(inputs);

        // 坦克以承伤(3.0)+KDA(1.5)+控制(2.0)+辅助(0.5) 多维度满分胜出
        assertThat(result.getPlayerScores().get(0).getTotalScore())
                .isGreaterThan(result.getPlayerScores().get(1).getTotalScore());
    }

    // ============ 2. 同队归一化 ============

    /**
     * 用例：同队归一化——伤害最高得 100，最低得 0
     */
    @Test
    @DisplayName("同队归一化：队内最高 100 分，最低 0 分")
    void teamNormalizationScalesTo100() {
        List<MvpScoringInput> inputs = List.of(
                player(22, 100, true, 30000, 10, 2, 5, 12000, 200, 10000, 10, 0, 0, 0), // 伤害最高
                player(51, 100, true, 20000, 8, 3, 4, 10000, 180, 9000, 10, 0, 0, 0),  // 伤害居中
                player(81, 100, true, 10000, 5, 3, 3, 8000, 150, 8000, 10, 0, 0, 0));  // 伤害最低

        MvpScoringResult result = score(inputs);

        assertThat(result.getPlayerScores().get(0).getDimensionScores().get("damage").getScore()).isEqualTo(100.0);
        assertThat(result.getPlayerScores().get(2).getDimensionScores().get("damage").getScore()).isEqualTo(0.0);
    }

    /**
     * 用例：全员同值时该维度都得 100 分（避免除零）
     */
    @Test
    @DisplayName("全员同值维度不除零：全部得 100 分")
    void allEqualValuesGive100() {
        List<MvpScoringInput> inputs = List.of(
                player(22, 100, true, 20000, 10, 2, 5, 12000, 200, 10000, 10, 0, 0, 0),
                player(51, 100, true, 20000, 10, 2, 5, 12000, 200, 10000, 10, 0, 0, 0));

        MvpScoringResult result = score(inputs);

        assertThat(result.getPlayerScores().get(0).getDimensionScores().get("damage").getScore()).isEqualTo(100.0);
        assertThat(result.getPlayerScores().get(1).getDimensionScores().get("damage").getScore()).isEqualTo(100.0);
    }

    /**
     * 用例：KDA 参与归一化——死亡数影响 KDA 分值
     */
    @Test
    @DisplayName("KDA 维度参与归一化")
    void kdaDimensionIsNormalized() {
        List<MvpScoringInput> inputs = List.of(
                player(22, 100, true, 20000, 15, 1, 10, 12000, 200, 10000, 10, 0, 0, 0), // KDA高
                player(51, 100, true, 20000, 5, 8, 3, 12000, 200, 10000, 10, 0, 0, 0));  // KDA低

        MvpScoringResult result = score(inputs);

        double kdaHigh = result.getPlayerScores().get(0).getDimensionScores().get("kda").getScore();
        double kdaLow = result.getPlayerScores().get(1).getDimensionScores().get("kda").getScore();
        assertThat(kdaHigh).isGreaterThan(kdaLow);
    }

    // ============ 3. 大乱斗辅助视野去除 ============

    /**
     * 用例：大乱斗（ARAM/CHERRY）模式下辅助视野权重为 0
     * <p>两个完全相同的辅助，仅视野不同，总分应一致（视野不参与）；</p>
     * <p>而经典模式（CLASSIC）下两者总分应不同。</p>
     */
    @Test
    @DisplayName("大乱斗模式辅助视野维度完全去除")
    void aramRemovesSupportVision() {
        // 两名完全相同的辅助，仅视野分不同
        MvpScoringInput supportLowVision = player(16, 100, true, 8000, 2, 4, 12, 8000, 60, 12000, 5, 10000, 3000, 30);   // 视野低
        MvpScoringInput supportHighVision = player(16, 100, true, 8000, 2, 4, 12, 8000, 60, 12000, 60, 10000, 3000, 30);  // 视野高

        // ARAM 模式：视野不参与 → 无视野维度或权重为 0，总分相同
        MvpScoringResult aram = score(List.of(supportLowVision, supportHighVision), "CHERRY");
        assertThat(aram.getPlayerScores().get(0).getTotalScore())
                .isEqualTo(aram.getPlayerScores().get(1).getTotalScore());
        // 且视野维度不存在于明细中（或 score 恒定）
        assertThat(aram.getPlayerScores().get(0).getDimensionScores().keySet())
                .doesNotContain("vision");

        // CLASSIC 模式：视野参与 → 总分不同
        MvpScoringResult classic = score(List.of(supportLowVision, supportHighVision), "CLASSIC");
        assertThat(classic.getPlayerScores().get(0).getTotalScore())
                .isNotEqualTo(classic.getPlayerScores().get(1).getTotalScore());
    }

    // ============ 4. 缺失职业回退 ============

    /**
     * 用例：未知 championId → 回退到全能均衡权重，不抛异常
     */
    @Test
    @DisplayName("未知英雄回退到均衡权重不抛异常")
    void unknownChampionFallsBackToBalanced() {
        List<MvpScoringInput> inputs = List.of(
                player(99999, 100, true, 20000, 5, 3, 5, 12000, 200, 15000, 15, 0, 0, 0),
                player(88888, 100, true, 15000, 4, 4, 4, 10000, 180, 12000, 10, 0, 0, 0));

        MvpScoringResult result = score(inputs);

        // 不抛异常，能给出总分
        assertThat(result.getPlayerScores()).hasSize(2);
        assertThat(result.getPlayerScores().get(0).getTotalScore()).isNotNull();
    }

    // ============ 5. 评选规则 ============

    /**
     * 用例：胜方最高者为 MVP，负方最高者为 SVP
     */
    @Test
    @DisplayName("胜方最高 MVP 负方最高 SVP")
    void selectsMvpAndSvp() {
        List<MvpScoringInput> inputs = List.of(
                player(22, 100, true, 30000, 15, 1, 8, 15000, 250, 15000, 20, 0, 0, 0),   // 胜方最高
                player(51, 100, true, 20000, 8, 3, 4, 10000, 180, 10000, 15, 0, 0, 0),    // 胜方
                player(81, 200, false, 25000, 12, 4, 6, 13000, 220, 12000, 10, 0, 0, 0),  // 负方最高
                player(96, 200, false, 15000, 5, 6, 3, 8000, 150, 9000, 8, 0, 0, 0));     // 负方

        MvpScoringResult result = score(inputs);

        assertThat(result.getMvp().getParticipantId()).isEqualTo(22L);
        assertThat(result.getSvp().getParticipantId()).isEqualTo(81L);
    }

    /**
     * 用例：平局时取先出现的参与者（确定性）
     */
    @Test
    @DisplayName("平局时取先出现的参与者")
    void tieBreaksByFirstAppearance() {
        // 两名完全相同的胜方选手 → 取先出现的（participantId 22）
        List<MvpScoringInput> inputs = List.of(
                player(22, 100, true, 20000, 10, 2, 5, 12000, 200, 10000, 10, 0, 0, 0),
                player(51, 100, true, 20000, 10, 2, 5, 12000, 200, 10000, 10, 0, 0, 0));

        MvpScoringResult result = score(inputs);

        assertThat(result.getMvp().getParticipantId()).isEqualTo(22L);
    }

    // ============ 6. 刺客/战士同方案 ============

    /**
     * 用例：刺客与战士使用同一套权重——相同数据下总分一致
     */
    @Test
    @DisplayName("刺客与战士总分一致（同权重）")
    void assassinAndFighterSameWeights() {
        // 相同数据的两个选手，一个刺客一个战士
        MvpScoringInput assassin = player(84, 100, true, 18000, 12, 3, 6, 11000, 150, 14000, 10, 1000, 500, 20);
        MvpScoringInput fighter = player(24, 100, true, 18000, 12, 3, 6, 11000, 150, 14000, 10, 1000, 500, 20);

        MvpScoringResult result = score(List.of(assassin, fighter, player(22, 100, true, 18000, 12, 3, 6, 11000, 150, 14000, 10, 1000, 500, 20)));

        assertThat(result.getPlayerScores().get(0).getTotalScore())
                .isEqualTo(result.getPlayerScores().get(1).getTotalScore());
    }

    // ============ 7. 评分明细 ============

    /**
     * 用例：评分明细包含原始值 raw 与归一化得分 score
     */
    @Test
    @DisplayName("评分明细包含 raw 与 score")
    void detailContainsRawAndScore() {
        List<MvpScoringInput> inputs = List.of(
                player(22, 100, true, 30000, 10, 2, 5, 12000, 200, 10000, 10, 0, 0, 0),
                player(51, 100, true, 15000, 8, 3, 4, 10000, 180, 9000, 8, 0, 0, 0));

        MvpScoringResult result = score(inputs);

        // 伤害维度：raw = 30000，score = 100（队内最高）
        var damage = result.getPlayerScores().get(0).getDimensionScores().get("damage");
        assertThat(damage.getRaw()).isEqualTo(30000.0);
        assertThat(damage.getScore()).isEqualTo(100.0);
        // 经济维度同样有 raw + score
        var gold = result.getPlayerScores().get(0).getDimensionScores().get("gold");
        assertThat(gold.getRaw()).isEqualTo(12000.0);
        assertThat(gold.getScore()).isGreaterThanOrEqualTo(0.0);
    }

    /**
     * 用例：总分在 0-100 范围内且为加权平均
     */
    @Test
    @DisplayName("总分在 0-100 内")
    void totalScoreWithinRange() {
        List<MvpScoringInput> inputs = List.of(
                player(22, 100, true, 30000, 15, 1, 8, 15000, 250, 15000, 20, 0, 0, 0),
                player(51, 100, true, 10000, 3, 6, 2, 8000, 150, 12000, 8, 0, 0, 0),
                player(96, 200, false, 25000, 12, 4, 6, 13000, 220, 12000, 10, 0, 0, 0),
                player(81, 200, false, 15000, 5, 6, 3, 8000, 150, 9000, 8, 0, 0, 0));

        MvpScoringResult result = score(inputs);

        result.getPlayerScores().forEach(ps -> {
            assertThat(ps.getTotalScore()).isBetween(0.0, 100.0);
        });
    }

    /**
     * 用例：分母为 0 的维度（如全员 0 视野）不污染总分
     */
    @Test
    @DisplayName("全员零值维度不污染总分")
    void zeroValuesDimensionDoesNotAffectTotal() {
        List<MvpScoringInput> inputs = List.of(
                player(22, 100, true, 30000, 15, 1, 8, 15000, 250, 15000, 0, 0, 0, 0),
                player(51, 100, true, 20000, 8, 3, 4, 10000, 180, 12000, 0, 0, 0, 0));

        MvpScoringResult result = score(inputs);

        // 维度分或总分都应存在且合理
        result.getPlayerScores().forEach(ps -> {
            assertThat(ps.getTotalScore()).isBetween(0.0, 100.0);
        });
    }
}