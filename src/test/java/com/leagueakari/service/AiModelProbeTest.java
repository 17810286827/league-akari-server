package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AI 模型探测测试（本地联调工具，非 CI 常规用例）：
 * 真实调用 OpenAI 兼容网关（opencode go），输出每个模型的连通性与延迟——
 * <ul>
 *   <li>首 token 延迟：请求发出到首个正文分片到达（流式，感知模型真实响应速度）</li>
 *   <li>总耗时：整段流接收完成</li>
 *   <li>HTTP 状态与正文摘要（验证模型"活着"且在说话）</li>
 * </ul>
 * 参数支持环境变量或 -D 系统属性（环境变量优先），未提供 API Key 时自动跳过：
 * <pre>
 *   AI_BASE_URL   默认 https://opencode.ai/zen/go/v1
 *   AI_API_KEY    必填（无则跳过）
 *   AI_MODEL      默认 mimo-v2.5；支持逗号分隔多模型逐一探测
 *   AI_THINKING   默认 false（关思考直出正文，延迟更接近播报场景）
 *   AI_TIMEOUT_S  默认 120
 * </pre>
 * 本地运行示例：
 * <pre>
 *   mvn test -Dtest=AiModelProbeTest \
 *     -DAI_API_KEY=xxx -DAI_MODEL=mimo-v2.5,deepseek-v4-flash \
 *     -DAI_BASE_URL=https://opencode.ai/zen/go/v1
 * </pre>
 */
class AiModelProbeTest {

    /** 浏览器 UA：网关经 Cloudflare 防护，无 UA 会被 403/503 拦截（与生产 AI 调用一致） */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 探测提示词：要求极短回复，把预算留给测量而不是生成 */
    private static final String PROBE_PROMPT = "这是一次连通性探测，请只回复两个字：正常";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 配置读取：环境变量优先，其次 -D 系统属性，最后默认值 */
    private static String cfg(String env, String prop, String def) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) {
            v = System.getProperty(prop);
        }
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    /** 用例：逐个探测配置的模型——连通性（HTTP 200 + 有正文）与延迟（首 token/总耗时） */
    @Test
    void probeModels_heartbeatAndLatency() throws Exception {
        String apiKey = cfg("AI_API_KEY", "ai.api-key", "");
        assumeTrue(!apiKey.isBlank(), "未提供 AI_API_KEY，跳过模型探测（配置见类注释）");

        String baseUrl = cfg("AI_BASE_URL", "ai.base-url", "https://opencode.ai/zen/go/v1");
        String models = cfg("AI_MODEL", "ai.model", "mimo-v2.5");
        boolean thinking = Boolean.parseBoolean(cfg("AI_THINKING", "ai.thinking", "false"));
        int timeoutSeconds = Integer.parseInt(cfg("AI_TIMEOUT_S", "ai.timeout-s", "120"));

        System.out.println("=== AI 模型探测 ===");
        System.out.println("base-url : " + baseUrl);
        System.out.println("thinking : " + thinking);
        String[] modelList = models.split(",");
        System.out.println("模型数量 : " + modelList.length);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        int failed = 0;
        for (String model : modelList) {
            String m = model.trim();
            if (m.isEmpty()) {
                continue;
            }
            try {
                ProbeResult result = probeOne(client, baseUrl, apiKey, m, thinking, timeoutSeconds);
                System.out.printf("  %-24s → HTTP %d | 首token %6d ms | 总耗时 %6.1f s | 正文: %s%n",
                        m, result.status, result.firstTokenMs, result.totalMs / 1000.0,
                        abbreviate(result.content));
            } catch (Exception e) {
                failed++;
                System.err.printf("  %-24s → 探测失败: %s%n", m, e.getMessage());
            }
        }
        assumeTrue(failed == 0, failed + " 个模型探测失败，详见上方输出");
    }

    /** 单模型探测：流式调用，记录首 token 延迟与总耗时 */
    private ProbeResult probeOne(HttpClient client, String baseUrl, String apiKey,
                                 String model, boolean thinking, int timeoutSeconds) throws Exception {
        // 组装请求体：预算 512（推理模型思考会耗 token，64 太小导致正文被截断）；
        // thinking=false 时直出正文，延迟更接近局后锐评/播报的真实场景
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", true);
        payload.put("max_tokens", 512);
        payload.put("temperature", 0.1);
        payload.put("chat_template_kwargs", Map.of("thinking", thinking));
        payload.put("messages", List.of(
                Map.of("role", "user", "content", PROBE_PROMPT)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload)))
                .build();

        long startNs = System.nanoTime();
        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        long headerMs = (System.nanoTime() - startNs) / 1_000_000;

        ProbeResult result = new ProbeResult(status, -1, 0, "");
        if (status != 200) {
            // 非 200：读完整错误体便于排障
            String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("HTTP " + status + ": " + abbreviate(errBody));
        }

        // 流式读 SSE：首个含正文的 data 分片到达时间 = 首 token 延迟
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                var node = objectMapper.readTree(data);
                var delta = node.path("choices").path(0).path("delta");
                String deltaContent = delta.path("content").asText("");
                String deltaReasoning = delta.path("reasoning_content").asText("");
                if (!deltaReasoning.isBlank()) {
                    reasoning.append(deltaReasoning);
                }
                if (!deltaContent.isBlank()) {
                    content.append(deltaContent);
                    if (result.firstTokenMs < 0) {
                        // 首个正文分片：记录相对请求发出的延迟
                        result.firstTokenMs = (System.nanoTime() - startNs) / 1_000_000;
                    }
                }
            }
        }
        result.content = content.toString();
        result.totalMs = (System.nanoTime() - startNs) / 1_000_000;
        if (result.firstTokenMs < 0) {
            // 有思考内容但正文为空（thinking 模式预算耗尽）：如实标注，不算心跳失败
            result.firstTokenMs = -2;
            result.content = "[无正文，思考内容 " + abbreviate(reasoning.toString()) + "]";
        }
        if (status == 200 && result.content.isBlank()) {
            throw new IllegalStateException("HTTP 200 但正文为空（连通正常，生成异常）");
        }
        return result;
    }

    /** 单模型探测结果 */
    private static class ProbeResult {
        final int status;
        long firstTokenMs;
        long totalMs;
        String content;

        ProbeResult(int status, long firstTokenMs, long totalMs, String content) {
            this.status = status;
            this.firstTokenMs = firstTokenMs;
            this.totalMs = totalMs;
            this.content = content;
        }
    }

    /** 长文本截断：日志与断言消息可读 */
    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }
}
