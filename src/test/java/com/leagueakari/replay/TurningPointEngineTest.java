package com.leagueakari.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.ReplayProperties;
import com.leagueakari.dto.replay.ReplayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TurningPointEngine 单元测试（纯函数黄金样本，工单 #35）：
 * 构造含已知事件的 frames → 断言经济差序列、击杀事件、五类转折点的提取结果。
 * 断言值全部由人工从夹具复算（非实现回放），规则变更时本测试即口径守卫。
 * <p>夹具口径：4 名参与者（局内序号 1-4），1/2 号属 100 队（我方视角），
 * 3/4 号属 200 队；帧序 f0~f3，事件全部预置可复算。</p>
 */
class TurningPointEngineTest {

    /** 被测引擎（默认规则参数：团灭窗 12s、极值门槛 1500 金） */
    private TurningPointEngine engine;

    /** 局内序号 → 参与者信息（名字/英雄/队伍） */
    private Map<Integer, TurningPointEngine.ParticipantInfo> slotInfo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        engine = new TurningPointEngine(new ReplayProperties());
        slotInfo = new HashMap<>();
        slotInfo.put(1, participant("玩家一", "阿狸", 100));
        slotInfo.put(2, participant("玩家二", "盲僧", 100));
        slotInfo.put(3, participant("玩家三", "锐雯", 200));
        slotInfo.put(4, participant("玩家四", "泰达米尔", 200));
    }

    /** 构造参与者信息（@Value 不可变对象）；championId 用序号占位（引擎仅透传不使用） */
    private TurningPointEngine.ParticipantInfo participant(String name, String champion, int teamId) {
        return new TurningPointEngine.ParticipantInfo(name, champion, name.hashCode(), teamId);
    }

    /** 构造一帧：时间戳 + 参与者金币（序号→totalGold）+ 事件列表 */
    private Map<String, Object> frame(long timestampMs, Map<Integer, Integer> goldBySlot,
            List<Map<String, Object>> events) {
        Map<String, Object> participantFrames = new LinkedHashMap<>();
        goldBySlot.forEach((slot, gold) -> {
            Map<String, Object> pf = new LinkedHashMap<>();
            pf.put("participantId", slot);
            pf.put("totalGold", gold);
            participantFrames.put(String.valueOf(slot), pf);
        });
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("timestamp", timestampMs);
        f.put("participantFrames", participantFrames);
        f.put("events", events);
        return f;
    }

    /** 构造英雄击杀事件 */
    private Map<String, Object> kill(long ts, int killerId, int victimId) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "CHAMPION_KILL");
        e.put("timestamp", ts);
        e.put("killerId", killerId);
        e.put("victimId", victimId);
        return e;
    }

    /** 构造精英野怪击杀事件（大龙的常见形态：ELITE_MONSTER_KILL + monsterType） */
    private Map<String, Object> eliteMonster(long ts, int killerId, String monsterType) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "ELITE_MONSTER_KILL");
        e.put("timestamp", ts);
        e.put("killerId", killerId);
        e.put("monsterType", monsterType);
        return e;
    }

    /** 黄金样本：四帧三事件（一血/团灭/大龙）+ 两次经济反超 + 极值 3000 金 */
    private List<Map<String, Object>> goldenFrames() {
        return List.of(
                // f0（60s）：我方 10000 vs 敌方 8000 → +2000；敌方拿一血
                frame(60_000L, Map.of(1, 6000, 2, 4000, 3, 4500, 4, 3500),
                        List.of(kill(65_000L, 3, 1))),
                // f1（120s）：12000 vs 11000 → +1000；敌方两人 5 秒内先后阵亡（2 人队伍 = 团灭）
                frame(120_000L, Map.of(1, 7000, 2, 5000, 3, 6000, 4, 5000),
                        List.of(kill(125_000L, 1, 3), kill(130_000L, 2, 4))),
                // f2（180s）：14000 vs 17000 → -3000（正转负 + 全场极值）；敌方收大龙
                frame(180_000L, Map.of(1, 8000, 2, 6000, 3, 9000, 4, 8000),
                        List.of(eliteMonster(185_000L, 3, "BARON"))),
                // f3（240s）：22000 vs 20000 → +2000（负转正）
                frame(240_000L, Map.of(1, 12000, 2, 10000, 3, 11000, 4, 9000),
                        List.of()));
    }

    /** 运行引擎（List→JsonNode 与 ReplayService 的转换口径一致） */
    private ReplayResponse compute(List<Map<String, Object>> frames, Integer perspectiveTeamId) {
        return engine.compute(objectMapper.valueToTree(frames), slotInfo, perspectiveTeamId);
    }

    /** 用例：经济差序列逐帧计算，正 = 我方（100 队）领先 */
    @Test
    void compute_goldDiffSeriesPerFrame() {
        ReplayResponse result = compute(goldenFrames(), 100);

        assertThat(result.getGoldDiffSeries()).hasSize(4);
        assertThat(result.getGoldDiffSeries()).extracting(ReplayResponse.GoldDiffPoint::getTimestampMs)
                .containsExactly(60_000L, 120_000L, 180_000L, 240_000L);
        assertThat(result.getGoldDiffSeries()).extracting(ReplayResponse.GoldDiffPoint::getGoldDiff)
                .containsExactly(2000D, 1000D, -3000D, 2000D);
    }

    /** 用例：视角换到 200 队时经济差取反（符号相对视角） */
    @Test
    void compute_goldDiffFromEnemyPerspective() {
        ReplayResponse result = compute(goldenFrames(), 200);

        assertThat(result.getGoldDiffSeries()).extracting(ReplayResponse.GoldDiffPoint::getGoldDiff)
                .containsExactly(-2000D, -1000D, 3000D, -2000D);
    }

    /** 用例：视角为空时回退到 100 队（防御，正常路径 service 已解析视角） */
    @Test
    void compute_nullPerspectiveFallsBackTo100() {
        ReplayResponse result = compute(goldenFrames(), null);

        assertThat(result.getPerspectiveTeamId()).isEqualTo(100);
        assertThat(result.getGoldDiffSeries().get(0).getGoldDiff()).isEqualTo(2000D);
    }

    /** 用例：击杀事件收集——敌我标记与击杀者/被击杀者姓名英雄 */
    @Test
    void compute_killEventsWithNamesAndSides() {
        ReplayResponse result = compute(goldenFrames(), 100);

        assertThat(result.getKillEvents()).hasSize(3);
        ReplayResponse.KillEvent first = result.getKillEvents().get(0);
        assertThat(first.getTimestampMs()).isEqualTo(65_000L);
        assertThat(first.getKillerName()).isEqualTo("玩家三");
        assertThat(first.getKillerChampion()).isEqualTo("锐雯");
        assertThat(first.getVictimName()).isEqualTo("玩家一");
        assertThat(first.getVictimChampion()).isEqualTo("阿狸");
        // 击杀者 3 号属 200 队 → 非我方击杀
        assertThat(first.isKillerIsPerspective()).isFalse();
        assertThat(result.getKillEvents().get(1).isKillerIsPerspective()).isTrue();
    }

    /** 用例：五类转折点全部提取——一血/团灭/大龙/极值/反超（按时间排序） */
    @Test
    void compute_extractsAllTurningPointTypes() {
        ReplayResponse result = compute(goldenFrames(), 100);

        List<ReplayResponse.TurningPoint> points = result.getTurningPoints();
        // 一血(65s) / 团灭(130s) / 极值(180s) / 反超正转负(180s) / 大龙(185s) / 反超负转正(240s)
        // 注：180s 处极值与反超同刻，稳定排序按提取顺序（极值先加入）
        assertThat(points).extracting(ReplayResponse.TurningPoint::getType).containsExactly(
                TurningPointEngine.TYPE_FIRST_BLOOD,
                TurningPointEngine.TYPE_TEAM_WIPE,
                TurningPointEngine.TYPE_GOLD_DIFF_EXTREME,
                TurningPointEngine.TYPE_GOLD_LEAD_CHANGE,
                TurningPointEngine.TYPE_BARON,
                TurningPointEngine.TYPE_GOLD_LEAD_CHANGE);
        assertThat(points).extracting(ReplayResponse.TurningPoint::getTimestampMs)
                .containsExactly(65_000L, 130_000L, 180_000L, 180_000L, 185_000L, 240_000L);

        // 一血：敌方视角描述 + 涉及击杀者与被击杀者
        ReplayResponse.TurningPoint firstBlood = points.get(0);
        assertThat(firstBlood.getGoldDiff()).isEqualTo(2000D);
        assertThat(firstBlood.getDetail()).contains("敌方").contains("锐雯").contains("玩家一");
        assertThat(firstBlood.getInvolved()).hasSize(2);

        // 团灭：敌队两人 5 秒内先后阵亡（2 人队伍全员阵亡即团灭），涉及被团灭者
        ReplayResponse.TurningPoint wipe = points.get(1);
        assertThat(wipe.getDetail()).contains("敌方").contains("团灭");
        assertThat(wipe.getInvolved()).extracting(ReplayResponse.InvolvedPlayer::getName)
                .containsExactlyInAnyOrder("玩家三", "玩家四");

        // 反超（正转负）：描述含"被反超"；极值：3000 金为全场最大
        assertThat(points.get(3).getDetail()).contains("被反超");
        assertThat(points.get(2).getGoldDiff()).isEqualTo(-3000D);
        assertThat(points.get(2).getDetail()).contains("落后");

        // 大龙：敌方收下
        assertThat(points.get(4).getDetail()).contains("敌方").contains("大龙");
    }

    /** 用例：团灭时间窗外不判定（两次阵亡间隔 20s > 12s 窗口） */
    @Test
    void compute_noWipeOutsideWindow() {
        List<Map<String, Object>> frames = List.of(
                frame(60_000L, Map.of(1, 6000, 2, 4000, 3, 4500, 4, 3500),
                        List.of(kill(65_000L, 1, 3), kill(85_000L, 2, 4))));

        ReplayResponse result = compute(frames, 100);

        assertThat(result.getTurningPoints())
                .noneMatch(p -> p.getType().equals(TurningPointEngine.TYPE_TEAM_WIPE));
        // 一血仍然提取
        assertThat(result.getTurningPoints())
                .anyMatch(p -> p.getType().equals(TurningPointEngine.TYPE_FIRST_BLOOD));
    }

    /** 用例：跨帧团灭不漏判——窗口外有一次我方阵亡占位，滑窗触发时只应淘汰过期者
     *（回归守卫：旧实现整清累计，把仍在窗内的敌方阵亡一并误删导致团灭漏判） */
    @Test
    void compute_wipeAcrossFramesNotMissed() {
        // 时间轴：我方 1 号死于 60s（占位）；敌方 3/4/5 号死于 66s/67s/74s——
        // 处理 74s 时 74-60=14s > 12s 窗口触发滑窗，但 66s/67s/74s 三者互距 ≤ 12s，
        // 敌方三人仍构成团灭（旧实现 clear() 会把 66s/67s 误逐出而漏判）
        slotInfo.put(5, participant("玩家五", "锤石", 200));
        List<Map<String, Object>> frames = List.of(
                frame(60_000L, Map.of(1, 6000, 2, 4000, 3, 4500, 4, 3500, 5, 3000),
                        List.of(kill(60_000L, 3, 1))),
                frame(120_000L, Map.of(1, 7000, 2, 5000, 3, 6000, 4, 5000, 5, 4000),
                        List.of(kill(66_000L, 1, 3), kill(67_000L, 2, 4), kill(74_000L, 1, 5))));

        ReplayResponse result = compute(frames, 100);

        assertThat(result.getTurningPoints())
                .anyMatch(p -> p.getType().equals(TurningPointEngine.TYPE_TEAM_WIPE)
                        && p.getDetail().contains("敌方"));
    }

    /** 用例：经济差极值低于门槛不提取（两帧差 200 / 400，均 < 1500 门槛） */
    @Test
    void compute_noExtremeBelowThreshold() {
        List<Map<String, Object>> frames = List.of(
                frame(60_000L, Map.of(1, 5000, 2, 4000, 3, 4400, 4, 3600), List.of()),
                frame(120_000L, Map.of(1, 5400, 2, 4200, 3, 4600, 4, 3800), List.of()));

        ReplayResponse result = compute(frames, 100);

        assertThat(result.getTurningPoints())
                .noneMatch(p -> p.getType().equals(TurningPointEngine.TYPE_GOLD_DIFF_EXTREME));
    }

    /** 用例：经济差全程同号时无反超转折点 */
    @Test
    void compute_noLeadChangeWhenSameSign() {
        List<Map<String, Object>> frames = List.of(
                frame(60_000L, Map.of(1, 6000, 2, 4000, 3, 4500, 4, 3500), List.of()),
                frame(120_000L, Map.of(1, 8000, 2, 5000, 3, 5000, 4, 4000), List.of()));

        ReplayResponse result = compute(frames, 100);

        assertThat(result.getTurningPoints())
                .noneMatch(p -> p.getType().equals(TurningPointEngine.TYPE_GOLD_LEAD_CHANGE));
    }

    /** 用例：大龙的另一种事件形态（type=BARON_KILL 直写）同样提取 */
    @Test
    void compute_baronKillEventTypeSupported() {
        Map<String, Object> baronKill = new LinkedHashMap<>();
        baronKill.put("type", "BARON_KILL");
        baronKill.put("timestamp", 300_000L);
        baronKill.put("killerId", 1);
        List<Map<String, Object>> frames = List.of(
                frame(300_000L, Map.of(1, 9000, 2, 6000, 3, 8000, 4, 7000), List.of(baronKill)));

        ReplayResponse result = compute(frames, 100);

        assertThat(result.getTurningPoints())
                .anyMatch(p -> p.getType().equals(TurningPointEngine.TYPE_BARON)
                        && p.getDetail().contains("我方"));
    }

    /** 用例：空帧/无事件对局——空集合优雅返回不抛异常 */
    @Test
    void compute_emptyFramesGracefully() {
        ReplayResponse result = compute(List.of(), 100);

        assertThat(result.getGoldDiffSeries()).isEmpty();
        assertThat(result.getKillEvents()).isEmpty();
        assertThat(result.getTurningPoints()).isEmpty();
    }

    /** 用例：非大龙精英野怪（小龙）不产生大龙转折点 */
    @Test
    void compute_dragonNotCountedAsBaron() {
        List<Map<String, Object>> frames = List.of(
                frame(60_000L, Map.of(1, 6000, 2, 4000, 3, 4500, 4, 3500),
                        List.of(eliteMonster(65_000L, 1, "DRAGON"))));

        ReplayResponse result = compute(frames, 100);

        assertThat(result.getTurningPoints())
                .noneMatch(p -> p.getType().equals(TurningPointEngine.TYPE_BARON));
    }
}
