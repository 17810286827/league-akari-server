package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostGameCommentService 单元测试：mock HttpClient 验证局后锐评的
 * 非流式调用、空正文自动重试（2 次）与 API Key 门控
 */
@ExtendWith(MockitoExtension.class)
class PostGameCommentServiceTest {

    @Mock
    private CloseableHttpClient httpClient;

    private PostGameCommentService service;

    @BeforeEach
    void setUp() {
        service = new PostGameCommentService(
                "https://opencode.ai/zen/go/v1", "test-key", "mimo-v2.5",
                "ai/post-game-prompt.md", 1.0, 1024, httpClient, new ObjectMapper());
    }

    /** 最小局后摘要：胜负、车队成员与焦点 */
    private Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("result", "胜利");
        s.put("score", Map.of("main", 32, "other", 18));
        s.put("teamName", "iKun");
        s.put("hero", Map.of("name", "赌书消得泼茶香", "kda", "12/3/7", "title", "MVP"));
        s.put("fleet", java.util.List.of(
                Map.of("name", "赌书消得泼茶香", "kda", "12/3/7", "title", "MVP"),
                Map.of("name", "手裂鬼子", "kda", "3/8/5")));
        return s;
    }

    private CloseableHttpResponse aiResponse(String content) {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getCode()).thenReturn(200);
        HttpEntity entity = mock(HttpEntity.class);
        try {
            when(entity.getContent()).thenAnswer(inv -> new ByteArrayInputStream(
                    ("{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(resp.getEntity()).thenReturn(entity);
        return resp;
    }

    private CloseableHttpResponse errorResponse(int status) {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getCode()).thenReturn(status);
        HttpEntity entity = mock(HttpEntity.class);
        try {
            when(entity.getContent()).thenAnswer(inv -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(resp.getEntity()).thenReturn(entity);
        return resp;
    }

    /** 用例：AI 正常返回正文 → 返回锐评文本 */
    @Test
    void generateComment_returnsAiContent() throws Exception {
        CloseableHttpResponse ok = aiResponse("这把养鱼人把对面野区当自己家");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(ok);

        String comment = service.generateComment(summary());

        assertThat(comment).contains("养鱼人");
        verify(httpClient, times(1)).execute(any(HttpPost.class));
    }

    /** 用例：第一次正文为空（推理预算耗尽）→ 自动重试第二次成功 */
    @Test
    void generateComment_retriesWhenContentEmpty() throws Exception {
        CloseableHttpResponse empty = aiResponse("");
        CloseableHttpResponse ok = aiResponse("输了不丢人，0/9 才丢人");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(empty, ok);

        String comment = service.generateComment(summary());

        assertThat(comment).contains("0/9");
        verify(httpClient, times(2)).execute(any(HttpPost.class));
    }

    /** 用例：两次都空正文 → 抛 IllegalStateException（由编排层降级为缺席提示） */
    @Test
    void generateComment_throwsWhenAlwaysEmpty() throws Exception {
        CloseableHttpResponse empty1 = aiResponse("");
        CloseableHttpResponse empty2 = aiResponse("");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(empty1, empty2);

        assertThatThrownBy(() -> service.generateComment(summary()))
                .isInstanceOf(IllegalStateException.class);
        verify(httpClient, times(2)).execute(any(HttpPost.class));
    }

    /** 用例：AI 接口非 200 → 抛异常（由编排层降级） */
    @Test
    void generateComment_throwsOnHttpError() throws Exception {
        CloseableHttpResponse bad = errorResponse(502);
        when(httpClient.execute(any(HttpPost.class))).thenReturn(bad);

        assertThatThrownBy(() -> service.generateComment(summary()))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 用例：API Key 未配置 → 快速失败，不发任何请求 */
    @Test
    void generateComment_failsFastWithoutApiKey() throws Exception {
        PostGameCommentService noKey = new PostGameCommentService(
                "https://opencode.ai/zen/go/v1", "", "mimo-v2.5",
                "ai/post-game-prompt.md", 1.0, 1024, httpClient, new ObjectMapper());

        assertThatThrownBy(() -> noKey.generateComment(summary()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API Key");
        verify(httpClient, times(0)).execute(any(HttpPost.class));
    }
}
