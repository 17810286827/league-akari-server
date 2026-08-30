package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.WeeklyReportResponse;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WeeklyAiCommentService 单元测试（外部 I/O 接缝）：
 * API Key 校验、AI 返回内容透出、请求载荷携带周报摘要、非 200 转状态异常、
 * 同一周结果缓存（重复生成不重复计费）。
 */
class WeeklyAiCommentServiceTest {

    private CloseableHttpClient httpClient;
    private WeeklyAiCommentService service;

    /** 构造指定 Key 的被测服务（其余配置固定） */
    private WeeklyAiCommentService serviceWithKey(String apiKey) {
        return new WeeklyAiCommentService(
                "https://ai.example.com/v1", apiKey, "test-model",
                "ai/weekly-prompt.md", 1.0, 512,
                httpClient, new ObjectMapper());
    }

    /** 模拟 AI 接口响应：状态码 + JSON 体（getContent 每次返回新流，支持多次读取/缓存测试） */
    private CloseableHttpResponse mockResponse(int status, String body) throws Exception {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getCode()).thenReturn(status);
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenAnswer(inv ->
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);
        return response;
    }

    /** 构造最小周报：仅含 AI 摘要会用到的字段 */
    private WeeklyReportResponse report(String weekLabel) {
        return WeeklyReportResponse.builder()
                .weekLabel(weekLabel)
                .overview(WeeklyReportResponse.Overview.builder()
                        .gameCount(3).winCount(2).lossCount(1)
                        .busiestDay("2026-08-26")
                        .activeMembers(List.of("赌书消得泼茶香#iKun", "手裂鬼子#tw2"))
                        .build())
                .mvpBoard(List.of(WeeklyReportResponse.BoardEntry.builder()
                        .riotId("赌书消得泼茶香#iKun").value(2.0).detail("MVP×1 SVP×1").build()))
                .criminalBoard(List.of(WeeklyReportResponse.BoardEntry.builder()
                        .riotId("手裂鬼子#tw2").value(4.0).detail("2场").build()))
                .build();
    }

    @BeforeEach
    void setUp() {
        httpClient = mock(CloseableHttpClient.class);
        service = serviceWithKey("test-key");
    }

    /** 用例：API Key 未配置时抛状态异常（调用方降级为 null） */
    @Test
    void generateComment_throwsWhenKeyMissing() {
        WeeklyAiCommentService noKey = serviceWithKey("");

        assertThatThrownBy(() -> noKey.generateComment(report("2026-08-24 ~ 2026-08-30")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API Key 未配置");
    }

    /** 用例：AI 200 响应 → 透出 message.content；请求体携带周报摘要 */
    @Test
    void generateComment_returnsAiContent() throws Exception {
        CloseableHttpResponse ok = mockResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"本周赌书封神，鬼子战犯实锤\"}}]}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(ok);

        String comment = service.generateComment(report("2026-08-24 ~ 2026-08-30"));

        assertThat(comment).isEqualTo("本周赌书封神，鬼子战犯实锤");
        // 请求载荷校验：打到 chat/completions，user 消息里带上周报摘要与榜单
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);
        verify(httpClient).execute(captor.capture());
        assertThat(captor.getValue().getUri().getPath()).endsWith("/chat/completions");
        String body = new String(captor.getValue().getEntity().getContent().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(body).contains("2026-08-24 ~ 2026-08-30").contains("MVP").contains("战犯");
    }

    /** 用例：AI 非 200 → 状态异常（提示重试） */
    @Test
    void generateComment_throwsOnNon200() throws Exception {
        CloseableHttpResponse bad = mockResponse(502, "{\"error\":\"bad gateway\"}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(bad);

        assertThatThrownBy(() -> service.generateComment(report("2026-08-24 ~ 2026-08-30")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("502");
    }

    /** 用例：同一周缓存命中——第二次生成不再发请求（避免重复计费） */
    @Test
    void generateComment_cachesPerWeek() throws Exception {
        CloseableHttpResponse ok = mockResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"锐评\"}}]}");
        when(httpClient.execute(any(HttpPost.class))).thenReturn(ok);

        service.generateComment(report("2026-08-24 ~ 2026-08-30"));
        service.generateComment(report("2026-08-24 ~ 2026-08-30"));

        verify(httpClient, times(1)).execute(any(HttpPost.class));
        // 换一周则重新生成
        service.generateComment(report("2026-08-31 ~ 2026-09-06"));
        verify(httpClient, times(2)).execute(any(HttpPost.class));
    }
}
