package com.leagueakari.service;

import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChampionIconService 单元测试：
 * 验证头像下载（成功缓存 / HTTP 失败 / IO 异常 / 非法 ID）与并发防抖行为。
 * 网络层用 Mockito 桩替身，测试不依赖外网。
 */
class ChampionIconServiceTest {

    /** 1x1 红色 PNG 字节，用于桩替 HTTP 响应体 */
    private static byte[] pngBytes() throws IOException {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** 构造 200 + PNG 的假响应链 */
    private static CloseableHttpResponse okResponse(byte[] body) throws IOException {
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body));
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getCode()).thenReturn(200);
        when(resp.getEntity()).thenReturn(entity);
        return resp;
    }

    /** 用例：首次下载成功并缓存，二次取同一 id 不再发起网络请求 */
    @Test
    void loadIcon_success_servesFromCache() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        // 注意：响应桩须先构建完成再注册，避免 thenReturn 求值期间嵌套 stubbing
        CloseableHttpResponse resp = okResponse(pngBytes());
        when(client.execute(any(HttpUriRequest.class))).thenReturn(resp);
        ChampionIconService service = new ChampionIconService(client);

        BufferedImage first = service.loadIcon(64);
        BufferedImage second = service.loadIcon(64);

        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first);
        verify(client, times(1)).execute(any(HttpUriRequest.class));
    }

    /** 用例：HTTP 非 200 返回 null（渲染侧降级色块圆盘），且不抛异常 */
    @Test
    void loadIcon_httpError_returnsNull() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        CloseableHttpResponse resp = mock(CloseableHttpResponse.class);
        when(resp.getCode()).thenReturn(404);
        when(client.execute(any(HttpUriRequest.class))).thenReturn(resp);
        ChampionIconService service = new ChampionIconService(client);

        assertThat(service.loadIcon(9999)).isNull();
    }

    /** 用例：网络异常返回 null（CDN 不可达时图照常发出，头像降级） */
    @Test
    void loadIcon_ioError_returnsNull() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        when(client.execute(any(HttpUriRequest.class))).thenThrow(new IOException("boom"));
        ChampionIconService service = new ChampionIconService(client);

        assertThat(service.loadIcon(64)).isNull();
    }

    /** 用例：非法英雄 id（0/负）直接返回 null，不发网络请求 */
    @Test
    void loadIcon_invalidId_returnsNullWithoutRequest() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        ChampionIconService service = new ChampionIconService(client);

        assertThat(service.loadIcon(0)).isNull();
        assertThat(service.loadIcon(-3)).isNull();
        verify(client, Mockito.never()).execute(any(HttpUriRequest.class));
    }
}
