package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.RiotAccountDto;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RiotAccountClient 单元测试：
 * 搜索成功（含召唤师资料补充 + JVM 缓存命中不再调接口）、404 转业务异常、
 * 输入/配置校验（缺 #tag、无 API Key）、5xx 转服务不可用。
 * 通过 mock CloseableHttpClient 隔离外部 API
 */
class RiotAccountClientTest {

    private CloseableHttpClient httpClient;
    private RiotAccountClient client;

    /** 模拟 Riot 接口响应：状态码 + JSON 响应体，返回可被 thenReturn 按序使用的响应 */
    private CloseableHttpResponse mockResponse(int status, String body) throws Exception {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getCode()).thenReturn(status);
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);
        return response;
    }

    @BeforeEach
    void setUp() {
        httpClient = mock(CloseableHttpClient.class);
        client = new RiotAccountClient(
                "test-key",
                "https://asia.api.riotgames.com",
                "https://sea.api.riotgames.com",
                httpClient,
                new ObjectMapper());
    }

    @Test
    void searchSuccessFillsProfileAndCaches() throws Exception {
        // 第一次搜索会连续调用两次接口：Account-V1（账号）+ Summoner-V4（等级/头像），
        // 用 thenReturn 按调用顺序返回两个响应
        CloseableHttpResponse accountResp = mockResponse(200,
                "{\"puuid\":\"puuid-1\",\"gameName\":\"赌书\",\"tagLine\":\"iKun\"}");
        CloseableHttpResponse summonerResp = mockResponse(200,
                "{\"puuid\":\"puuid-1\",\"summonerLevel\":123,\"profileIconId\":456}");
        when(httpClient.execute(any())).thenReturn(accountResp, summonerResp);

        RiotAccountDto account = client.searchByRiotId("赌书#iKun");

        // 账号信息 + 补充的等级/头像都在第一次搜索内完成
        assertThat(account.getPuuid()).isEqualTo("puuid-1");
        assertThat(account.getSummonerLevel()).isEqualTo(123);
        assertThat(account.getProfileIconId()).isEqualTo(456);

        // 第二次搜索同昵称：命中 JVM 缓存，不再调用 Riot API
        RiotAccountDto cached = client.searchByRiotId("赌书#iKun");
        assertThat(cached.getSummonerLevel()).isEqualTo(123);
        // 共 2 次 HTTP 调用：Account + Summoner（第二次搜索命中缓存未新增调用）
        verify(httpClient, times(2)).execute(any());
    }

    @Test
    void searchNotFoundThrowsBusinessException() throws Exception {
        // Riot 返回 404：转业务异常，由全局处理器转 404
        // 先构建响应再 stub execute（避免在 thenReturn 参数内嵌套 stubbing）
        CloseableHttpResponse response = mockResponse(404, "");
        when(httpClient.execute(any())).thenReturn(response);
        assertThatThrownBy(() -> client.searchByRiotId("不存在#tag"))
                .isInstanceOf(RiotAccountNotFoundException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void searchServerErrorThrowsIllegalState() throws Exception {
        // Riot 返回 5xx：转服务不可用（全局处理器 503）
        CloseableHttpResponse response = mockResponse(500, "{}");
        when(httpClient.execute(any())).thenReturn(response);
        assertThatThrownBy(() -> client.searchByRiotId("玩家#tag"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    void invalidNameWithoutTagRejected() {
        // 昵称缺 #tag：参数错误
        assertThatThrownBy(() -> client.searchByRiotId("没有tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#tag");
    }

    @Test
    void missingApiKeyRejected() {
        // API Key 未配置：直接报错（全局处理器 503）
        RiotAccountClient noKey = new RiotAccountClient(
                "", "https://asia.api.riotgames.com", "https://sea.api.riotgames.com",
                httpClient, new ObjectMapper());
        assertThatThrownBy(() -> noKey.searchByRiotId("玩家#tag"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API Key");
    }
}
