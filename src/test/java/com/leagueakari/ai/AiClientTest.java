package com.leagueakari.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.AiProperties;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AiClient 单元测试（公共 AI 调用组件，外部 I/O 接缝）：
 * 通过 mock CloseableHttpClient 隔离网络，验证对外契约——
 * 请求组装（payload 字段、Bearer/浏览器 UA 请求头）、HTTP 错误转 IllegalStateException、
 * 非流式空正文返回 null、流式 SSE 逐块回调（content/reasoning 分流、finish_reason 透传）。
 * 这些用例取代三个业务服务测试中原有的 payload 断言，是网关行为变化的回归网。
 */
class AiClientTest {

    /** 模拟推理模式 SSE 流：思维链（reasoning_content）与正文（content）交替，最后 [DONE] */
    private static final String SSE_STREAM = """
            data: {"choices":[{"delta":{"reasoning_content":"正在分析"}}]}

            data: {"choices":[{"delta":{"content":"你好"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"继续推理"}}]}

            data: {"choices":[{"delta":{"content":"，世界"}}]}

            data: [DONE]

            """;

    /** 被预算截断的流：最后一个 chunk 携带 finish_reason=length */
    private static final String SSE_STREAM_TRUNCATED = """
            data: {"choices":[{"delta":{"content":"写到一半"}}]}

            data: {"choices":[{"delta":{"content":"戛然而止"},"finish_reason":"length"}]}

            data: [DONE]

            """;

    private CloseableHttpClient httpClient;
    private AiClient client;

    @BeforeEach
    void setUp() {
        httpClient = mock(CloseableHttpClient.class);
        // 连接级配置走 AiProperties（yaml 唯一真值）；采样参数由各用例的 request 显式传入
        AiProperties props = new AiProperties();
        props.setBaseUrl("https://ai.test/v1");
        props.setApiKey("test-key");
        client = new AiClient(props, httpClient, new ObjectMapper());
    }

    /** 构造非流式场景的请求参数（无 penalty、thinking=false：周报/局后锐评同款） */
    private AiCompletionRequest plainRequest() {
        return new AiCompletionRequest("test-model", 1.0, null, null, 512, false);
    }

    /** 构造单局分析场景的请求参数（带 penalty、thinking 可控） */
    private AiCompletionRequest analysisRequest(boolean thinking) {
        return new AiCompletionRequest("test-model", 0.7, 0.6, 0.3, 2048, thinking);
    }

    /** 模拟 AI 接口响应：状态码 + 响应体（JSON 或 SSE 流） */
    private void mockResponse(int status, String body) throws Exception {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getCode()).thenReturn(status);
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenAnswer(inv ->
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);
        when(httpClient.execute(any(HttpPost.class))).thenReturn(response);
    }

    /** 捕获 AiClient 实际发出的 POST 请求（断言 URI/请求头/请求体用） */
    private HttpPost capturedPost() throws Exception {
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);
        org.mockito.Mockito.verify(httpClient).execute(captor.capture());
        return captor.getValue();
    }

    // ==================== 非流式 call ====================

    /** 用例（T7 重载）：空正文自动重试——首次空、第二次有正文 → 返回正文且共发 2 次请求 */
    @Test
    void callWithRetry_retriesOnEmptyContent() throws Exception {
        CloseableHttpResponse empty = mockResponseCapture(200, emptyBody());
        CloseableHttpResponse ok = mockResponseCapture(200, contentBody("重试后的正文"));
        when(httpClient.execute(any(HttpPost.class))).thenReturn(empty).thenReturn(ok);

        String result = client.call(plainRequest(), "system", "user", "ctx", 2);

        org.assertj.core.api.Assertions.assertThat(result).contains("重试后的正文");
        org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(2)).execute(any(HttpPost.class));
    }

    /** 用例（T7 重载）：重试耗尽仍空 → 返回 null（降级语义由调用方决定） */
    @Test
    void callWithRetry_returnsNullWhenAlwaysEmpty() throws Exception {
        when(httpClient.execute(any(HttpPost.class))).thenAnswer(inv -> {
            try {
                return mockResponseCapture(200, emptyBody());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        String result = client.call(plainRequest(), "system", "user", "ctx", 2);

        org.assertj.core.api.Assertions.assertThat(result).isNull();
        org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(2)).execute(any(HttpPost.class));
    }

    /** 用例（T7 重载）：API Key 未配置 → 前置校验直接抛，零请求发出 */
    @Test
    void callWithRetry_failsFastWithoutApiKey() throws Exception {
        AiProperties noKey = new AiProperties();
        noKey.setBaseUrl("https://ai.test/v1");
        noKey.setApiKey("");
        AiClient keyless = new AiClient(noKey, httpClient, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> keyless.call(plainRequest(), "system", "user", "ctx", 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API Key");
        org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.never()).execute(any(HttpPost.class));
    }

    /** 构造独立的 mock 响应实例（链式 thenReturn 需要两个不同实例） */
    private CloseableHttpResponse mockResponseCapture(int status, String body) throws Exception {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getCode()).thenReturn(status);
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenAnswer(inv ->
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);
        return response;
    }

    /** 空正文响应体（choices[0].content 为空串） */
    private String emptyBody() {
        return "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}";
    }

    /** 正常正文响应体 */
    private String contentBody(String content) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"" + content + "\"}}]}";
    }


    /** 用例：请求打到 baseUrl + /chat/completions，带 Bearer 鉴权与浏览器 UA（Cloudflare 防护） */
    @Test
    void call_sendsAuthAndBrowserUaHeaders() throws Exception {
        mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"正文\"}}]}");

        client.call(plainRequest(), "系统提示", "用户内容", "test");

        HttpPost post = capturedPost();
        assertThat(post.getUri().toString()).isEqualTo("https://ai.test/v1/chat/completions");
        assertThat(post.getHeader("Authorization").getValue()).isEqualTo("Bearer test-key");
        // 无 UA/程序化请求会被 Cloudflare 403/503 拦截，必须是浏览器 UA
        assertThat(post.getHeader("User-Agent").getValue()).startsWith("Mozilla/5.0");
    }

    /** 用例：非流式 payload 携带 model/messages/temperature/max_tokens，penalty 为 null 时不传 */
    @Test
    void call_payloadCarriesCoreParamsAndOmitsNullPenalties() throws Exception {
        mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"正文\"}}]}");

        client.call(plainRequest(), "系统提示", "用户内容", "test");

        String body = EntityUtils.toString(capturedPost().getEntity());
        assertThat(body).contains("\"model\":\"test-model\"")
                .contains("\"stream\":false")
                .contains("\"temperature\":1.0")
                .contains("\"max_tokens\":512")
                .contains("\"role\":\"system\"").contains("系统提示")
                .contains("\"role\":\"user\"").contains("用户内容");
        // 周报/局后场景不传 penalty（保持既有 payload 行为，避免输出风格漂移）
        assertThat(body).doesNotContain("frequency_penalty").doesNotContain("presence_penalty");
    }

    /** 用例：单局分析场景传入 penalty 时随请求发送 */
    @Test
    void call_payloadCarriesPenaltiesWhenPresent() throws Exception {
        mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"正文\"}}]}");

        client.call(analysisRequest(false), "系统提示", "用户内容", "test");

        String body = EntityUtils.toString(capturedPost().getEntity());
        assertThat(body).contains("\"frequency_penalty\":0.6").contains("\"presence_penalty\":0.3");
    }

    /** 用例：thinking=false 时写 chat_template_kwargs.thinking=false（DeepSeek 原生参数直出正文） */
    @Test
    void call_disablesThinkingWhenFalse() throws Exception {
        mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"正文\"}}]}");

        client.call(plainRequest(), "系统提示", "用户内容", "test");

        String body = EntityUtils.toString(capturedPost().getEntity());
        assertThat(body).contains("\"chat_template_kwargs\"").contains("\"thinking\":false");
    }

    /** 用例：thinking=true 时不写 chat_template_kwargs（保持模型默认推理模式） */
    @Test
    void call_omitsThinkingKwargsWhenTrue() throws Exception {
        mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"正文\"}}]}");

        client.call(analysisRequest(true), "系统提示", "用户内容", "test");

        assertThat(EntityUtils.toString(capturedPost().getEntity()))
                .doesNotContain("chat_template_kwargs");
    }

    /** 用例：200 响应 → 透出 choices[0].message.content */
    @Test
    void call_returnsContent() throws Exception {
        mockResponse(200, "{\"choices\":[{\"message\":{\"content\":\"本周赌书封神\"}}]}");

        String content = client.call(plainRequest(), "系统提示", "用户内容", "test");

        assertThat(content).isEqualTo("本周赌书封神");
    }

    /** 用例：正文为空（推理预算耗尽）→ 返回 null，由调用方决定重试 */
    @Test
    void call_returnsNullOnEmptyContent() throws Exception {
        mockResponse(200, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}");

        assertThat(client.call(plainRequest(), "系统提示", "用户内容", "test")).isNull();
    }

    /** 用例：非 200 → IllegalStateException（携带状态码，提示重试） */
    @Test
    void call_throwsOnNon200() throws Exception {
        mockResponse(502, "{\"error\":\"bad gateway\"}");

        assertThatThrownBy(() -> client.call(plainRequest(), "系统提示", "用户内容", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("502");
    }

    /** 用例：网络异常（超时/断连）→ IllegalStateException（提示检查网络与 Key） */
    @Test
    void call_throwsOnNetworkError() throws Exception {
        when(httpClient.execute(any(HttpPost.class))).thenThrow(new IOException("connect timed out"));

        assertThatThrownBy(() -> client.call(plainRequest(), "系统提示", "用户内容", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("网络与 API Key");
    }

    // ==================== 流式 callStream ====================

    /** 用例：SSE 流逐块回调——思维链交 onReasoning、正文交 onContent，顺序与到达顺序一致 */
    @Test
    void callStream_deliversReasoningAndContentInOrder() throws Exception {
        mockResponse(200, SSE_STREAM);
        List<String> contents = new ArrayList<>();
        List<String> reasonings = new ArrayList<>();

        String finishReason = client.callStream(analysisRequest(true), "系统提示", "用户内容",
                new AiStreamHandler() {
                    @Override
                    public void onContent(String chunk) {
                        contents.add(chunk);
                    }

                    @Override
                    public void onReasoning(String chunk) {
                        reasonings.add(chunk);
                    }
                }, "test");

        assertThat(contents).containsExactly("你好", "，世界");
        assertThat(reasonings).containsExactly("正在分析", "继续推理");
        // 自然结束（未见 finish_reason）：返回 null，"是否为空"由调用方判定
        assertThat(finishReason).isNull();
    }

    /** 用例：流式 payload 与非流式的差异——stream=true；采样参数同样透传 */
    @Test
    void callStream_payloadIsStreaming() throws Exception {
        mockResponse(200, SSE_STREAM);

        client.callStream(analysisRequest(false), "系统提示", "用户内容",
                chunk -> {
                }, "test");

        String body = EntityUtils.toString(capturedPost().getEntity());
        assertThat(body).contains("\"stream\":true")
                .contains("\"temperature\":0.7")
                .contains("\"max_tokens\":2048")
                .contains("\"chat_template_kwargs\"").contains("\"thinking\":false");
    }

    /** 用例：finish_reason 在最后一个 chunk 携带（length=预算截断）→ 透传给调用方 */
    @Test
    void callStream_returnsFinishReason() throws Exception {
        mockResponse(200, SSE_STREAM_TRUNCATED);

        String finishReason = client.callStream(analysisRequest(false), "系统提示", "用户内容",
                chunk -> {
                }, "test");

        assertThat(finishReason).isEqualTo("length");
    }

    /** 用例：流式非 200 → IllegalStateException（携带状态码） */
    @Test
    void callStream_throwsOnNon200() throws Exception {
        mockResponse(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client.callStream(analysisRequest(false), "系统提示", "用户内容",
                chunk -> {
                }, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    /** 用例：SSE 数据不是合法 JSON → IllegalStateException（提示数据异常） */
    @Test
    void callStream_throwsOnGarbledData() throws Exception {
        mockResponse(200, "data: {不是JSON");

        assertThatThrownBy(() -> client.callStream(analysisRequest(false), "系统提示", "用户内容",
                chunk -> {
                }, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据异常");
    }

    /** 用例：流无任何数据块（服务端提前断开）→ 不回调、返回 null finishReason，由调用方判空抛错 */
    @Test
    void callStream_emptyStreamReturnsNullWithoutCallbacks() throws Exception {
        mockResponse(200, "");

        List<String> contents = new ArrayList<>();
        String finishReason = client.callStream(analysisRequest(false), "系统提示", "用户内容",
                contents::add, "test");

        assertThat(contents).isEmpty();
        assertThat(finishReason).isNull();
    }

    /**
     * 用例：回调内抛出的运行时异常原样穿透 callStream（不转译、不吞掉）——
     * 调用方（单局分析）依赖该语义在客户端断开时立即终止上游消费（见 docs/adr/0005）
     */
    @Test
    void callStream_propagatesCallbackRuntimeException() throws Exception {
        mockResponse(200, SSE_STREAM);
        // 专用异常类型：区别于 AiClient 自身抛出的 IllegalStateException，验证不发生转译
        class StopConsumptionException extends RuntimeException {
            StopConsumptionException() {
                super("stop");
            }
        }

        assertThatThrownBy(() -> client.callStream(analysisRequest(true), "系统提示", "用户内容",
                chunk -> {
                    throw new StopConsumptionException();
                }, "test"))
                .isInstanceOf(StopConsumptionException.class)
                .hasMessage("stop");
    }
}
