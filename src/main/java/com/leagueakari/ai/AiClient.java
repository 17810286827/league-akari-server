package com.leagueakari.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 大模型公共调用客户端（纯 HTTP 接缝，见 docs/adr/0005）：
 * 统一收口三个业务场景（单局分析/周报锐评/局后锐评）原先各自手写的 chat/completions 调用。
 * <p>职责边界：只管连接级事务——URL 拼接、Bearer 鉴权、浏览器 UA（Cloudflare 防护，
 * 无 UA 会被 403/503 拦截）、payload 组装、HTTP 错误转 IllegalStateException、
 * 响应/SSE 解析、API Key 前置校验。<b>不感知业务场景</b>：采样参数由调用方经
 * {@link AiCompletionRequest} 显式传入；JVM 缓存、提示词加载（见 PromptLoader）等
 * 业务策略留在各业务服务。传输层可靠性原语（空正文重试）由 {@link #call} 重载提供
 * （架构清理 T7：与连接重试同类，住客户端）。</p>
 * <p>HTTP 走全局连接池 HttpClient（见 HttpClientConfig：读超时 300s、
 * disableAutomaticRetries 防 POST 重复计费）；流式调用由调用方在其专用线程池中执行。</p>
 */
@Slf4j
@Component
public class AiClient {

    /** 浏览器 UA：网关经 Cloudflare 防护，无 UA/程序化请求会被 403/503 拦截 */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 网关基础地址（OpenAI 兼容，请求实际打到 baseUrl + "/chat/completions"） */
    private final String baseUrl;

    /** API Key（AiProperties 注入，Bearer 鉴权用） */
    private final String apiKey;

    /** 全局连接池 HttpClient（与 Riot API 共用，见 HttpClientConfig） */
    private final CloseableHttpClient httpClient;

    /** JSON 序列化/解析（Spring 全局 ObjectMapper） */
    private final ObjectMapper objectMapper;

    /** 构造注入：连接级配置统一取自 AiProperties（yaml 唯一真值，见 docs/adr/0004） */
    public AiClient(AiProperties ai, CloseableHttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUrl = ai.getBaseUrl();
        this.apiKey = ai.getApiKey();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 非流式调用 chat/completions（一次请求拿完整回答，周报/局后锐评场景）：
     * 请求体 { model, stream:false, temperature, max_tokens, messages }，
     * penalty 为 null 时不进 payload；thinking=false 时写 chat_template_kwargs.thinking=false。
     *
     * @param request      采样参数（调用方从 AiProperties 组装）
     * @param systemPrompt 系统提示词
     * @param userContent  用户消息（业务摘要 JSON/文本）
     * @param logContext   日志上下文标识（调用方传入，如 "weekly:2026-09"），仅用于日志排障关联，
     *                     组件不解析其内容、不感知业务概念
     * @return 回答正文；<b>正文为空时返回 null</b>（多为推理模型把预算耗在思维链上，
     *         finish_reason=length），是否重试由调用方决定
     * @throws IllegalStateException 非 200 / 网络异常 / 响应解析失败
     */
    public String call(AiCompletionRequest request, String systemPrompt, String userContent,
            String logContext) {
        long startTime = System.currentTimeMillis();
        // 非流式 payload：penalty 为 null 不传（保持周报/局后场景的既有采样行为）
        Map<String, Object> payload = buildPayload(request, false, systemPrompt, userContent);
        HttpPost post = buildPost(payload);
        // 请求发出前打日志：若此处之后长时间无日志，说明卡在连接建立/TLS 握手
        log.info("AI API request starting: context={}, url={}, model={}, stream=false, elapsed={}ms",
                logContext, baseUrl + "/chat/completions", request.model(),
                System.currentTimeMillis() - startTime);
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            log.info("AI API response received: context={}, status={}, elapsed={}ms",
                    logContext, status, System.currentTimeMillis() - startTime);
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            if (status != HttpStatus.OK.value()) {
                // 非 200：读错误体（OpenAI 兼容接口返回 JSON 错误）后抛出，提示用户稍后重试
                log.error("AI API error: context={}, status={}, body={}", logContext, status, body);
                throw new IllegalStateException("AI 接口调用失败（HTTP " + status + "），请稍后重试");
            }
            JsonNode choice = objectMapper.readTree(body).path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText("");
            String content = choice.path("message").path("content").asText("");
            if (content.isBlank()) {
                // 正文为空：多为推理模型把输出预算耗在思维链（finish_reason=length），
                // 返回 null 交调用方决定重试；finish_reason 落日志便于确认根因
                log.warn("AI returned empty content: context={}, finishReason={}", logContext, finishReason);
                return null;
            }
            return content;
        } catch (IOException e) {
            // 网络/超时等客户端异常：完整堆栈（区分连接超时/读超时/SSL 异常）
            log.error("AI API request failed: context={}, elapsed={}ms", logContext,
                    System.currentTimeMillis() - startTime, e);
            throw new IllegalStateException("AI 接口调用失败，请检查网络与 API Key");
        } catch (IllegalStateException e) {
            // 业务错误（非 200 等）：原样上抛
            throw e;
        } catch (Exception e) {
            log.error("AI response parse failed: context={}", logContext, e);
            throw new IllegalStateException("AI 返回数据异常，请稍后重试");
        }
    }


    /**
     * 带空正文重试的非流式调用（传输层可靠性原语）：正文为空（推理模型把预算耗在
     * 思维链、finish_reason=length）时自动重试，共最多 {@code maxAttempts} 次；
     * 全部尝试后仍为空返回 null（调用方决定失败语义，如局后锐评的缺席提示降级）。
     * <p>API Key 前置校验：未配置直接抛 IllegalStateException（三处调用方各自的
     * 校验收敛到此，消除三个真相来源）。</p>
     *
     * @param maxAttempts 最大尝试次数（含首次；如 2 = 首次 + 1 次重试）
     */
    public String call(AiCompletionRequest request, String systemPrompt, String userContent,
            String logContext, int maxAttempts) {
        requireApiKey();
        String comment = null;
        for (int attempt = 1; attempt <= maxAttempts && comment == null; attempt++) {
            comment = call(request, systemPrompt, userContent, logContext);
            if (comment == null) {
                log.warn("AI empty content, retrying: context={}, attempt={}/{}",
                        logContext, attempt, maxAttempts);
            }
        }
        return comment;
    }

    /** API Key 前置校验：未配置抛 IllegalStateException（调用方的统一拦截点） */
    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI API Key 未配置，无法调用大模型");
        }
    }

    /**
     * API Key 是否已配置（流式场景的前置校验入口，如单局分析在 SSE 流建立前拦截）：
     * 与 {@link #call} 重载内的校验同源，消除各服务自判 Key 状态的真相分裂
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 流式调用 chat/completions（stream=true，单局分析场景）：
     * 在调用方线程内逐行解析 SSE 流（data: 前缀承载 JSON，[DONE] 结束），
     * 推理模式的思维链增量（delta.reasoning_content）交 {@link AiStreamHandler#onReasoning}，
     * 正文增量（delta.content）交 {@link AiStreamHandler#onContent}，顺序与到达顺序一致。
     * 回调内抛出的异常原样穿透（不被转译、不被吞掉），用于调用方终止上游消费（如客户端断开）。
     * 关键节点打日志：请求发出、响应状态、首块耗时（TTFT）、结束统计——便于定位
     * "卡在连接建立还是模型慢"
     *
     * @param request      采样参数（调用方从 AiProperties 组装；单局分析含 penalty）
     * @param systemPrompt 系统提示词
     * @param userContent  用户消息（对局数据摘要）
     * @param handler      增量回调（正文必需，思维链可选）
     * @param logContext   日志上下文标识（调用方传入，如 "gameId=123"），仅用于日志排障关联
     * @return 流结束原因 finish_reason：stop=自然完成，length=预算截断；
     *         流自然结束但未携带 finish_reason 时返回 null
     * @throws IllegalStateException 非 200 / 网络异常 / SSE 数据解析失败
     */
    public String callStream(AiCompletionRequest request, String systemPrompt, String userContent,
            AiStreamHandler handler, String logContext) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> payload = buildPayload(request, true, systemPrompt, userContent);
        HttpPost post = buildPost(payload);
        // 请求发出前打日志：若此处之后长时间无日志，说明卡在连接建立/TLS 握手
        log.info("AI API request starting: context={}, url={}, model={}, stream=true, elapsed={}ms",
                logContext, baseUrl + "/chat/completions", request.model(),
                System.currentTimeMillis() - startTime);
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            log.info("AI API response received: context={}, status={}, elapsed={}ms",
                    logContext, status, System.currentTimeMillis() - startTime);
            if (status != HttpStatus.OK.value()) {
                String errBody = response.getEntity() != null
                        ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
                log.error("AI API error: context={}, status={}, body={}", logContext, status, errBody);
                throw new IllegalStateException("AI 接口调用失败（HTTP " + status + "），请稍后重试");
            }
            // 逐行解析 SSE 流：空行分隔事件，data: 前缀承载 JSON，[DONE] 表示结束。
            // 记录首块耗时（TTFT）与块数/字数——思维链或正文任一到达都算有数据，
            // 避免"模型在思考但看似无响应"的误判
            int chunkCount = 0;
            int reasoningCount = 0;
            int totalChars = 0;
            long firstChunkAt = -1L;
            String finishReason = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        log.info("AI API stream done marker: context={}, chunks={}, reasoning={}, chars={}, elapsed={}ms",
                                logContext, chunkCount, reasoningCount, totalChars,
                                System.currentTimeMillis() - startTime);
                        break;
                    }
                    // 推理模式增量在 delta.reasoning_content，正文在 delta.content
                    JsonNode choice = objectMapper.readTree(data).path("choices").path(0);
                    // finish_reason 在流的最后一个 chunk 携带：stop=自然完成 / length=预算截断
                    if (choice.hasNonNull("finish_reason")) {
                        finishReason = choice.get("finish_reason").asText();
                    }
                    JsonNode delta = choice.path("delta");
                    String content = delta.path("content").asText("");
                    String reasoning = delta.path("reasoning_content").asText("");
                    if (firstChunkAt < 0 && (!content.isEmpty() || !reasoning.isEmpty())) {
                        // 首块到达时间：连接建立后到第一个数据块的延迟（模型响应速度指标）
                        firstChunkAt = System.currentTimeMillis();
                        log.info("AI API first data received: context={}, elapsed={}ms",
                                logContext, firstChunkAt - startTime);
                    }
                    if (!content.isEmpty()) {
                        chunkCount++;
                        totalChars += content.length();
                        handler.onContent(content);
                    }
                    if (!reasoning.isEmpty()) {
                        reasoningCount++;
                        handler.onReasoning(reasoning);
                    }
                }
            }
            // 正常退出循环但没看到 [DONE]（如流被服务器提前断开）也能走到这里，靠返回值兜底
            log.info("AI API stream read finished: context={}, chunks={}, reasoning={}, chars={}, "
                    + "finishReason={}, elapsed={}ms",
                    logContext, chunkCount, reasoningCount, totalChars, finishReason,
                    System.currentTimeMillis() - startTime);
            return finishReason;
        } catch (IllegalStateException e) {
            // 业务错误（非 200 等）：原样上抛；回调内抛出的普通 IllegalStateException（如 SSE 推送失败）
            // 同样从这里原样穿透，不转译
            throw e;
        } catch (JsonProcessingException | ParseException e) {
            // SSE 数据解析失败：模型返回非预期格式
            log.error("Failed to parse AI stream data: context={}", logContext, e);
            throw new IllegalStateException("AI 返回数据异常，请稍后重试");
        } catch (IOException e) {
            // 网络/超时等客户端异常：完整堆栈（区分连接超时/读超时/SSL 异常）
            log.error("AI API request failed: context={}, elapsed={}ms", logContext,
                    System.currentTimeMillis() - startTime, e);
            throw new IllegalStateException("AI 接口调用失败，请检查网络与 API Key");
        }
    }

    /**
     * 组装 chat/completions 请求体（流式/非流式共用）：
     * penalty 为 null 时省略对应键；thinking=false 时写 DeepSeek 原生参数
     * chat_template_kwargs.thinking=false（网关不认 OpenAI 风格的 reasoning_effort），
     * thinking=true 时不写该键（保持模型默认推理模式）。
     * LinkedHashMap 保证键序稳定，便于日志比对与测试断言
     */
    private Map<String, Object> buildPayload(AiCompletionRequest request, boolean stream,
            String systemPrompt, String userContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("stream", stream);
        payload.put("temperature", request.temperature());
        // penalty 为 null 时不进 payload（周报/局后场景保持既有采样行为，避免输出风格漂移）
        if (request.frequencyPenalty() != null) {
            payload.put("frequency_penalty", request.frequencyPenalty());
        }
        if (request.presencePenalty() != null) {
            payload.put("presence_penalty", request.presencePenalty());
        }
        // 输出上限：思维链与正文共享预算，限制推理模型的无限思考
        payload.put("max_tokens", request.maxTokens());
        if (!request.thinking()) {
            payload.put("chat_template_kwargs", Map.of("thinking", false));
        }
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        ));
        return payload;
    }

    /**
     * 构造 POST 请求并设置三件套请求头：
     * Content-Type: application/json、Authorization: Bearer（鉴权）、
     * User-Agent: 浏览器 UA（Cloudflare 防护，无 UA 会被 403/503 拦截）。
     * 序列化失败抛 IllegalStateException（配置/数据异常，payload 结构固定实际不会发生）
     */
    private HttpPost buildPost(Map<String, Object> payload) {
        HttpPost post = new HttpPost(baseUrl + "/chat/completions");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Authorization", "Bearer " + apiKey);
        post.setHeader("User-Agent", BROWSER_USER_AGENT);
        try {
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload),
                    ContentType.APPLICATION_JSON));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AI request payload: {}", e.getMessage());
            throw new IllegalStateException("AI 请求组装失败");
        }
        return post;
    }
}
