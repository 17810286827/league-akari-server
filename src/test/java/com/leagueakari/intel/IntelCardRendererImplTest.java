package com.leagueakari.intel;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敌方情报卡渲染器单测：验证渲染器输入结构化情报 → 输出非空 PNG 字节；
 * 覆盖全路人/单组/多组/缺失标注等视觉情形的"渲染不抛异常且产出 PNG 魔数"。
 * <p>像素级视觉断言不在单测范围（沿用 ReportImageProjectorTest 的样张人工归档模式）。</p>
 */
class IntelCardRendererImplTest {

    private final IntelCardRendererImpl renderer = new IntelCardRendererImpl();

    /** 构造一份含 2 名敌方 + 1 组开黑的完整情报 */
    private EnemyIntel intel() {
        EnemyIntel.EnemyPlayer a = new EnemyIntel.EnemyPlayer(
                "p1", "敌方甲", "DIAMOND", "III", WindowWinRate.of("p1", List.of(true, true, false)));
        EnemyIntel.EnemyPlayer b = new EnemyIntel.EnemyPlayer(
                "p2", "敌方乙", "GOLD", "I", WindowWinRate.of("p2", List.of(false, false)));
        PremadeDetector.PremadeGroup group = new PremadeDetector.PremadeGroup(
                List.of("p1", "p2"), 2, List.of("m1", "m2"));
        return new EnemyIntel("iKun", 100, "单双排", List.of(a, b), List.of(group),
                List.of(new EnemyIntel.PremadeWinRate(1, 2)));
    }

    @Test
    void 渲染完整情报_输出PNG魔数() {
        byte[] png = renderer.render(intel());
        assertThat(png).isNotEmpty();
        // PNG 魔数：89 50 4E 47
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1] & 0xFF).isEqualTo(0x50);
        assertThat(png[2] & 0xFF).isEqualTo(0x4E);
        assertThat(png[3] & 0xFF).isEqualTo(0x47);
    }

    @Test
    void 全路人无开黑_渲染不抛异常() {
        EnemyIntel intel = new EnemyIntel("iKun", 100, "匹配",
                List.of(
                        new EnemyIntel.EnemyPlayer("p1", "甲", null, null, WindowWinRate.of("p1", List.of())),
                        new EnemyIntel.EnemyPlayer("p2", "乙", null, null, WindowWinRate.of("p2", List.of()))
                ),
                List.of(),
                List.of());
        assertThat(renderer.render(intel)).isNotEmpty();
    }

    @Test
    void 敌方5人全缺失_渲染不抛异常() {
        EnemyIntel intel = new EnemyIntel("iKun", 200, "单双排",
                List.of(
                        new EnemyIntel.EnemyPlayer("p1", null, null, null, WindowWinRate.of("p1", List.of())),
                        new EnemyIntel.EnemyPlayer("p2", null, null, null, WindowWinRate.of("p2", List.of())),
                        new EnemyIntel.EnemyPlayer("p3", null, null, null, WindowWinRate.of("p3", List.of())),
                        new EnemyIntel.EnemyPlayer("p4", null, null, null, WindowWinRate.of("p4", List.of())),
                        new EnemyIntel.EnemyPlayer("p5", null, null, null, WindowWinRate.of("p5", List.of()))
                ),
                List.of(),
                List.of());
        assertThat(renderer.render(intel)).isNotEmpty();
    }

    @Test
    void 多组开黑并存_渲染不抛异常() {
        PremadeDetector.PremadeGroup g1 = new PremadeDetector.PremadeGroup(
                List.of("p1", "p2"), 3, List.of("m1", "m2", "m3"));
        PremadeDetector.PremadeGroup g2 = new PremadeDetector.PremadeGroup(
                List.of("p3", "p4"), 2, List.of("m4", "m5"));
        EnemyIntel intel = new EnemyIntel("iKun", 100, "灵活组排",
                List.of(
                        new EnemyIntel.EnemyPlayer("p1", "甲", "GOLD", "II", WindowWinRate.of("p1", List.of(true))),
                        new EnemyIntel.EnemyPlayer("p2", "乙", "GOLD", "II", WindowWinRate.of("p2", List.of(true))),
                        new EnemyIntel.EnemyPlayer("p3", "丙", "PLAT", "IV", WindowWinRate.of("p3", List.of(false))),
                        new EnemyIntel.EnemyPlayer("p4", "丁", "PLAT", "IV", WindowWinRate.of("p4", List.of(false)))
                ),
                List.of(g1, g2),
                List.of(new EnemyIntel.PremadeWinRate(2, 3), new EnemyIntel.PremadeWinRate(1, 2)));
        assertThat(renderer.render(intel)).isNotEmpty();
    }

    /**
     * 样张归档（人工评审用）：渲染一张含分组/段位/胜率/缺失标注的代表性情报卡，
     * 写到项目根目录 intel-card-sample.png 供视觉验收。非像素断言，不影响 CI 通过。
     */
    @Test
    void 生成样张归档() throws Exception {
        EnemyIntel intel = new EnemyIntel("iKun", 100, "单双排",
                List.of(
                        new EnemyIntel.EnemyPlayer("p1", "敌方甲", "DIAMOND", "III", WindowWinRate.of("p1", List.of(true, true, false)), 238, "劫"),
                        new EnemyIntel.EnemyPlayer("p2", "敌方乙", "DIAMOND", "III", WindowWinRate.of("p2", List.of(true, false, true)), 103, "阿狸"),
                        new EnemyIntel.EnemyPlayer("p3", "敌方丙", "EMERALD", "II", WindowWinRate.of("p3", List.of(true, true, true)), 24, "贾克斯"),
                        new EnemyIntel.EnemyPlayer("p4", "敌方丁", "PLATINUM", "IV", WindowWinRate.of("p4", List.of(false, false)), 157, "亚索"),
                        new EnemyIntel.EnemyPlayer("p5", null, null, null, WindowWinRate.of("p5", List.of()), null, null)
                ),
                List.of(
                        new PremadeDetector.PremadeGroup(List.of("p1", "p2"), 8, List.of("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8")),
                        new PremadeDetector.PremadeGroup(List.of("p3", "p4", "p5"), 3, List.of("m9", "m10", "m11"))
                ),
                List.of(new EnemyIntel.PremadeWinRate(6, 8), new EnemyIntel.PremadeWinRate(2, 3)));
        byte[] png = renderer.render(intel);
        Files.write(Path.of("intel-card-sample.png"), png);
        assertThat(png).isNotEmpty();
    }
}
