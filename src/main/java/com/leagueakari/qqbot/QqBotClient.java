package com.leagueakari.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.PushProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QQ 官方开放平台机器人客户端（外部 I/O 接缝）：
 * 负责换取并缓存 access_token、向车队群发送消息。
 * 只做 OpenAPI 协议层，不涉及业务判定（判定在 BroadcastCoordinator）。
 * <p>官方文档：凭证 api.bot.qq.com/app/getAppAccessToken；
 * 群消息 POST /v2/groups/{group_openid}/messages（msg_type 0=纯文本 / 7=富媒体）。</p>
 */
@Slf4j
@Service
public class QqBotClient {

    /** 官方 OpenAPI 统一域名（2026-08 起） */
    private static final String API_BASE = "https://api.bot.qq.com";

    /** 缓存条目：token + 过期毫秒时间戳 */
    private record TokenCache(String token, long expiresAtMs) {}

    /** 进程内 token 缓存：过期前 60 秒视为失效提前刷新 */
    private volatile TokenCache tokenCache;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PushProperties pushProperties;

    public QqBotClient(CloseableHttpClient httpClient, ObjectMapper objectMapper,
                       PushProperties pushProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.pushProperties = pushProperties;
    }

    /**
     * 向车队群发送纯文本消息（msg_type=0）
     *
     * @param groupOpenId 目标群 openid
     * @param content     文本内容
     * @throws QqPushException 凭证未配置 / 接口非 200 / 网络失败
     */
    public void sendGroupTextMessage(String groupOpenId, String content) {
        if (groupOpenId == null || groupOpenId.isBlank()) {
            throw new QqPushException("QQ 群 openid 未配置，无法推送");
        }
        String token = obtainAccessToken();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", 0);
        payload.put("content", content);

        HttpPost post = new HttpPost(API_BASE + "/v2/groups/" + groupOpenId + "/messages");
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON.toString());
        post.setHeader("Authorization", "Bearer " + token);
        try {
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload),
                    ContentType.APPLICATION_JSON));
        } catch (Exception e) {
            log.error("Failed to serialize QQ message payload: {}", e.getMessage());
            throw new QqPushException("QQ 消息请求组装失败", e);
        }
        executeChecked(post, "群消息发送");
    }

    /**
     * 换取并缓存 access_token：凭证接口返回 expires_in（秒），
     * 缓存至过期前 60 秒；并发下重复换取无害（幂等），由最后一次写覆盖
     *
     * @throws QqPushException 凭证未配置或换取失败
     */
    private String obtainAccessToken() {
        if (!pushProperties.isConfigured()) {
            throw new QqPushException("QQ 机器人凭证未配置（push.app-id / push.client-secret）");
        }
        TokenCache cached = tokenCache;
        if (cached != null && cached.expiresAtMs() - 60_000 > System.currentTimeMillis()) {
            return cached.token();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appId", pushProperties.getAppId());
        payload.put("clientSecret", pushProperties.getClientSecret());
        HttpPost post = new HttpPost(API_BASE + "/app/getAppAccessToken");
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON.toString());
        try {
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload),
                    ContentType.APPLICATION_JSON));
        } catch (Exception e) {
            log.error("Failed to serialize QQ token payload: {}", e.getMessage());
            throw new QqPushException("QQ 凭证请求组装失败", e);
        }

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            if (status != 200) {
                log.error("QQ token API error: status={}, body={}", status, body);
                throw new QqPushException("QQ 凭证换取失败（HTTP " + status + "）");
            }
            JsonNode json = objectMapper.readTree(body);
            String token = json.path("access_token").asText("");
            long expiresIn = json.path("expires_in").asLong(7200);
            if (token.isBlank()) {
                throw new QqPushException("QQ 凭证响应缺少 access_token");
            }
            tokenCache = new TokenCache(token, System.currentTimeMillis() + expiresIn * 1000);
            log.info("QQ access token refreshed: expiresIn={}s", expiresIn);
            return token;
        } catch (IOException e) {
            log.error("QQ token request failed: {}", e.getMessage());
            throw new QqPushException("QQ 凭证请求失败，请检查网络与凭证", e);
        } catch (QqPushException e) {
            throw e;
        } catch (Exception e) {
            log.error("QQ token response parse failed: {}", e.getMessage());
            throw new QqPushException("QQ 凭证响应异常", e);
        }
    }

    /**
     * 执行 POST 并校验：非 200 抛 QqPushException（携带官方错误码便于排障），
     * 网络/解析异常同样包装抛出
     */
    private void executeChecked(HttpPost post, String action) {
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            if (status != 200) {
                log.error("QQ {} API error: status={}, body={}", action, status, body);
                throw new QqPushException("QQ " + action + "失败（HTTP " + status + "）：" + body);
            }
            log.info("QQ {} success", action);
        } catch (IOException | org.apache.hc.core5.http.ParseException e) {
            log.error("QQ {} request failed: {}", action, e.getMessage());
            throw new QqPushException("QQ " + action + "请求失败，请检查网络", e);
        }
    }
}
