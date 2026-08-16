package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GameDataService 单元测试：
 * 英雄/装备映射 JSON 解析（数组与 {data:[...]} 两种结构）、zh_cn 失败降级 default、
 * 全部失败回退 ID 字符串、缓存命中不重复拉取。
 * 通过 mock CloseableHttpClient 隔离 CDN 网络
 */
class GameDataServiceTest {

    private CloseableHttpClient httpClient;
    private GameDataService service;

    @BeforeEach
    void setUp() {
        httpClient = mock(CloseableHttpClient.class);
        service = new GameDataService(httpClient, new ObjectMapper());
    }

    /** 模拟 CDN 响应：状态码 + JSON 响应体 */
    private CloseableHttpResponse mockResponse(int status, String body) throws Exception {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getCode()).thenReturn(status);
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);
        return response;
    }

    @Test
    void championNameParsesZhCnArray() throws Exception {
        // zh_cn 返回数组 [{id,name}]：解析出中文名；负 ID（-1"无"）被过滤
        // 先构建响应再 stub execute（避免在 thenReturn 参数内嵌套 stubbing）
        CloseableHttpResponse resp = mockResponse(200,
                "[{\"id\":103,\"name\":\"阿狸\"},{\"id\":157,\"name\":\"亚索\"},{\"id\":-1,\"name\":\"无\"}]");
        when(httpClient.execute(any())).thenReturn(resp);

        assertThat(service.championName(103)).isEqualTo("阿狸");
        assertThat(service.championName(157)).isEqualTo("亚索");
        // 未知 ID 回退 ID 字符串（模型按提示词 ID 对照表兜底）
        assertThat(service.championName(9999)).isEqualTo("9999");
        // 缓存命中：再次调用不重新拉取
        assertThat(service.championName(103)).isEqualTo("阿狸");
        verify(httpClient, times(1)).execute(any());
    }

    @Test
    void itemNameParsesItems() throws Exception {
        CloseableHttpResponse resp = mockResponse(200,
                "[{\"id\":6672,\"name\":\"收集者\"},{\"id\":6609,\"name\":\"巨蛇之牙\"}]");
        when(httpClient.execute(any())).thenReturn(resp);

        assertThat(service.itemName(6672)).isEqualTo("收集者");
        assertThat(service.itemName(6609)).isEqualTo("巨蛇之牙");
        // 装备与英雄映射相互独立（互不触发对方的加载）
        verify(httpClient, times(1)).execute(any());
    }

    @Test
    void fallsBackToDefaultLocale() throws Exception {
        // zh_cn 404 → 降级 default（第二次 execute 返回英文名）；
        // 先构建两个响应再 thenReturn（避免嵌套 stubbing）
        CloseableHttpResponse zhCnResp = mockResponse(404, "");
        CloseableHttpResponse defaultResp = mockResponse(200, "[{\"id\":103,\"name\":\"Ahri\"}]");
        when(httpClient.execute(any())).thenReturn(zhCnResp, defaultResp);

        assertThat(service.championName(103)).isEqualTo("Ahri");
        verify(httpClient, times(2)).execute(any());
    }

    @Test
    void fallsBackToIdWhenAllLocalesFail() throws Exception {
        // 网络异常：回退 ID 字符串，不抛异常（AI 分析主流程不受影响）
        when(httpClient.execute(any())).thenThrow(new IOException("network down"));

        assertThat(service.championName(103)).isEqualTo("103");
        assertThat(service.itemName(6672)).isEqualTo("6672");
    }

    @Test
    void supportsWrappedDataObject() throws Exception {
        // 兼容 {data: [...]} 包装结构（防御数据源格式变更）
        CloseableHttpResponse resp = mockResponse(200,
                "{\"data\":[{\"id\":103,\"name\":\"阿狸\"}]}");
        when(httpClient.execute(any())).thenReturn(resp);

        assertThat(service.championName(103)).isEqualTo("阿狸");
    }
}
