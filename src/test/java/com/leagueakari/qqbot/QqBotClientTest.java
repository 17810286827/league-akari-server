package com.leagueakari.qqbot;

import com.leagueakari.common.exception.QqPushException;
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
        // 消息体：msg_type=0 纯文本 + 鉴权头（官方 SDK 格式：QQBot + access_token + X-Union-Appid）
        HttpPost msgPost = captor.getAllValues().get(1);
        assertThat(msgPost.getFirstHeader("Authorization").getValue()).isEqualTo("QQBot tok-abc");
        assertThat(msgPost.getFirstHeader("X-Union-Appid").getValue()).isEqualTo("app-1");
        assertThat(msgPost.getFirstHeader("Content-Type").getValue()).contains("application/json");
        String msgBody = EntityUtils.toString(msgPost.getEntity(), StandardCharsets.UTF_8);
        assertThat(msgBody).contains("\"msg_type\":0").contains("\"content\":\"本局战报……\"");
    }

    /**
     * 用例：发群 Markdown 消息（msg_type=2 + markdown.content），
     * **加粗** 等富文本语法原样透传（群聊自定义 Markdown 官方全量开放，免模板）
     */
    @Test
    void sendGroupMarkdownMessage_postsMarkdownPayload() throws Exception {
        CloseableHttpResponse tokenResp = tokenResponse("tok-md");
        CloseableHttpResponse msgResp = okResponse();
        when(httpClient.execute(any(HttpPost.class)))
                .thenReturn(tokenResp)
                .thenReturn(msgResp);

        qqBotClient.sendGroupMarkdownMessage("GROUP-1", "本局 **战神** 是养鱼人，**战犯** 是夜雨听澜");

        var captor = org.mockito.ArgumentCaptor.forClass(HttpPost.class);
        verify(httpClient, times(2)).execute(captor.capture());
        HttpPost msgPost = captor.getAllValues().get(1);
        assertThat(msgPost.getUri().toString())
                .isEqualTo("https://api.bot.qq.com/v2/groups/GROUP-1/messages");
        assertThat(msgPost.getFirstHeader("Authorization").getValue()).isEqualTo("QQBot tok-md");
        String msgBody = EntityUtils.toString(msgPost.getEntity(), StandardCharsets.UTF_8);
        assertThat(msgBody).contains("\"msg_type\":2")
                .contains("\"markdown\":{\"content\":\"本局 **战神** 是养鱼人，**战犯** 是夜雨听澜\"}");
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

    /**
     * 用例：图片消息全链路——凭证 → 预上传 → 分片 PUT（预签名 URL）→ 分片确认 →
     * files 注册取 file_info → 富媒体消息（msg_type=7）。请求顺序即官方流程
     */
    @Test
    void sendGroupImageMessage_runsFullUploadFlow() throws Exception {
        // 六步响应依次就位（先构造再打桩，避免嵌套 stubbing）
        CloseableHttpResponse tokenResp = tokenResponse("tok-img");
        CloseableHttpResponse prepareResp = jsonResponse(200,
                "{\"data\":{\"upload_id\":\"up-1\",\"block_size\":1048576,"
                        + "\"parts\":[{\"index\":0,\"presigned_url\":\"https://presigned.example.com/part-1\"}]}}");
        CloseableHttpResponse putResp = statusOnlyResponse(200);
        CloseableHttpResponse finishResp = okResponse();
        CloseableHttpResponse filesResp = jsonResponse(200,
                "{\"data\":{\"file_info\":\"fi-1\",\"file_uuid\":\"fu-1\",\"ttl\":300}}");
        CloseableHttpResponse msgResp = okResponse();
        when(httpClient.execute(any(org.apache.hc.client5.http.classic.methods.HttpUriRequestBase.class)))
                .thenReturn(tokenResp, prepareResp, putResp, finishResp, filesResp, msgResp);

        qqBotClient.sendGroupImageMessage("GROUP-1", new byte[]{1, 2, 3});

        // 请求序列与目标：token → prepare → 预签名 PUT → finish → files → messages
        var captor = org.mockito.ArgumentCaptor.forClass(
                org.apache.hc.client5.http.classic.methods.HttpUriRequestBase.class);
        verify(httpClient, times(6)).execute(captor.capture());
        var urls = captor.getAllValues().stream().map(r -> {
            try {
                return r.getUri().toString();
            } catch (Exception e) {
                return "?";
            }
        }).toList();
        assertThat(urls.get(0)).isEqualTo("https://api.bot.qq.com/app/getAppAccessToken");
        assertThat(urls.get(1)).isEqualTo("https://api.bot.qq.com/v2/groups/GROUP-1/upload_prepare");
        assertThat(urls.get(2)).isEqualTo("https://presigned.example.com/part-1");
        assertThat(urls.get(3)).isEqualTo("https://api.bot.qq.com/v2/groups/GROUP-1/upload_part_finish");
        assertThat(urls.get(4)).isEqualTo("https://api.bot.qq.com/v2/groups/GROUP-1/files");
        assertThat(urls.get(5)).isEqualTo("https://api.bot.qq.com/v2/groups/GROUP-1/messages");
        // 预上传体含类型/大小（字符串）与摘要；单片确认含 part_index；
        // 合并体含 srv_send_msg；最终消息体为 msg_type=7 + file_info
        String prepareBody = EntityUtils.toString(captor.getAllValues().get(1).getEntity(),
                StandardCharsets.UTF_8);
        assertThat(prepareBody).contains("\"file_type\":1").contains("\"file_size\":\"3\"")
                .contains("\"file_name\":\"report.png\"");
        String finishBody = EntityUtils.toString(captor.getAllValues().get(3).getEntity(),
                StandardCharsets.UTF_8);
        assertThat(finishBody).contains("\"part_index\":0").contains("\"upload_id\":\"up-1\"");
        String filesBody = EntityUtils.toString(captor.getAllValues().get(4).getEntity(),
                StandardCharsets.UTF_8);
        assertThat(filesBody).contains("\"srv_send_msg\":false").contains("\"file_type\":1");
        String msgBody = EntityUtils.toString(captor.getAllValues().get(5).getEntity(),
                StandardCharsets.UTF_8);
        assertThat(msgBody).contains("\"msg_type\":7").contains("\"file_info\":\"fi-1\"");
    }

    /** 构造 JSON 响应 */
    private CloseableHttpResponse jsonResponse(int status, String body) {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getCode()).thenReturn(status);
        HttpEntity entity = new StringEntity(body, StandardCharsets.UTF_8);
        when(resp.getEntity()).thenReturn(entity);
        return resp;
    }

    /** 仅带状态码的响应（分片 PUT 只校验 200，不消费响应体） */
    private CloseableHttpResponse statusOnlyResponse(int status) {
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getCode()).thenReturn(status);
        return resp;
    }
}
