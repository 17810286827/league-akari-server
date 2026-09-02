package com.leagueakari.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局后锐评服务（外部 I/O 接缝）：战报图发送后，按车队视角对本局做一次非流式 AI 点评，
 * 输出可直接发群的正文。区别于单局 AI 分析（self 视角、SSE 流式）与周报锐评（按周聚合）。
 * <p>失败语义：空正文自动重试 1 次（共最多 2 次调用），仍失败抛 IllegalStateException，
 * 由 BroadcastCoordinator 降级为"AI 缺席提示"发送。</p>
 */
@Slf4j
@Service
public class PostGameCommentService {

    /** 浏览器 UA：opencode go 经 Cloudflare 防护，无 UA 会被 403/503 拦截（与既有 AI 调用一致） */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 最大调用次数：1 次正常 + 1 次空正文重试 */
    private static final int MAX_ATTEMPTS = 2;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String promptFile;
    private final double temperature;
    private final int maxTokens;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PostGameCommentService(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key:}") String apiKey,
            // 局后锐评独立模型：短任务用 deepseek-v4-flash（thinking=false 真正生效，
            // 直出正文首 token ~1s；mimo-v2.5 无视该参数仍先推理 ~60s，实测差 20 倍）
            @Value("${ai.post-game-model:${ai.model:mimo-v2.5}}") String model,
            @Value("${ai.post-game-prompt-file:ai/post-game-prompt.md}") String promptFile,
            @Value("${ai.temperature:1.0}") double temperature,
            @Value("${ai.post-game-max-tokens:2048}") int maxTokens,
            CloseableHttpClient httpClient,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.promptFile = promptFile;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成局后锐评
     *
     * @param summary 本局摘要（胜负/比分/车队成员/焦点），由编排层组装
     * @return 可直接发群的锐评正文
     * @throws IllegalStateException API Key 未配置 / 接口失败 / 重试后正文仍为空
     */
    public String generateComment(Map<String, Object> summary) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Post-game comment skipped: API key not configured");
            throw new IllegalStateException("AI API Key 未配置，无法生成局后锐评");
        }
        String systemPrompt = loadPrompt();
        String userContent;
        try {
            userContent = objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.error("Failed to serialize post-game summary: {}", e.getMessage());
            throw new IllegalStateException("局后摘要组装失败", e);
        }

        // 空正文重试：推理模型偶发把输出预算耗在思维链上（finish_reason=length），
        // 重试一次通常能拿到正文；仍为空才判定失败交给编排层降级
        String comment = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && comment == null; attempt++) {
            comment = callAi(systemPrompt, userContent);
            if (comment == null) {
                log.warn("Post-game AI empty content, retrying: attempt={}/{}", attempt, MAX_ATTEMPTS);
            }
        }
        if (comment == null) {
            throw new IllegalStateException("AI 返回内容为空，局后锐评生成失败");
        }
        log.info("Post-game comment generated: length={}", comment.length());
        return comment;
    }

    /** 读取局后锐评提示词（classpath；缺失时回退内置默认，保证接口可用） */
    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource(promptFile);
            if (resource.exists()) {
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load post-game prompt file {}: {}", promptFile, e.getMessage());
        }
        return "你是车队开黑群的锐评官，根据提供的本局数据用中文写一段 150 字以内毒舌但善意的锐评，"
                + "点名最亮眼与最拉胯的队友，不要使用 markdown 格式。";
    }

    /**
     * 非流式调用 chat/completions（与周报锐评同款参数）：
     * 非 200 抛状态异常；正文为空返回 null（由调用方重试）
     */
    private String callAi(String systemPrompt, String userContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", false);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        // 与既有 AI 调用一致：关闭思考模式直接出正文（局后锐评不需要思维链）
        payload.put("chat_template_kwargs", Map.of("thinking", false));
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)));

        HttpPost post = new HttpPost(baseUrl + "/chat/completions");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Authorization", "Bearer " + apiKey);
        post.setHeader("User-Agent", BROWSER_USER_AGENT);
        try {
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload),
                    ContentType.APPLICATION_JSON));
        } catch (Exception e) {
            log.error("Failed to serialize post-game AI payload: {}", e.getMessage());
            throw new IllegalStateException("局后锐评请求组装失败", e);
        }
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            if (status != HttpStatus.OK.value()) {
                log.error("Post-game AI API error: status={}, body={}", status, body);
                throw new IllegalStateException("AI 接口调用失败（HTTP " + status + "），请稍后重试");
            }
            JsonNode choice = objectMapper.readTree(body).path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText("");
            String content = choice.path("message").path("content").asText("");
            if (content.isBlank()) {
                log.warn("Post-game AI returned empty content, finishReason={}", finishReason);
                return null;
            }
            return content;
        } catch (IOException e) {
            log.error("Post-game AI API request failed: {}", e.getMessage());
            throw new IllegalStateException("AI 接口调用失败，请检查网络与 API Key", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Post-game AI response parse failed: {}", e.getMessage());
            throw new IllegalStateException("AI 返回数据异常，请稍后重试", e);
        }
    }
}
