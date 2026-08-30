package com.leagueakari.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 业务时钟配置：统一注入 java.time.Clock，业务侧"今天/本周/上一周"全部以此为基准
 * <p>独立成 Bean 的目的：测试可用 {@link Clock#fixed} 替换，使"默认上一周"等
 * 时间语义可确定性断言；时区固定 Asia/Shanghai（车队按国内作息开黑）。</p>
 */
@Configuration
public class TimeConfig {

    /**
     * 业务时钟（Asia/Shanghai）
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
