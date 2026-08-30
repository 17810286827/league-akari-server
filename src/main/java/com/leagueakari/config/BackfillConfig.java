package com.leagueakari.config;

import com.leagueakari.service.RiotRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 历史回填基础设施配置：Riot 限流器与回填专用执行器
 */
@Configuration
public class BackfillConfig {

    /**
     * Riot 限流器：个人开发者 Key 约 100 请求/2 分钟，上限取 90 留余量；
     * 真实时钟 + 真实睡眠（回填是后台任务，阻塞等待可接受）
     */
    @Bean
    public RiotRateLimiter riotRateLimiter() {
        return new RiotRateLimiter(90, 120_000L,
                System::currentTimeMillis,
                ms -> {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    /**
     * 回填专用单线程执行器：回填任务串行执行，与 AI 流式线程池隔离
     */
    @Bean
    public Executor backfillExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "backfill-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
