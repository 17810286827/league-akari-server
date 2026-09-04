package com.leagueakari.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 外部 HTTP 调用基础设施配置：
 * - CloseableHttpClient：全局唯一的 Apache HttpClient 5 实例（连接池 + 超时），
 *   Riot API 与 AI 模型调用共用，替换原 RestTemplate 方案
 * - aiStreamExecutor：AI 流式分析专用线程池（SseEmitter 推送必须在独立线程执行，
 *   不能阻塞 controller 返回响应头）
 */
@Configuration
public class HttpClientConfig {

    /**
     * 创建带连接池的 Apache HttpClient 5 实例：
     * - 连接池：最多 32 条连接、单路由 16 条（Riot/AI 两路调用互不挤占）
     * - 超时：连接建立 5 秒；响应读超时 300 秒（AI 推理模型先吐思维链再吐正文，流整体较长）
     * - 关闭自动重试：POST 请求（AI 分析）重试会重复调用模型产生费用，Riot API 有速率限制也不宜重试
     */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient httpClient() {
        // 连接池管理：设置每连接空闲保活与整体上限。
        // 响应超时 300 秒：AI 推理模型思考阶段先输出长思维链
        // 再输出正文，整个流可能持续数分钟，需给足读超时
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(5))
                                .setSocketTimeout(Timeout.ofSeconds(300))
                                .build())
                        .setMaxConnTotal(32)
                        .setMaxConnPerRoute(16)
                        .build();
        // 请求级配置：连接请求等待 5 秒，超时后直接失败而不是无限排队
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .build();
    }

    /**
     * AI 流式分析专用线程池：controller 返回 SseEmitter 后，由该线程池执行
     * 模型调用与 SSE 推送；队列满时退化为调用方线程执行（CallerRunsPolicy），
     * 保证不丢请求
     */
    @Bean
    public Executor aiStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-stream-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
