package com.leagueakari.riot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RiotHttpClient 单元测试（统一出口的三合一契约，mock HttpClient）：
 * 1. X-Riot-Token 头恒携带；2. 限流器在每次出网前 acquire（无差别挂载）；
 * 3. 状态码语义翻译：404 → RiotAccountNotFoundException、429 → 等待重试一次、
 * 其他非 2xx → 带 body 的 IllegalStateException。
 */
@ExtendWith(MockitoExtension.class)
class RiotHttpClientTest {

    @Mock
    private CloseableHttpClient httpClient;

    @Mock
    private RiotRateLimiter rateLimiter;

    private RiotHttpClient client;

    @BeforeEach
    void setUp() {
        client = new RiotHttpClient("test-api-key", httpClient, new ObjectMapper(), rateLimiter);
    }

    /** 构造 mock 响应（close() 显式放行——try-with-resources 会调用） */
    private CloseableHttpResponse response(int status, String body) throws Exception {
        CloseableHttpResponse resp = org.mockito.Mockito.mock(CloseableHttpResponse.class,
                org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.RETURNS_DEEP_STUBS));
        when(resp.getCode()).thenReturn(status);
        HttpEntity entity = org.mockito.Mockito.mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8)));
        // EntityUtils.toString 走 InputStream 读取（不依赖 mocked 字符串返回）
        when(resp.getEntity()).thenReturn(entity);
        org.mockito.Mockito.doNothing().when(resp).close();
        return resp;
    }

    @Test
    @DisplayName("正常 2xx：携带 X-Riot-Token 头、限流 acquire、返回响应体")
    void get_sendsTokenAcquiresLimitAndReturnsBody() throws Exception {
        CloseableHttpResponse ok = response(200, "{\"puuid\":\"abc\"}");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(ok);

        String body = client.get(URI.create("https://example.com/accounts"));

        assertThat(body).contains("abc");
        // 限流契约：每次出网前 acquire（无差别挂载）
        verify(rateLimiter, times(1)).acquire();
        // 请求头契约：X-Riot-Token 恒携带
        ArgumentCaptor<HttpGet> captor = ArgumentCaptor.forClass(HttpGet.class);
        verify(httpClient).execute(captor.capture());
        assertThat(captor.getValue().getFirstHeader("X-Riot-Token").getValue()).isEqualTo("test-api-key");
    }

    @Test
    @DisplayName("404：抛 RiotAccountNotFoundException（业务异常，全局处理器转 404）")
    void get_404ThrowsAccountNotFound() throws Exception {
        CloseableHttpResponse notFound = response(404, "");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(notFound);

        assertThatThrownBy(() -> client.get(URI.create("https://example.com/x")))
                .isInstanceOf(RiotAccountNotFoundException.class);
    }

    @Test
    @DisplayName("429：等待 Retry-After 后重试一次，重试成功返回正常响应体")
    void get_429RetriesOnceThenSucceeds() throws Exception {
        CloseableHttpResponse limited = response(429, "");
        CloseableHttpResponse ok = response(200, "ok-after-retry");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(limited).thenReturn(ok);

        String body = client.get(URI.create("https://example.com/y"));

        // 重试契约：429 后发起第二次请求，成功返回
        assertThat(body).isEqualTo("ok-after-retry");
        verify(httpClient, times(2)).execute(any(HttpGet.class));
    }

    @Test
    @DisplayName("429 重试仍 429：抛限流异常（不再无限重试）")
    void get_429TwiceThrows() throws Exception {
        CloseableHttpResponse limited = response(429, "");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(limited);

        assertThatThrownBy(() -> client.get(URI.create("https://example.com/z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("429");
        verify(httpClient, times(2)).execute(any(HttpGet.class));
    }

    @Test
    @DisplayName("其他非 2xx（如 403）：抛带状态码的 IllegalStateException")
    void get_otherNon2xxThrowsIllegalState() throws Exception {
        CloseableHttpResponse forbidden = response(403, "forbidden");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(forbidden);

        assertThatThrownBy(() -> client.get(URI.create("https://example.com/w")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403");
    }
}
