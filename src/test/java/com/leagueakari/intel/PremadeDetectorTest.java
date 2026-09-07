package com.leagueakari.intel;

import com.leagueakari.intel.PremadeDetector.PremadeGroup;
import com.leagueakari.intel.PremadeDetector.TeamMatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开黑判定引擎单测（纯函数，无 Spring/DB 依赖）：
 * 口径与桌面端 LeagueAkari team-up-calc 同移植，覆盖无共现/单组/多组/子集/阈值边界/非候选玩家。
 */
class PremadeDetectorTest {

    /**
     * 把"对局 id + 同队玩家数组"包装为引擎入参
     */
    private static List<TeamMatch> matches(Object[]... gameGroups) {
        List<TeamMatch> result = new ArrayList<>();
        for (int g = 0; g < gameGroups.length; g++) {
            result.add(new TeamMatch("m" + g, List.of((String[]) gameGroups[g])));
        }
        return result;
    }

    @Test
    void 无共现时返回空列表() {
        // 候选 6 人两两从未同队：返回无开黑分组
        List<PremadeGroup> result = PremadeDetector.detect(matches(
                new String[]{"A", "B"},
                new String[]{"C", "D"},
                new String[]{"E", "F"}
        ), List.of("A", "B", "C", "D", "E", "F"), 2);
        assertThat(result).isEmpty();
    }

    @Test
    void 单组两人共现达阈值() {
        // A、B 两局同队 → 判定为一组开黑
        List<PremadeGroup> result = PremadeDetector.detect(matches(
                new String[]{"A", "B", "C"},
                new String[]{"A", "B", "D"}
        ), List.of("A", "B", "C", "D"), 2);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlayers()).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void 两组独立开黑() {
        // {A,B} 共现两局、{C,D} 共现两局，互不相干 → 两个分组
        List<PremadeGroup> result = PremadeDetector.detect(matches(
                new String[]{"A", "B", "X"},
                new String[]{"C", "D", "Y"},
                new String[]{"A", "B", "Z"},
                new String[]{"C", "D", "W"}
        ), List.of("A", "B", "C", "D", "X", "Y", "Z", "W"), 2);
        assertThat(result).hasSize(2);
    }

    @Test
    void 三人组淘汰子组() {
        // A,B,C 三人共现 2 局；A,B 作为子组也共现 2 局 → 只保留 3 人组
        List<PremadeGroup> result = PremadeDetector.detect(matches(
                new String[]{"A", "B", "C", "X"},
                new String[]{"A", "B", "C", "Y"}
        ), List.of("A", "B", "C", "X", "Y"), 2);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlayers()).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    void 阈值边界恰等判黑() {
        // 共现次数恰等于阈值 → 判定为开黑
        List<PremadeGroup> result = PremadeDetector.detect(matches(
                new String[]{"A", "B", "C"},
                new String[]{"A", "B", "D"}
        ), List.of("A", "B", "C", "D"), 2);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTimes()).isEqualTo(2);
    }

    @Test
    void 阈值未达不判黑() {
        // 共现 1 次 < 阈值 2 → 不算开黑
        List<PremadeGroup> result = PremadeDetector.detect(matches(
                new String[]{"A", "B", "C"},
                new String[]{"D", "E", "F"}
        ), List.of("A", "B", "C", "D", "E", "F"), 2);
        assertThat(result).isEmpty();
    }

    @Test
    void 候选之外的玩家不参与判定() {
        // 局内有非候选玩家（如我方混入的玩家），不参与开黑判定也不影响结果
        List<PremadeGroup> result = PremadeDetector.detect(matches(
                new String[]{"A", "B", "SELF"},
                new String[]{"A", "B", "SELF"}
        ), List.of("A", "B"), 2);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlayers()).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void 一局蓝红两撮分别计数不跨队连边() {
        // 同一局 A,B 蓝方；C,D 红方：A 与 C 虽同局但不同队 → 不产生共现
        List<PremadeGroup> result = PremadeDetector.detect(List.of(
                new TeamMatch("m1", List.of("A", "B")),
                new TeamMatch("m1", List.of("C", "D")),
                new TeamMatch("m2", List.of("A", "B")),
                new TeamMatch("m2", List.of("C", "D"))
        ), List.of("A", "B", "C", "D"), 2);
        // 蓝方 {A,B} 红方 {C,D} 各成一组，而不是 A 和 C 算共现
        assertThat(result).hasSize(2);
    }

    @Test
    void 三人组times为全组真正同场数而非两两共现最小值() {
        // A,B,C 三人同场 4 局（m0~m3）。组内两两 A-B 共现 6 局（m0~m5：m4/m5 中
        // C 与 D 在另一队、A/B 同队），但三人真正同场只有 4 局。
        // 新口径 times=全组同场交集 = 4（旧口径"两两共现最小值"会虚报成 6）
        List<PremadeGroup> result = PremadeDetector.detect(List.of(
                new TeamMatch("m0", List.of("A", "B", "C")),
                new TeamMatch("m1", List.of("A", "B", "C")),
                new TeamMatch("m2", List.of("A", "B", "C")),
                new TeamMatch("m3", List.of("A", "B", "C")),
                new TeamMatch("m4", List.of("A", "B", "D", "E")),
                new TeamMatch("m5", List.of("A", "B", "D", "E"))
        ), List.of("A", "B", "C"), 4);
        // A,B 二人组：交集 {m0..m5} = 6 局 > 三人组 4 局 → 因有独立双排（m4/m5 无 C）保留为二人组
        // 此处候选仅 A/B/C（D/E 非候选不参与），故二人组 {A,B} 交集 = 6 也会被枚举出并保留
        // 期望：三人组（times=4）+ 二人组（times=6）两个组，且三人组 times 正确报 4
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPlayers()).containsExactlyInAnyOrder("A", "B", "C");
        assertThat(result.get(0).getTimes()).isEqualTo(4);
    }

    @Test
    void 二人子组有独立双排局时不被三人组剔除() {
        // A,B,C 三人同场 2 局（m0/m1）；A,B 另双排 3 局（m2~m4，共现 5）→
        // 二人组有超出大组的独立双排（交集 5 > 大组交集 2），情报上"AB 固定双排、C 常加入"
        // 更真实 → 两个组都保留
        List<PremadeGroup> result = PremadeDetector.detect(List.of(
                new TeamMatch("m0", List.of("A", "B", "C")),
                new TeamMatch("m1", List.of("A", "B", "C")),
                new TeamMatch("m2", List.of("A", "B")),
                new TeamMatch("m3", List.of("A", "B")),
                new TeamMatch("m4", List.of("A", "B"))
        ), List.of("A", "B", "C"), 2);
        assertThat(result).hasSize(2);
    }
}
