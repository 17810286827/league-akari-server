package com.leagueakari.service;

import com.leagueakari.dto.RiotAccountDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Riot 召唤师搜索客户端（service 层）：
 * 调用 Riot Account-V1 接口按"昵称#tag"查询 puuid；
 * 结果做 JVM 内存缓存（ConcurrentHashMap，按 riotName 键缓存），避免重复打 Riot API
 * （Riot API 有速率限制，缓存可显著降低调用量）
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

    /** HTTP 客户端（Riot API 调用走 RestTemplate） */
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * JVM 缓存：riotName（"昵称#tag"）→ 账号信息。
     * 只缓存查询成功的结果；失败（404/网络错误）不缓存，下次重新查询
     */
    private final Map<String, RiotAccountDto> accountCache = new ConcurrentHashMap<>();

    /**
     * 构造注入 Riot 配置
     *
     * @param apiKey         Riot API Key（可能为空串，为空时搜索接口直接报错）
     * @param accountDomain  Riot 账号接口域名
     * @param summonerDomain Riot 召唤师接口域名（台服 sea）
     */
    public RiotAccountClient(
            @Value("${riot.api-key:}") String apiKey,
            @Value("${riot.account-domain:https://asia.api.riotgames.com}") String accountDomain,
            @Value("${riot.summoner-domain:https://sea.api.riotgames.com}") String summonerDomain) {
        this.apiKey = apiKey;
        this.accountDomain = accountDomain;
        this.summonerDomain = summonerDomain;
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
        // URI 模板 + 变量：由 RestTemplate 的 UriTemplateHandler 统一编码（中文/特殊字符自动 %XX），
        // 不能手工预编码拼接（已编码的 % 会被二次编码为 %25 导致 Riot 侧 404）
        String url = accountDomain + "/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}";

        // 请求头：Riot API 通过 X-Riot-Token 传递开发者 Key
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Riot-Token", apiKey);
        try {
            log.info("Calling Riot Account API: {}#{}", gameName, tagLine);
            ResponseEntity<RiotAccountDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), RiotAccountDto.class,
                    gameName, tagLine);
            RiotAccountDto account = response.getBody();
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
        } catch (HttpClientErrorException e) {
            // 404：召唤师不存在（业务异常，转为明确的 404 提示）
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("Riot account not found: {}", riotName);
                throw new RiotAccountNotFoundException(riotName);
            }
            // 其他 4xx（如 401 Key 失效 / 403 无权限 / 429 限流）：记日志后抛出
            log.error("Riot Account API error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("Riot API 调用失败（" + e.getStatusCode() + "），请稍后重试");
        } catch (RestClientException e) {
            // 网络/超时等客户端异常：不缓存，抛出便于上层提示
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
            String url = summonerDomain + "/lol/summoner/v4/summoners/by-puuid/{puuid}";
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Riot-Token", apiKey);
            ResponseEntity<RiotAccountDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), RiotAccountDto.class,
                    account.getPuuid());
            RiotAccountDto profile = response.getBody();
            if (profile != null) {
                account.setSummonerLevel(profile.getSummonerLevel());
                account.setProfileIconId(profile.getProfileIconId());
            }
        } catch (Exception e) {
            // 等级/头像为增强信息：任何失败都不影响账号搜索主流程
            log.warn("Failed to fetch summoner profile for {}: {}", account.getPuuid(), e.getMessage());
        }
    }
}
