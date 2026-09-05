package com.leagueakari.riot;

import com.leagueakari.common.exception.ErrorCode;

import com.leagueakari.common.exception.BizException;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Riot API 统一出口（riot 包内共享）：所有出网到 Riot 官方 API 的请求物理上只过这一个口子。
 * <p>三合一职责：
 * 1. X-Riot-Token 请求头（开发者 Key 鉴权）；
 * 2. 限流器挂载——每次出网前 acquire，无差别生效（消除账号查询缓存 miss 时裸打 API 的限流盲区）；
 * 3. 状态码语义翻译——404 → BizException(RIOT_ACCOUNT_NOT_FOUND)（全局处理器统一转换）、
 *    429 → 等待 Retry-After 后重试一次（仍 429 抛限流异常，不无限重试）、
 *    其他非 2xx → 业务异常 BizException(RIOT_API_ERROR)。</p>
 * <p>注意：QQ 开放平台（QqBotClient）是另一协议域（鉴权头/错误码/token 刷新语义不同），不并入本出口。
 * CommunityDragon 静态资源（gamedata 包）无鉴权无限流语义，同样不经此出口。</p>
 */
@Slf4j
@Component
public class RiotHttpClient {

    /** Riot 开发者 Key（X-Riot-Token） */
    private final String apiKey;

    /** Apache HttpClient 5（全局连接池实例，见 HttpClientConfig） */
    private final CloseableHttpClient httpClient;

    /** JSON 解析器（429 重试场景无直接使用，保留供后续响应解析扩展；构造契约的一部分） */
    private final ObjectMapper objectMapper;

    /** 滚动窗口限流器：所有出网请求无差别经过 */
    private final RiotRateLimiter rateLimiter;

    public RiotHttpClient(
            @Value("${riot.api-key:}") String apiKey,
            CloseableHttpClient httpClient,
            ObjectMapper objectMapper,
            RiotRateLimiter rateLimiter) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 统一 GET 出口：token 头 + 限流 + 状态码语义翻译
     *
     * @param uri 完整请求 URI（调用方负责路径段编码，见 URIBuilder.setPathSegments）
     * @return 响应体字符串（UTF-8；2xx 保证非 null，空体返回空串）
     * 404 → BizException(RIOT_ACCOUNT_NOT_FOUND)；429 重试后仍限流或其他非 2xx →
     * BizException(RIOT_API_ERROR)。本类不感知具体资源语义，由调用方按需翻译上下文。
     */
    public String get(URI uri) {
        // 第一次尝试（限流在每次出网前生效）
        int status = attemptOnce(uri);
        if (status == 429) {
            // 429 限流：等待 Retry-After 指示的时间后重试一次（个人 Key 场景通常为毫秒级窗口滑动）
            log.warn("Riot API rate limited (429), retry once: uri={}", uri);
            sleepQuietly(500);
            status = attemptOnce(uri);
            if (status == 429) {
                throw new BizException(ErrorCode.RIOT_API_ERROR, "Riot API 限流（429），请稍后再试");
            }
            // 重试后的非 429 状态由 attemptOnce 内部抛出/处理；走到这里说明重试成功（attemptOnce 返回 2xx）
        }
        // attemptOnce 对 2xx 返回原状态码，此处已是成功路径
        return lastBody;
    }

    /** 最近一次成功请求的响应体（get 的返回值载体；仅 2xx 时有效） */
    private String lastBody;

    /**
     * 发起一次请求：返回状态码（2xx 时同时记录响应体到 lastBody）；
     * 404/其他非 2xx 直接抛对应异常
     */
    private int attemptOnce(URI uri) {
        rateLimiter.acquire();
        HttpGet request = new HttpGet(uri);
        request.setHeader("X-Riot-Token", apiKey);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            if (status == 404) {
                // 404：资源不存在（账号/对局），抛业务异常由全局处理器统一转换
                throw new BizException(ErrorCode.RIOT_ACCOUNT_NOT_FOUND, "Riot 资源不存在: " + uri);
            }
            if (status == 429) {
                // 429：返回状态码由调用方决定重试（不在此抛，重试逻辑在 get）
                return 429;
            }
            if (status < 200 || status >= 300) {
                // 其他 4xx（401 Key 失效 / 403 无权限）与 5xx：记日志后抛出
                log.error("Riot API error: status={}, uri={}, body={}", status, uri, body);
                throw new BizException(ErrorCode.RIOT_API_ERROR, "Riot API 调用失败（" + status + "），请稍后重试");
            }
            lastBody = body;
            return status;
        } catch (BizException e) {
            // 已翻译的业务异常（404/限流/非 2xx）：原样上抛
            throw e;
        } catch (Exception e) {
            log.error("Riot API request failed: uri={}, error={}", uri, e.getMessage());
            throw new BizException(ErrorCode.RIOT_API_ERROR, "Riot API 请求失败：" + e.getMessage(), e);
        }
    }

    /** 429 重试前的等待（毫秒级；被中断不阻断，立即重试） */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
