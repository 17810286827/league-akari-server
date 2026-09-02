package com.leagueakari.qqbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.PushProperties;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QqBotClient 单元测试：mock HttpClient 断言官方 OpenAPI 请求构造
 * （凭证换取 → 群文本消息），以及 token 缓存的"只换一次"契约
 */
@ExtendWith(MockitoExtension.class)
class QqBotClientTest {

    @Mock
    private CloseableHttpClient httpClient;

    private PushProperties pushProperties;

    private QqBotClient qqBotClient;

    /** 固定响应：QQ 官方 API 返回 200 + 空 JSON（发消息成功体） */
    private CloseableHttpResponse okResponse() {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getCode()).thenReturn(200);
        HttpEntity entity = new StringEntity("{}", StandardCharsets.UTF_8);
        when(resp.getEntity()).thenReturn(entity);
        return resp;
    }

    /** 固定响应：凭证接口返回 access_token */
    private CloseableHttpResponse tokenResponse(String token) {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getCode()).thenReturn(200);
        HttpEntity entity = new StringEntity("{\"access_token\":\"" + token + "\",\"expires_in\":7200}",
                StandardCharsets.UTF_8);
        when(resp.getEntity()).thenReturn(entity);
        return resp;
    }

    @BeforeEach
    void setUp() {
        // 配置齐备的默认实例：appId/clientSecret/群 openid 均有值
        pushProperties = new PushProperties();
        pushProperties.setAppId("app-1");
        pushProperties.setClientSecret("secret-1");
        pushProperties.setGroupOpenId("GROUP-1");
        qqBotClient = new QqBotClient(httpClient, new ObjectMapper(), pushProperties);
    }

    /**
     * 用例：发群文本消息时先换 token 再 POST 群消息，
     * 请求目标与消息体符合官方契约（msg_type=0 纯文本）
     */
    @Test
    void sendGroupTextMessage_postsToOfficialEndpointWithBearerToken() throws Exception {
        // 凭证接口 → token；群消息接口 → 成功（先构造响应再打桩，避免嵌套 stubbing）
        CloseableHttpResponse tokenResp = tokenResponse("tok-abc");
        CloseableHttpResponse msgResp = okResponse();
        when(httpClient.execute(any(HttpPost.class)))
                .thenReturn(tokenResp)
                .thenReturn(msgResp);

        qqBotClient.sendGroupTextMessage("GROUP-1", "本局战报……");

        // 两次请求目标：凭证接口 + 群消息接口（captor 按调用顺序收集，顺序即契约）
        var captor = org.mockito.ArgumentCaptor.forClass(HttpPost.class);
        verify(httpClient, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(0).getUri().toString())
                .isEqualTo("https://api.bot.qq.com/app/getAppAccessToken");
        assertThat(captor.getAllValues().get(1).getUri().toString())
                .isEqualTo("https://api.bot.qq.com/v2/groups/GROUP-1/messages");
        // 凭证体：appId + clientSecret
        String tokenBody = EntityUtils.toString(captor.getAllValues().get(0).getEntity(), StandardCharsets.UTF_8);
        assertThat(tokenBody).contains("\"appId\":\"app-1\"").contains("\"clientSecret\":\"secret-1\"");
        // 消息体：msg_type=0 纯文本 + Authorization Bearer
        HttpPost msgPost = captor.getAllValues().get(1);
        assertThat(msgPost.getFirstHeader("Authorization").getValue()).isEqualTo("Bearer tok-abc");
        assertThat(msgPost.getFirstHeader("Content-Type").getValue()).contains("application/json");
        String msgBody = EntityUtils.toString(msgPost.getEntity(), StandardCharsets.UTF_8);
        assertThat(msgBody).contains("\"msg_type\":0").contains("\"content\":\"本局战报……\"");
    }

    /**
     * 用例：token 缓存契约——同进程内连续发送只换取一次凭证，
     * 第二次直接复用缓存，不重复请求凭证接口
     */
    @Test
    void sendGroupTextMessage_reusesCachedToken() throws Exception {
        CloseableHttpResponse tokenResp = tokenResponse("tok-1");
        CloseableHttpResponse ok1 = okResponse();
        CloseableHttpResponse ok2 = okResponse();
        when(httpClient.execute(any(HttpPost.class)))
                .thenReturn(tokenResp)
                .thenReturn(ok1)
                .thenReturn(ok2);

        qqBotClient.sendGroupTextMessage("GROUP-1", "第一条");
        qqBotClient.sendGroupTextMessage("GROUP-1", "第二条");

        // 共 3 次 execute：1 次凭证 + 2 次消息（凭证未重复请求）
        verify(httpClient, times(3)).execute(any(HttpPost.class));
    }

    /**
     * 用例：凭证或群消息接口非 200 时抛 QqPushException，携带官方状态码
     */
    @Test
    void sendGroupTextMessage_throwsWhenApiReturnsError() throws Exception {
        // 凭证成功，群消息接口返回 401
        CloseableHttpResponse tokenResp = tokenResponse("tok-1");
        CloseableHttpResponse badResp = errorResponse(401, "{\"code\":401001,\"message\":\"invalid token\"}");
        when(httpClient.execute(any(HttpPost.class)))
                .thenReturn(tokenResp)
                .thenReturn(badResp);

        assertThatThrownBy(() -> qqBotClient.sendGroupTextMessage("GROUP-1", "hi"))
                .isInstanceOf(QqPushException.class)
                .hasMessageContaining("401");
    }

    /**
     * 用例：机器人凭证未配置时直接抛错，不发任何请求（防御误调用）
     */
    @Test
    void sendGroupTextMessage_failsFastWhenNotConfigured() throws Exception {
        PushProperties empty = new PushProperties();
        QqBotClient unconfigured = new QqBotClient(httpClient, new ObjectMapper(), empty);

        assertThatThrownBy(() -> unconfigured.sendGroupTextMessage("", "hi"))
                .isInstanceOf(QqPushException.class)
                .hasMessageContaining("未配置");
        // 未发起任何 HTTP 请求
        verify(httpClient, times(0)).execute(any(HttpPost.class));
    }

    /** 构造非 200 响应 */
    private CloseableHttpResponse errorResponse(int status, String body) {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getCode()).thenReturn(status);
        HttpEntity entity = new StringEntity(body, StandardCharsets.UTF_8);
        when(resp.getEntity()).thenReturn(entity);
        return resp;
    }
}
