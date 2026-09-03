package com.leagueakari.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 英雄头像服务：拉取 CommunityDragon 官方头像（与前端 icon-url.ts 同一数据源），
 * 供战报图渲染器绘制真实英雄头像。
 * <p>图源：{@code …/default/v1/champion-icons/{championId}.png}（champion-icons 目录内为
 * 116px 方形原图，渲染时圆形裁切缩放）。内存缓存成功后不再回源；加载失败返回 null，
 * 由渲染器降级为色块圆盘（图仍完整可发），CDN 恢复后自动重试成功。
 * 同一 id 并发未命中时仅一个线程下载，其余本轮降级（避免首图渲染打爆 CDN）。</p>
 */
@Slf4j
@Service
public class ChampionIconService {

    /** CommunityDragon 头像目录（与 GameDataService 同一根地址，champion-icons 无中文 locale 区分） */
    private static final String ICON_URL_PATTERN =
            "https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/champion-icons/%d.png";

    private final CloseableHttpClient httpClient;

    /** 头像缓存：championId → 图（成功下载后驻留，英雄头像几乎不变） */
    private final Map<Integer, BufferedImage> cache = new ConcurrentHashMap<>();

    /** 加载中集合：double-check 防并发重复下载同一头像 */
    private final Set<Integer> loading = ConcurrentHashMap.newKeySet();

    /** 已告警失败集合：同一 id 只 warn 一次，避免 CDN 故障时每局刷屏 */
    private final Set<Integer> failedWarned = ConcurrentHashMap.newKeySet();

    /**
     * 构造注入 HTTP 客户端（与 GameDataService 共用 HttpClientConfig 的 bean）
     */
    public ChampionIconService(CloseableHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 取英雄头像图：缓存命中直接返回；未命中同步下载一次并缓存。
     *
     * @param championId 英雄 ID
     * @return 头像 BufferedImage；下载失败/非法 ID 返回 null（调用方降级绘制）
     */
    public BufferedImage loadIcon(int championId) {
        if (championId <= 0) {
            return null;
        }
        BufferedImage hit = cache.get(championId);
        if (hit != null) {
            return hit;
        }
        // 已有线程在下载同一头像：本轮先降级，避免同步阻塞与重复请求
        if (!loading.add(championId)) {
            return null;
        }
        try {
            BufferedImage icon = fetch(championId);
            if (icon == null) {
                return null;
            }
            cache.put(championId, icon);
            failedWarned.remove(championId);
            log.info("Champion icon cached: id={}, size={}x{}", championId, icon.getWidth(), icon.getHeight());
            return icon;
        } finally {
            loading.remove(championId);
        }
    }

    /**
     * 下载单张头像（子类可覆写用于测试桩替）；非 200 或解码失败返回 null。
     */
    protected BufferedImage fetch(int championId) {
        HttpGet request = new HttpGet(String.format(ICON_URL_PATTERN, championId));
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            if (response.getCode() != HttpStatus.OK.value()) {
                warnOnce(championId, "http " + response.getCode());
                return null;
            }
            try (InputStream in = response.getEntity().getContent()) {
                BufferedImage icon = ImageIO.read(in);
                if (icon == null) {
                    warnOnce(championId, "unreadable image");
                    return null;
                }
                return icon;
            }
        } catch (Exception e) {
            // CDN 偶发故障：不抛异常，返回 null 由渲染降级；CDN 恢复后下次调用自动重试
            warnOnce(championId, e.getMessage());
            return null;
        }
    }

    /** 同一英雄 id 只告警一次（成功后会重置），日志可追踪但不刷屏 */
    private void warnOnce(int championId, String reason) {
        if (failedWarned.add(championId)) {
            log.warn("Champion icon fetch failed: id={}, reason={}, will fallback to color dot", championId, reason);
        }
    }
}
