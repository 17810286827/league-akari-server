package com.leagueakari.intel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 窗口胜率统计单测（纯函数）：喂进来的 20 局窗口原始胜率（胜场/局数），不做队列过滤。
 * 空窗口（0 局）返回"无数据"，避免除零；局数与胜场一并返回供展示（不裸百分比）。
 */
class WindowWinRateTest {

    @Test
    void 全胜_胜率100() {
        WindowWinRate r = WindowWinRate.of("P1", List.of(true, true, true));
        assertThat(r.getWins()).isEqualTo(3);
        assertThat(r.getGames()).isEqualTo(3);
        assertThat(r.getWinRate()).isEqualTo(1.0);
    }

    @Test
    void 全败_胜率0() {
        WindowWinRate r = WindowWinRate.of("P1", List.of(false, false));
        assertThat(r.getWins()).isZero();
        assertThat(r.getGames()).isEqualTo(2);
        assertThat(r.getWinRate()).isZero();
    }

    @Test
    void 混战_按胜场除局数() {
        // 20 局 12 胜 → 60%
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            results.add(true);
        }
        for (int i = 0; i < 8; i++) {
            results.add(false);
        }
        WindowWinRate r = WindowWinRate.of("P1", results);
        assertThat(r.getGames()).isEqualTo(20);
        assertThat(r.getWins()).isEqualTo(12);
        assertThat(r.getWinRate()).isEqualTo(0.6);
    }

    @Test
    void 空窗口_无数据不除零() {
        WindowWinRate r = WindowWinRate.of("P1", List.of());
        assertThat(r.getGames()).isZero();
        assertThat(r.getWins()).isZero();
        assertThat(r.getWinRate()).isNull();
        assertThat(r.hasData()).isFalse();
    }
}
