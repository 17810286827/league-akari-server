package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.RiotAccountDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Riot 召唤师搜索客户端（service 层）：
 * 调用 Riot Account-V1 接口按"昵称#tag"查询 puuid；
 * 结果做 JVM 内存缓存（ConcurrentHashMap，按 riotName 键缓存），避免重复打 Riot API
 * （Riot API 有速率限制，缓存可显著降低调用量）。
 * HTTP 调用走 Apache HttpClient 5（全局连接池实例），替换原 RestTemplate 方案
 */
@Slf4j
@Service
public class RiotAccountClient {

    /** Riot API Key（application.yml riot.api-key，环境变量 RIOT_API_KEY 可覆盖） */
    private final String apiKey;

    /** Riot 亚洲区 API 域名（账号接口） */
    private final String accountDomain;

    /** Riot 台服区 API 域名（召唤师等级/头像接口，Summoner-V4 按平台路由） */
    private final String summonerDomain;

    /** Apache HttpClient 5（全局连接池实例，见 HttpClientConfig） */
    private final CloseableHttpClient httpClient;

    /** JSON 解析器（Riot 响应体反序列化） */
    private final ObjectMapper objectMapper;

    /**
     * JVM 缓存：riotName（"昵称#tag"）→ 账号信息。
     * 只缓存查询成功的结果；失败（404/网络错误）不缓存，下次重新查询
     */
    private final Map<String, RiotAccountDto> accountCache = new ConcurrentHashMap<>();

    /**
     * 构造注入 Riot 配置与 HTTP 客户端
     *
     * @param apiKey         Riot API Key（可能为空串，为空时搜索接口直接报错）
     * @param accountDomain  Riot 账号接口域名
     * @param summonerDomain Riot 召唤师接口域名（台服 sea）
     * @param httpClient     Apache HttpClient 5 实例
     * @param objectMapper   Jackson 解析器
     */
    public RiotAccountClient(
            @Value("${riot.api-key:}") String apiKey,
            @Value("${riot.account-domain:https://asia.api.riotgames.com}") String accountDomain,
            @Value("${riot.summoner-domain:https://sea.api.riotgames.com}") String summonerDomain,
            CloseableHttpClient httpClient,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.accountDomain = accountDomain;
        this.summonerDomain = summonerDomain;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 按"昵称#tag"查询召唤师账号信息（优先命中 JVM 缓存）
     *
     * @param riotName 召唤师名，格式 "昵称#tag"（如 "赌书消得泼茶香#iKun"）
     * @return 账号信息（puuid/gameName/tagLine）
     * @throws IllegalArgumentException     riotName 缺少 #tag 或 API Key 未配置
     * @throws RiotAccountNotFoundException 召唤师不存在（Riot 返回 404）
     */
    public RiotAccountDto searchByRiotId(String riotName) {
        // 输入格式校验：必须包含 #（Riot Account-V1 需要 gameName + tagLine 两部分）
        if (riotName == null || !riotName.contains("#")) {
            log.warn("Invalid riot name (missing #tag): {}", riotName);
            throw new IllegalArgumentException("召唤师名格式错误，应为 昵称#tag（如 赌书消得泼茶香#iKun）");
        }
        // 配置校验：API Key 未配置时直接报错，避免无效调用
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Riot API key not configured, search skipped: {}", riotName);
            throw new IllegalStateException("Riot API Key 未配置，无法搜索召唤师");
        }
        // JVM 缓存命中：直接返回，避免重复调用 Riot API
        RiotAccountDto cached = accountCache.get(riotName);
        if (cached != null) {
            log.info("Riot account cache hit: {}", riotName);
            return cached;
        }

        // 拆分 gameName 与 tagLine：只按第一个 # 拆（tagLine 本身不含 #）
        String[] parts = riotName.split("#", 2);
        String gameName = parts[0];
        String tagLine = parts[1];
        // URI 构建：gameName/tagLine 可能含中文与特殊字符，由 URIBuilder.setPathSegments 统一编码（%XX），
        // 不能手工预编码拼接（已编码的 % 会被二次编码为 %25 导致 Riot 侧 404）
        URI uri = buildUri(accountDomain, "riot", "account", "v1", "accounts", "by-riot-id", gameName, tagLine);

        // 请求头：Riot API 通过 X-Riot-Token 传递开发者 Key
        HttpGet request = new HttpGet(uri);
        request.setHeader("X-Riot-Token", apiKey);
        try {
            log.info("Calling Riot Account API: {}#{}", gameName, tagLine);
            RiotAccountDto account = executeForDto(request, uri.toString());
            if (account == null || account.getPuuid() == null) {
                // 响应体为空或缺少 puuid：视为异常数据，不缓存
                log.warn("Riot Account API returned empty body: {}", riotName);
                throw new IllegalStateException("Riot 返回异常数据，请稍后重试");
            }
            // 查询成功写入缓存
            accountCache.put(riotName, account);
            // 补充召唤师等级与头像 ID（Summoner-V4 by-puuid）：失败不阻塞主流程（等级缺失时前端占位）
            fillSummonerProfile(account);
            log.info("Riot account found: {} -> {} (level={})", riotName, account.getPuuid(),
                    account.getSummonerLevel());
            return account;
        } catch (RiotAccountNotFoundException e) {
            // 404：召唤师不存在（业务异常，转给全局处理器返回 404 提示）
            log.warn("Riot account not found: {}", riotName);
            throw e;
        } catch (IllegalStateException e) {
            // 其余 4xx/5xx 与网络异常：统一抛出提示
            throw e;
        } catch (Exception e) {
            // 网络/解析等客户端异常：不缓存，抛出便于上层提示
            log.error("Riot Account API request failed: {}", e.getMessage());
            throw new IllegalStateException("Riot API 请求失败，请检查网络后重试");
        }
    }

    /**
     * 补充召唤师等级与头像 ID：调用 Summoner-V4 by-puuid（按平台域名路由）。
     * 该接口失败（如区域无记录/网络异常）仅记 warn 日志，不阻塞搜索主流程——
     * 等级/头像缺失时前端展示占位
     *
     * @param account 已从 Account-V1 拿到的账号信息（puuid 必填）
     */
    private void fillSummonerProfile(RiotAccountDto account) {
        try {
            URI uri = buildUri(summonerDomain, "lol", "summoner", "v4", "summoners", "by-puuid", account.getPuuid());
            HttpGet request = new HttpGet(uri);
            request.setHeader("X-Riot-Token", apiKey);
            RiotAccountDto profile = executeForDto(request, uri.toString());
            if (profile != null) {
                account.setSummonerLevel(profile.getSummonerLevel());
                account.setProfileIconId(profile.getProfileIconId());
            }
        } catch (Exception e) {
            // 等级/头像为增强信息：任何失败都不影响账号搜索主流程
            log.warn("Failed to fetch summoner profile for {}: {}", account.getPuuid(), e.getMessage());
        }
    }

    /**
     * 构建带路径段的请求 URI：路径段由 URIBuilder 统一编码（中文/特殊字符 → %XX）；
     * 域名或路径非法时视为配置错误，抛 IllegalStateException
     *
     * @param domain   基础域名（如 https://asia.api.riotgames.com）
     * @param segments 路径段（按顺序拼接，每段单独编码）
     * @return 构建完成的 URI
     */
    private URI buildUri(String domain, String... segments) {
        try {
            return new URIBuilder(domain).setPathSegments(segments).build();
        } catch (URISyntaxException e) {
            // 配置/入参导致的 URI 非法：配置错误应尽早暴露而非静默失败
            log.error("Invalid URI: domain={}, segments={}: {}", domain, String.join("/", segments), e.getMessage());
            throw new IllegalStateException("外部 API 地址配置错误");
        }
    }

    /**
     * 执行 GET 请求并解析 JSON 响应体为 DTO：
     * 404 抛 RiotAccountNotFoundException（业务异常），其余非 2xx 抛 IllegalStateException
     *
     * @param request 已配置好 URL 与请求头的 GET 请求
     * @param url     请求 URL（仅用于日志）
     * @return 解析后的 DTO（响应体为空时返回 null）
     */
    private RiotAccountDto executeForDto(HttpGet request, String url) throws Exception {
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getCode();
            // 响应体读取：UTF-8 解码 JSON 文本
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), java.nio.charset.StandardCharsets.UTF_8)
                    : "";
            if (status == HttpStatus.NOT_FOUND.value()) {
                // 404：召唤师不存在，抛业务异常由全局处理器转为 404
                throw new RiotAccountNotFoundException(url);
            }
            if (status < 200 || status >= 300) {
                // 其他 4xx（401 Key 失效 / 403 无权限 / 429 限流）与 5xx：记日志后抛出
                log.error("Riot API error: status={}, body={}", status, body);
                throw new IllegalStateException("Riot API 调用失败（" + status + "），请稍后重试");
            }
            if (body.isBlank()) {
                // 空响应体：返回 null 由调用方判定
                return null;
            }
            // 反序列化 JSON 为 DTO（Riot 字段名与 DTO 驼峰字段对应）
            return objectMapper.readValue(body, RiotAccountDto.class);
        }
    }
}
