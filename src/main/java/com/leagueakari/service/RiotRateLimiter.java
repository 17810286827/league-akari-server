package com.leagueakari.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Riot 个人开发者 Key 的滚动窗口限流器
 * <p>Riot 个人 Key 限约为 100 请求/2 分钟，本限流器按"保留余量"的上限
 * 控制滚动窗口内的请求数，窗口满时阻塞等待最早请求滑出窗口。
 * 时钟与睡眠均可注入——测试用假时钟即可确定性验证窗口计算。</p>
 * <p>线程安全：回填任务在单线程执行器中运行，无需加锁。</p>
 */
public class RiotRateLimiter {

    /** 窗口内允许的最大请求数 */
    private final int maxPerWindow;

    /** 窗口长度（毫秒） */
    private final long windowMs;

    /** 毫秒时钟（可注入） */
    private final LongSupplier clockMillis;

    /** 睡眠动作（毫秒；测试注入"睡眠即推进时钟"的假实现） */
    private final LongConsumer sleeper;

    /** 滚动窗口内的请求时间戳 */
    private final Deque<Long> requestTimestamps = new ArrayDeque<>();

    public RiotRateLimiter(int maxPerWindow, long windowMs,
            LongSupplier clockMillis, LongConsumer sleeper) {
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
        this.clockMillis = clockMillis;
        this.sleeper = sleeper;
    }

    /**
     * 获取一个请求配额：窗口未满直接记录并返回；窗口满时阻塞等待最早请求滑出
     */
    public void acquire() {
        long now = clockMillis.getAsLong();
        evictExpired(now);
        if (requestTimestamps.size() >= maxPerWindow) {
            // 需要等待的时长 = 最早请求滑出窗口还差的时间（+1ms 保证严格滑出）
            long oldest = requestTimestamps.peekFirst();
            long waitMs = windowMs - (now - oldest) + 1;
            if (waitMs > 0) {
                sleeper.accept(waitMs);
                now = clockMillis.getAsLong();
                evictExpired(now);
            }
        }
        requestTimestamps.addLast(now);
    }

    /** 清理已滑出窗口的请求记录 */
    private void evictExpired(long now) {
        while (!requestTimestamps.isEmpty() && now - requestTimestamps.peekFirst() >= windowMs) {
            requestTimestamps.pollFirst();
        }
    }
}
