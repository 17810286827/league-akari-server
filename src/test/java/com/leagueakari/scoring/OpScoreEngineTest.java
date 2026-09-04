package com.leagueakari.scoring;

import com.leagueakari.config.ScoringConfig;
import com.leagueakari.entity.ChampionClass;
import com.leagueakari.mapper.ChampionClassMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpScore 评分引擎单元测试（表加载在引擎侧，测试注入 mock 表源；不依赖 Spring 上下文）
 */
class OpScoreEngineTest {

    private ScoringConfig config;
    private OpScoreEngine engine;
    private ChampionClassMapper championClassMapper;
    private BaselineService baselineService;

    /** 用例内动态构造的基线（championId → 值对象）；未设置时为空表 */
    private Map<Integer, ChampionBaseline> baselineFixture;

    @BeforeEach
    void setUp() {
        config = new ScoringConfig();
        // 权重表初稿（与 spec 一致，每行和为 1.0）
        config.setWeights(defaultWeights());
        config.setMultiKillBonus(Map.of("double", 0.2, "triple", 0.5, "quadra", 1.0, "penta", 2.0));
        config.setBaselineThresholdMin(10);
        config.setBaselineThresholdMax(30);
        config.setBaselineMixMax(0.5);
        // mock 表源：职业表默认返回 UNKNOWN 映射，基线默认空（各用例可覆盖 stub）
        championClassMapper = mock(ChampionClassMapper.class);
        baselineService = mock(BaselineService.class);
        baselineFixture = new HashMap<>();
        lenient().when(championClassMapper.selectList(any())).thenAnswer(inv ->
                classFixture.entrySet().stream().map(e -> {
                    ChampionClass c = new ChampionClass();
                    c.setChampionId(e.getKey());
                    c.setClassName(e.getValue());
                    return c;
                }).toList());
        lenient().when(baselineService.getBaselineMap()).thenAnswer(inv -> baselineFixture);
        engine = new OpScoreEngine(config, championClassMapper, baselineService);
    }

    /** 用例内动态构造的职业映射（championId → 职业名） */
    private final Map<Integer, String> classFixture = new HashMap<>();

    /** 旧 classMap(...) 兼容：写入职业映射 fixture */
    private Map<Integer, String> classMap(int... champions) {
        for (int c : champions) {
            classFixture.put(c, "UNKNOWN");
        }
        return classFixture;
    }

    static Map<String, Map<String, Double>> defaultWeights() {
        Map<String, Map<String, Double>> w = new HashMap<>();
        w.put("ADC", Map.of("kda", 0.35, "damage", 0.30, "gold", 0.10, "tank", 0.05, "vision", 0.0, "healShield", 0.0, "cc", 0.05, "turret", 0.15));
        w.put("MAGE", Map.of("kda", 0.30, "damage", 0.35, "gold", 0.10, "tank", 0.05, "vision", 0.0, "healShield", 0.0, "cc", 0.10, "turret", 0.10));
        w.put("TANK", Map.of("kda", 0.20, "damage", 0.05, "gold", 0.10, "tank", 0.40, "vision", 0.0, "healShield", 0.05, "cc", 0.15, "turret", 0.05));
        w.put("ASSASSIN", Map.of("kda", 0.40, "damage", 0.25, "gold", 0.10, "tank", 0.05, "vision", 0.0, "healShield", 0.0, "cc", 0.10, "turret", 0.10));
        w.put("FIGHTER", Map.of("kda", 0.25, "damage", 0.20, "gold", 0.10, "tank", 0.25, "vision", 0.0, "healShield", 0.05, "cc", 0.10, "turret", 0.05));
        w.put("SUPPORT", Map.of("kda", 0.20, "damage", 0.0, "gold", 0.05, "tank", 0.05, "vision", 0.0, "healShield", 0.40, "cc", 0.25, "turret", 0.05));
        w.put("UNKNOWN", Map.of("kda", 0.30, "damage", 0.25, "gold", 0.10, "tank", 0.15, "vision", 0.0, "healShield", 0.05, "cc", 0.10, "turret", 0.05));
        return w;
    }

    /** 构造一名参与者：默认全零，可按维度覆盖 */
    private MvpScoringInput player(long id, int team, boolean win, int championId, Map<String, Object> overrides) {
        Map<String, Object> d = new HashMap<>();
        d.put("kills", 0);
        d.put("deaths", 0);
        d.put("assists", 0);
        d.put("goldEarned", 0);
        d.put("totalDamageDealtToChampions", 0.0);
        d.put("totalDamageTaken", 0.0);
        d.put("visionScore", 0.0);
        d.put("totalHeal", 0.0);
        d.put("totalDamageShieldedOnTeammates", 0.0);
        d.put("timeCCingOthers", 0.0);
        d.put("damageDealtToTurrets", 0.0);
        d.put("doubleKills", 0);
        d.put("tripleKills", 0);
        d.put("quadraKills", 0);
        d.put("pentaKills", 0);
        d.put("gameDurationSeconds", 1200);
        d.put("aramMode", false);
        d.putAll(overrides);
        return MvpScoringInput.builder()
                .participantId(id)
                .teamId(team)
                .win(win)
                .championId(championId)
                .kills((Integer) d.get("kills"))
                .deaths((Integer) d.get("deaths"))
                .assists((Integer) d.get("assists"))
                .goldEarned((Integer) d.get("goldEarned"))
                .totalDamageDealtToChampions((Double) d.get("totalDamageDealtToChampions"))
                .totalDamageTaken((Double) d.get("totalDamageTaken"))
                .visionScore((Double) d.get("visionScore"))
                .totalHeal((Double) d.get("totalHeal"))
                .totalDamageShieldedOnTeammates((Double) d.get("totalDamageShieldedOnTeammates"))
                .timeCCingOthers((Double) d.get("timeCCingOthers"))
                .damageDealtToTurrets((Double) d.get("damageDealtToTurrets"))
                .doubleKills((Integer) d.get("doubleKills"))
                .tripleKills((Integer) d.get("tripleKills"))
                .quadraKills((Integer) d.get("quadraKills"))
                .pentaKills((Integer) d.get("pentaKills"))
                .gameDurationSeconds((Integer) d.get("gameDurationSeconds"))
                .aramMode((Boolean) d.get("aramMode"))
                .build();
    }

    /** 构造「辅助职业有视野权重」的权重表：生产 yml 中辅助 vision=0，本组用例需要非零权重才能验证归零逻辑 */
    static Map<String, Map<String, Double>> supportVisionWeights() {
        Map<String, Map<String, Double>> w = new HashMap<>();
        w.put("SUPPORT", Map.of("kda", 0.30, "damage", 0.0, "gold", 0.10, "tank", 0.05, "vision", 0.25,
                "healShield", 0.10, "cc", 0.15, "turret", 0.05));
        w.put("UNKNOWN", defaultWeights().get("UNKNOWN"));
        return w;
    }

    // ===== 维度与归一化 =====

    @Test
    @DisplayName("伤害按分钟归一化：两倍时长同总量单位时间输出减半")
    void damagePerMinuteNormalized() {
        // 1200s=20min，A 总伤 12000 → 600/min；B 总伤 3000 → 150/min
        MvpScoringInput a = player(1, 100, true, 1, Map.of(
                "totalDamageDealtToChampions", 12000.0, "gameDurationSeconds", 1200));
        MvpScoringInput b = player(2, 100, true, 1, Map.of(
                "totalDamageDealtToChampions", 3000.0, "gameDurationSeconds", 1200));
        classMap(1, 1);
        var result = engine.score(List.of(a, b));
        var pa = byId(result, 1L);
        var pb = byId(result, 2L);
        assertThat(pa.getDimensionScores().get("damage").getPerMinute()).isEqualTo(600.0);
        assertThat(pb.getDimensionScores().get("damage").getPerMinute()).isEqualTo(150.0);
    }

    @Test
    @DisplayName("队内位次分：5 人唯一值降序得到 100/75/50/25/0")
    void teamRankFiveUnique() {
        // 5 人伤害 20000/15000/10000/5000/0，UNKNOWN 职业仅 kda+damage 权重，但其他维度权重为 0 不参与
        Map<String, Object> base = Map.of("gameDurationSeconds", 1200);
        List<MvpScoringInput> team = List.of(
                player(1, 100, true, 1, Map.of("totalDamageDealtToChampions", 20000.0)),
                player(2, 100, true, 1, Map.of("totalDamageDealtToChampions", 15000.0)),
                player(3, 100, true, 1, Map.of("totalDamageDealtToChampions", 10000.0)),
                player(4, 100, true, 1, Map.of("totalDamageDealtToChampions", 5000.0)),
                player(5, 100, true, 1, Map.of("totalDamageDealtToChampions", 0.0)));
        var result = engine.score(team);
        assertThat(byId(result, 1L).getDimensionScores().get("damage").getTeamRank()).isEqualTo(100.0);
        assertThat(byId(result, 2L).getDimensionScores().get("damage").getTeamRank()).isEqualTo(75.0);
        assertThat(byId(result, 3L).getDimensionScores().get("damage").getTeamRank()).isEqualTo(50.0);
        assertThat(byId(result, 4L).getDimensionScores().get("damage").getTeamRank()).isEqualTo(25.0);
        assertThat(byId(result, 5L).getDimensionScores().get("damage").getTeamRank()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("全员同值：位次分无区分度，一律 50")
    void teamRankAllEqual() {
        List<MvpScoringInput> team = List.of(
                player(1, 100, true, 1, Map.of("totalDamageDealtToChampions", 1000.0)),
                player(2, 100, true, 1, Map.of("totalDamageDealtToChampions", 1000.0)));
        var result = engine.score(team);
        assertThat(byId(result, 1L).getDimensionScores().get("damage").getTeamRank()).isEqualTo(50.0);
        assertThat(byId(result, 2L).getDimensionScores().get("damage").getTeamRank()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("KDA：死亡为 0 时按 1 计，避免除零")
    void kdaNoDivisionByZero() {
        // 10/0/5，死亡为 0 → KDA = 15
        MvpScoringInput a = player(1, 100, true, 1, Map.of("kills", 10, "deaths", 0, "assists", 5));
        MvpScoringInput b = player(2, 100, true, 1, Map.of());
        classMap(1, 1);
        var result = engine.score(List.of(a, b));
        assertThat(byId(result, 1L).getDimensionScores().get("kda").getPerMinute()).isEqualTo(15.0);
    }

    // ===== 基线混合 =====

    @Test
    @DisplayName("无基线样本：混合比为 0，纯局内比较")
    void noBaselinePureTeamRank() {
        MvpScoringInput a = player(1, 100, true, 1, Map.of("totalDamageDealtToChampions", 20000.0));
        MvpScoringInput b = player(2, 100, true, 1, Map.of());
        classMap(1, 1);
        var result = engine.score(List.of(a, b));
        assertThat(byId(result, 1L).getDimensionScores().get("damage").getMix()).isEqualTo(0.0);
        // 纯局内：A 是唯一最高 → 100
        assertThat(byId(result, 1L).getDimensionScores().get("damage").getFinalScore()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("无基线样本的维度：基线分为 0，混合比为 0")
    void noBaselineDimBaselineScoreZero() {
        MvpScoringInput a = player(1, 100, true, 1, Map.of("totalDamageDealtToChampions", 12000.0));
        MvpScoringInput b = player(2, 100, true, 1, Map.of());
        classMap(1, 1);
        var result = engine.score(List.of(a, b));
        assertThat(byId(result, 1L).getDimensionScores().get("damage").getBaselineScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("达到阈值：混合比 0.5，基线分与位次分各占一半")
    void baselineMixHalfAtThreshold() {
        // 英雄 1 的基线：damage 1000/min（玩家 900/min 是 90%）
        baselineFixture.put(1, new ChampionBaseline(1, Map.of("damage", 1000.0, "kda", 5.0, "gold", 300.0,
                "tank", 500.0, "healShield", 0.0, "cc", 5.0, "turret", 50.0), 30));
        // 玩家：900/min vs 基线 1000/min → 基线分 90
        MvpScoringInput a = player(1, 100, true, 1, Map.of("totalDamageDealtToChampions", 18000.0));
        MvpScoringInput b = player(2, 100, true, 1, Map.of("totalDamageDealtToChampions", 18000.0));
        // 全员同值 → 位次分 50
        classMap(1, 1);
        var result = engine.score(List.of(a, b));
        var pa = byId(result, 1L);
        assertThat(pa.getDimensionScores().get("damage").getMix()).isEqualTo(0.5);
        assertThat(pa.getDimensionScores().get("damage").getBaselineScore()).isEqualTo(90.0);
        assertThat(pa.getDimensionScores().get("damage").getFinalScore()).isEqualTo(70.0); // 50*0.5+90*0.5
    }

    @Test
    @DisplayName("基线过渡期：15 局时混合比线性插值到 0.125")
    void baselineMixTransition() {
        ChampionBaseline baseline = new ChampionBaseline(1, Map.of("damage", 1000.0), 15);
        // 有 dim 数据才进入插值
        double mix = engine.mixRatio(baseline, "damage");
        // (15-10)/(30-10)*0.5 = 0.125
        assertThat(mix).isEqualTo(0.125);
    }

    @Test
    @DisplayName("基线截断：偏离基线 3 倍时基线分截断到 100")
    void baselineScoreCappedAt100() {
        baselineFixture.put(1, new ChampionBaseline(1, Map.of("damage", 1000.0), 30));
        // 玩家 3000/min 是基线 3 倍 → 300，截断 100
        MvpScoringInput a = player(1, 100, true, 1, Map.of("totalDamageDealtToChampions", 60000.0));
        MvpScoringInput b = player(2, 100, true, 1, Map.of("totalDamageDealtToChampions", 60000.0));
        classMap(1, 1);
        var result = engine.score(List.of(a, b));
        assertThat(byId(result, 1L).getDimensionScores().get("damage").getBaselineScore()).isEqualTo(100.0);
    }

    // ===== 多杀加分 =====

    @Test
    @DisplayName("多杀加分：4 双 1 三 1 四 → 0.2*4+0.5+1.0=2.3")
    void multiKillBonus() {
        MvpScoringInput a = player(1, 100, true, 1, Map.of(
                "doubleKills", 4, "tripleKills", 1, "quadraKills", 1, "pentaKills", 0));
        MvpScoringInput b = player(2, 100, true, 1, Map.of());
        classMap(1, 1);
        var result = engine.score(List.of(a, b));
        assertThat(byId(result, 1L).getMultiKillBonus()).isEqualTo(2.3);
    }

    // ===== OP Score / grade =====

    @Test
    @DisplayName("总分 100 为满分：opScore=10，grade=完美")
    void maxTotal() {
        // UNKNOWN 职业：kda 0.3 + damage 0.25 ... 人为构造全队同值纯局内，位次分 50
        List<MvpScoringInput> team = List.of(
                player(1, 100, true, 1, Map.of()),
                player(2, 100, true, 1, Map.of()));
        var result = engine.score(team);
        // 全员同值 → 各位次分 50 → 加权总分 50 → opScore 5.0
        assertThat(byId(result, 1L).getOpScore()).isEqualTo(5.0);
        assertThat(byId(result, 1L).getGrade()).isEqualTo("一般");
    }

    @Test
    @DisplayName("多杀加分后 opScore 截断到 10")
    void multiKillCapAt10() {
        MvpScoringInput a = player(1, 100, true, 1, Map.of("pentaKills", 5, "doubleKills", 9));
        MvpScoringInput b = player(2, 100, true, 1, Map.of());
        classMap(1, 1);
        var result = engine.score(List.of(a, b));
        assertThat(byId(result, 1L).getOpScore()).isBetween(9.0, 10.0);
        assertThat(byId(result, 1L).getOpScore()).isLessThanOrEqualTo(10.0);
    }

    @Test
    @DisplayName("获胜方最高分 = MVP，败方最高分 = ACE")
    void mvpAndAce() {
        MvpScoringInput w1 = player(1, 100, true, 1, Map.of("totalDamageDealtToChampions", 20000.0));
        MvpScoringInput w2 = player(2, 100, true, 1, Map.of("totalDamageDealtToChampions", 10000.0));
        MvpScoringInput l1 = player(3, 200, false, 1, Map.of("totalDamageDealtToChampions", 18000.0));
        MvpScoringInput l2 = player(4, 200, false, 1, Map.of("totalDamageDealtToChampions", 5000.0));
        classMap(1, 1, 1, 1);
        var result = engine.score(List.of(w1, w2, l1, l2));
        assertThat(result.getMvp().getParticipantId()).isEqualTo(1L);
        assertThat(result.getAce().getParticipantId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("评选按 op_score 取：多杀加分使 op_score 反超时，MVP 归 op_score 最高者")
    void mvpByOpScoreWhenBonusFlipsRanking() {
        // 胜方：w1 加权总分更高（伤害位次第一）但无多杀；w2 总分略低但两个五杀（+4.0）→ op_score 反超。
        // UNKNOWN 职业 2 人队：非伤害维度全员同值位次分 50；damage 位次 100/75。
        // w1 总分 = 0.25*100 + 0.75*50 = 62.5 → op_score 6.3；w2 总分 = 56.3 → 5.6 + 4.0 = 9.6
        MvpScoringInput w1 = player(1, 100, true, 1, Map.of("totalDamageDealtToChampions", 20000.0));
        MvpScoringInput w2 = player(2, 100, true, 1, Map.of("totalDamageDealtToChampions", 19000.0, "pentaKills", 2));
        MvpScoringInput l1 = player(3, 200, false, 1, Map.of("totalDamageDealtToChampions", 15000.0));
        MvpScoringInput l2 = player(4, 200, false, 1, Map.of());
        classMap(1, 1, 1, 1);
        var result = engine.score(List.of(w1, w2, l1, l2));
        // MVP 按 op_score（含多杀加分，与界面展示的评分一致）取：w2 的 9.6 > w1 的 6.3
        assertThat(result.getMvp().getParticipantId()).isEqualTo(2L);
        // SVP 仍为败方 op_score 最高者
        assertThat(result.getAce().getParticipantId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("少于 2 人抛 IllegalArgumentException")
    void fewerThan2Throw() {
        assertThatThrownBy(() -> engine.score(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("grade 映射：各档位边界")
    void gradeBoundaries() {
        // 通过反射或直接调用 package-private 方法验证
        assertThat(engine.grade(9.5)).isEqualTo("完美");
        assertThat(engine.grade(9.0)).isEqualTo("完美");
        assertThat(engine.grade(8.9)).isEqualTo("卓越");
        assertThat(engine.grade(7.5)).isEqualTo("优秀");
        assertThat(engine.grade(6.1)).isEqualTo("良好");
        assertThat(engine.grade(5.0)).isEqualTo("一般");
        assertThat(engine.grade(4.9)).isEqualTo("偏低");
        assertThat(engine.grade(3.2)).isEqualTo("较差");
        assertThat(engine.grade(2.9)).isEqualTo("糟糕");
    }

    // ===== 大乱斗修正 =====

    @Test
    @DisplayName("大乱斗修正：大乱斗对局下辅助的视野维度不计入总分（权重归零）")
    void aramSupportVisionExcluded() {
        // 辅助视野权重非零的自定义权重表（生产 yml 辅助 vision=0，规则处于休眠——见权重表注释）
        config.setWeights(supportVisionWeights());
        // 两名辅助（英雄 16），视野得分悬殊：A=100，B=0
        MvpScoringInput a = player(1, 100, true, 16, Map.of("aramMode", true, "visionScore", 100.0));
        MvpScoringInput b = player(2, 100, true, 16, Map.of("aramMode", true));
        classFixture.put(16, "SUPPORT");
        var result = engine.score(List.of(a, b));
        // 视野维度权重归零 → 结果中不应出现 vision 维度，且总分差异只来自 kda/gold 等（全零 → 两人同分）
        assertThat(byId(result, 1L).getDimensionScores()).doesNotContainKey("vision");
        assertThat(byId(result, 1L).getTotalScore()).isEqualTo(byId(result, 2L).getTotalScore());
    }

    @Test
    @DisplayName("大乱斗修正不误伤：普通对局辅助的视野维度正常参与评分")
    void classicSupportVisionStillCounted() {
        config.setWeights(supportVisionWeights());
        // 同样两名辅助、同样悬殊视野，但 aramMode=false：视野维度必须出现且 A 得满位次分
        MvpScoringInput a = player(1, 100, true, 16, Map.of("visionScore", 100.0));
        MvpScoringInput b = player(2, 100, true, 16, Map.of());
        classFixture.put(16, "SUPPORT");
        var result = engine.score(List.of(a, b));
        assertThat(byId(result, 1L).getDimensionScores()).containsKey("vision");
        assertThat(byId(result, 1L).getDimensionScores().get("vision").getTeamRank()).isEqualTo(100.0);
        // 视野权重 0.25：A 的总分应高于 B（视野位次 100 vs 0 的加权贡献）
        assertThat(byId(result, 1L).getTotalScore()).isGreaterThan(byId(result, 2L).getTotalScore());
    }

    @Test
    @DisplayName("大乱斗修正不误伤其他职业：大乱斗下非辅助职业的视野维度不受影响")
    void aramNonSupportVisionUnaffected() {
        // 自定义 UNKNOWN 权重表里给 vision 非零权重，验证规则只对 SUPPORT 生效
        Map<String, Map<String, Double>> w = new HashMap<>();
        w.put("UNKNOWN", Map.of("kda", 0.30, "damage", 0.25, "gold", 0.10, "tank", 0.05, "vision", 0.10,
                "healShield", 0.05, "cc", 0.10, "turret", 0.05));
        config.setWeights(w);
        MvpScoringInput a = player(1, 100, true, 1, Map.of("aramMode", true, "visionScore", 100.0));
        MvpScoringInput b = player(2, 100, true, 1, Map.of("aramMode", true));
        classMap(1, 1);
        var result = engine.score(List.of(a, b));
        // 非 SUPPORT 职业：大乱斗下视野维度照常参与
        assertThat(byId(result, 1L).getDimensionScores()).containsKey("vision");
        assertThat(byId(result, 1L).getDimensionScores().get("vision").getTeamRank()).isEqualTo(100.0);
    }

    private OpScoreResult.PlayerScore byId(OpScoreResult result, long id) {
        return result.getPlayerScores().stream()
                .filter(p -> p.getParticipantId().equals(id))
                .findFirst().orElseThrow(() -> new AssertionError("participant " + id + " not found"));
    }
}