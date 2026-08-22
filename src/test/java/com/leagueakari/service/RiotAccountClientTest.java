package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.RiotAccountDto;
import com.leagueakari.entity.RiotAccount;
import com.leagueakari.mapper.RiotAccountMapper;
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
 * 查库命中直接返回（零 Riot API 调用）、库未命中调 API 并按 puuid 回写、
 * 改名场景走 update 不重复插行、404 转业务异常、
 * 输入/配置校验（缺 #tag、无 API Key 且库未命中）、5xx 转服务不可用。
 * 通过 mock CloseableHttpClient 与 RiotAccountMapper 隔离外部依赖
 */
class RiotAccountClientTest {

    private CloseableHttpClient httpClient;
    private RiotAccountMapper riotAccountMapper;
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

    /** 构造一条已入库的账号记录（模拟查库命中场景） */
    private RiotAccount mockStoredAccount() {
        RiotAccount stored = new RiotAccount();
        stored.setId(1L);
        stored.setPuuid("puuid-1");
        stored.setGameName("赌书");
        stored.setTagLine("iKun");
        stored.setSummonerLevel(123);
        stored.setProfileIconId(456);
        return stored;
    }

    @BeforeEach
    void setUp() {
        httpClient = mock(CloseableHttpClient.class);
        riotAccountMapper = mock(RiotAccountMapper.class);
        // 默认查库未命中（selectOne 返回 null），需要命中场景的测试单独 stub
        when(riotAccountMapper.selectOne(any())).thenReturn(null);
        client = new RiotAccountClient(
                "test-key",
                "https://asia.api.riotgames.com",
                "https://sea.api.riotgames.com",
                httpClient,
                new ObjectMapper(),
                riotAccountMapper);
    }

    @Test
    void searchDbHitReturnsWithoutApiCall() throws Exception {
        // 库命中：selectOne 返回已入库记录，不发起任何 HTTP 调用（甚至不需要 API Key）
        when(riotAccountMapper.selectOne(any())).thenReturn(mockStoredAccount());

        RiotAccountDto account = client.searchByRiotId("赌书#iKun");

        // 全量信息来自库：puuid + 名字 + 等级 + 头像
        assertThat(account.getPuuid()).isEqualTo("puuid-1");
        assertThat(account.getGameName()).isEqualTo("赌书");
        assertThat(account.getTagLine()).isEqualTo("iKun");
        assertThat(account.getSummonerLevel()).isEqualTo(123);
        assertThat(account.getProfileIconId()).isEqualTo(456);
        // 零 Riot API 调用：持久化缓存的核心价值
        verify(httpClient, times(0)).execute(any());
    }

    @Test
    void searchDbMissCallsApiAndPersistsResult() throws Exception {
        // 第一次查库未命中 → 调 Account-V1 + Summoner-V4 两个接口
        CloseableHttpResponse accountResp = mockResponse(200,
                "{\"puuid\":\"puuid-1\",\"gameName\":\"赌书\",\"tagLine\":\"iKun\"}");
        CloseableHttpResponse summonerResp = mockResponse(200,
                "{\"puuid\":\"puuid-1\",\"summonerLevel\":123,\"profileIconId\":456}");
        when(httpClient.execute(any())).thenReturn(accountResp, summonerResp);
        // 回写路径：该 puuid 首次入库 → selectCount 返回 0 → 走 insert
        when(riotAccountMapper.selectCount(any())).thenReturn(0L);

        RiotAccountDto account = client.searchByRiotId("赌书#iKun");

        assertThat(account.getPuuid()).isEqualTo("puuid-1");
        assertThat(account.getSummonerLevel()).isEqualTo(123);
        // API 结果已按 puuid 回写入库
        verify(riotAccountMapper).insert(any(RiotAccount.class));

        // 第二次搜索同昵称：查库命中 → 不再调 Riot API
        when(riotAccountMapper.selectOne(any())).thenReturn(mockStoredAccount());
        RiotAccountDto cached = client.searchByRiotId("赌书#iKun");
        assertThat(cached.getSummonerLevel()).isEqualTo(123);
        // 仍只有首次的 2 次 HTTP 调用（Account + Summoner）
        verify(httpClient, times(2)).execute(any());
    }

    @Test
    void searchDbMissExistingPuuidUpdatesRecord() throws Exception {
        // 改名场景：API 返回的 puuid 已存在库里 → 走 update 而不是 insert
        CloseableHttpResponse accountResp = mockResponse(200,
                "{\"puuid\":\"puuid-1\",\"gameName\":\"新名字\",\"tagLine\":\"iKun\"}");
        CloseableHttpResponse summonerResp = mockResponse(200,
                "{\"puuid\":\"puuid-1\",\"summonerLevel\":130,\"profileIconId\":456}");
        when(httpClient.execute(any())).thenReturn(accountResp, summonerResp);
        when(riotAccountMapper.selectCount(any())).thenReturn(1L);

        client.searchByRiotId("新名字#iKun");

        // 已存在的 puuid：更新记录（名字/等级随之刷新），不产生第二行
        verify(riotAccountMapper).update(any(RiotAccount.class), any());
        verify(riotAccountMapper, times(0)).insert(any(RiotAccount.class));
    }

    @Test
    void searchNotFoundThrowsBusinessException() throws Exception {
        // Riot 返回 404：转业务异常，由全局处理器转 404
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
        // 昵称缺 #tag：参数错误（不触达查库与 API）
        assertThatThrownBy(() -> client.searchByRiotId("没有tag"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#tag");
    }

    @Test
    void missingApiKeyRejectedWhenDbMiss() {
        // API Key 未配置且库未命中：无法调 Riot API，直接报错（库命中场景不受影响）
        RiotAccountClient noKey = new RiotAccountClient(
                "", "https://asia.api.riotgames.com", "https://sea.api.riotgames.com",
                httpClient, new ObjectMapper(), riotAccountMapper);
        assertThatThrownBy(() -> noKey.searchByRiotId("玩家#tag"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API Key");
    }
}
