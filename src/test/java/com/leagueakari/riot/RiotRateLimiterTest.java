package com.leagueakari.riot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * RiotRateLimiter 单元测试：滚动窗口限流逻辑
 * <p>用假时钟 + 假睡眠器（睡眠即推进时钟）确定性验证窗口计算，
 * 覆盖：窗口内不限流、达到上限触发等待、等待后窗口滑动放行。</p>
 */
class RiotRateLimiterTest {

    /** 用例：窗口内请求数未达上限时不睡眠 */
    @Test
    void acquire_noSleepBelowLimit() {
        AtomicLong now = new AtomicLong(0);
        List<Long> sleeps = new ArrayList<>();
        RiotRateLimiter limiter = new RiotRateLimiter(2, 120_000, now::get, sleeps::add);

        limiter.acquire();
        limiter.acquire();

        assertThat(sleeps).isEmpty();
    }

    /** 用例：达到上限后第 N+1 个请求需等待"最早请求滑出窗口"为止 */
    @Test
    void acquire_sleepsWhenWindowFull() {
        AtomicLong now = new AtomicLong(0);
        List<Long> sleeps = new ArrayList<>();
        // 上限 2、窗口 100ms：前两个立即通过，第三个必须等第一个滑出（0 + 100 → 等待 101ms）
        RiotRateLimiter limiter = new RiotRateLimiter(2, 100, now::get, ms -> {
            sleeps.add(ms);
            now.addAndGet(ms);
        });

        limiter.acquire();
        limiter.acquire();
        limiter.acquire();

        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isGreaterThan(0);
    }

    /** 用例：窗口整体滑出后（时钟推进超过窗口），请求直接通过不再睡眠 */
    @Test
    void acquire_noSleepAfterWindowSlides() {
        AtomicLong now = new AtomicLong(0);
        List<Long> sleeps = new ArrayList<>();
        RiotRateLimiter limiter = new RiotRateLimiter(2, 100, now::get, ms -> {
            sleeps.add(ms);
            now.addAndGet(ms);
        });

        limiter.acquire();
        limiter.acquire();
        // 时间推进到窗口之外：两条记录都滑出
        now.addAndGet(150);
        assertThatNoException().isThrownBy(limiter::acquire);

        assertThat(sleeps).isEmpty();
    }
}
