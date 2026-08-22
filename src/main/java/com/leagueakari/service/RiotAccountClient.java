package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.RiotAccountDto;
import com.leagueakari.entity.RiotAccount;
import com.leagueakari.mapper.RiotAccountMapper;
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

/**
 * Riot 召唤师搜索客户端（service 层）：
 * 查询优先级——riot_account 库表（持久化缓存，puuid 终身不变）→ 未命中才调 Riot Account-V1。
 * API 结果按 puuid 回写入库（一人一行，改名时更新名字），替代原 JVM 内存缓存：
 * 重启不丢、可积累、查库毫秒级且不消耗 Riot API 配额。
 * HTTP 调用走 Apache HttpClient 5（全局连接池实例）
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

    /** riot_account 表 Mapper：持久化缓存查询与回写 */
    private final RiotAccountMapper riotAccountMapper;

    /**
     * 构造注入 Riot 配置、HTTP 客户端与持久化缓存 Mapper
     *
     * @param apiKey            Riot API Key（可能为空串；库命中场景不依赖 Key）
     * @param accountDomain     Riot 账号接口域名
     * @param summonerDomain    Riot 召唤师接口域名（台服 sea）
     * @param httpClient        Apache HttpClient 5 实例
     * @param objectMapper      Jackson 解析器
     * @param riotAccountMapper riot_account 表 Mapper
     */
    public RiotAccountClient(
            @Value("${riot.api-key:}") String apiKey,
            @Value("${riot.account-domain:https://asia.api.riotgames.com}") String accountDomain,
            @Value("${riot.summoner-domain:https://sea.api.riotgames.com}") String summonerDomain,
            CloseableHttpClient httpClient,
            ObjectMapper objectMapper,
            RiotAccountMapper riotAccountMapper) {
        this.apiKey = apiKey;
        this.accountDomain = accountDomain;
        this.summonerDomain = summonerDomain;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.riotAccountMapper = riotAccountMapper;
    }

    /**
     * 按"昵称#tag"查询召唤师账号信息（优先命中持久化缓存 riot_account 表）
     *
     * @param riotName 召唤师名，格式 "昵称#tag"（如 "赌书消得泼茶香#iKun"）
     * @return 账号信息（puuid/gameName/tagLine/等级/头像）
     * @throws IllegalArgumentException     riotName 缺少 #tag
     * @throws IllegalStateException        库未命中且 API Key 未配置，或 API 调用失败
     * @throws RiotAccountNotFoundException 召唤师不存在（Riot 返回 404）
     */
    public RiotAccountDto searchByRiotId(String riotName) {
        // 输入格式校验：必须包含 #（Riot Account-V1 需要 gameName + tagLine 两部分）
        if (riotName == null || !riotName.contains("#")) {
            log.warn("Invalid riot name (missing #tag): {}", riotName);
            throw new IllegalArgumentException("召唤师名格式错误，应为 昵称#tag（如 赌书消得泼茶香#iKun）");
        }
        // 拆分 gameName 与 tagLine：只按第一个 # 拆（tagLine 本身不含 #）
        String[] parts = riotName.split("#", 2);
        String gameName = parts[0];
        String tagLine = parts[1];

        // 持久化缓存命中：直接返回库内记录，零 Riot API 调用（不依赖 API Key）
        RiotAccount stored = findByGameName(gameName, tagLine);
        if (stored != null) {
            log.info("Riot account db cache hit: {}#{} -> {}", gameName, tagLine, stored.getPuuid());
            return toDto(stored);
        }

        // 配置校验：需要调 Riot API 时才检查 Key，避免库命中场景被误拦截
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Riot API key not configured, search skipped: {}", riotName);
            throw new IllegalStateException("Riot API Key 未配置，无法搜索召唤师");
        }

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
                // 响应体为空或缺少 puuid：视为异常数据，不入库
                log.warn("Riot Account API returned empty body: {}", riotName);
                throw new IllegalStateException("Riot 返回异常数据，请稍后重试");
            }
            // 补充召唤师等级与头像 ID（Summoner-V4 by-puuid）：失败不阻塞主流程（等级缺失时前端占位）
            fillSummonerProfile(account);
            // 按 puuid 回写持久化缓存：后续同名搜索走库，不再消耗 Riot API 配额；
            // 回写失败仅记日志不阻塞返回（缓存降级为下次重新调 API）
            persistAccount(account);
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
            // 网络/解析等客户端异常：不入库，抛出便于上层提示
            log.error("Riot Account API request failed: {}", e.getMessage());
            throw new IllegalStateException("Riot API 请求失败，请检查网络后重试");
        }
    }

    /**
     * 按"昵称 + tag"查持久化缓存表。
     * (game_name, tag_line) 不设唯一约束（Riot 名字可释放后被他人占用），
     * 极端时序下可能出现同名多行，按 updated_at 倒序取最新一条
     *
     * @param gameName 昵称（# 前部分）
     * @param tagLine  尾号（# 后部分）
     * @return 命中的账号记录；未命中返回 null
     */
    private RiotAccount findByGameName(String gameName, String tagLine) {
        return riotAccountMapper.selectOne(new QueryWrapper<RiotAccount>()
                .eq("game_name", gameName)
                .eq("tag_line", tagLine)
                .orderByDesc("updated_at")
                .last("LIMIT 1"));
    }

    /**
     * 按 puuid 回写账号信息（upsert 语义）：
     * puuid 已存在 → 更新名字/等级/头像（覆盖改名场景）；不存在 → 插入新行。
     * 一人一行由 uk_riot_account_puuid 唯一键保证
     *
     * @param account Riot API 返回的完整账号信息（puuid 必填）
     */
    private void persistAccount(RiotAccountDto account) {
        try {
            RiotAccount entity = new RiotAccount();
            entity.setPuuid(account.getPuuid());
            entity.setGameName(account.getGameName());
            entity.setTagLine(account.getTagLine());
            entity.setSummonerLevel(account.getSummonerLevel());
            entity.setProfileIconId(account.getProfileIconId());

            Long exists = riotAccountMapper.selectCount(new QueryWrapper<RiotAccount>()
                    .eq("puuid", account.getPuuid()));
            if (exists != null && exists > 0) {
                // 已有该玩家：按 puuid 更新（名字/等级/头像随之刷新）
                riotAccountMapper.update(entity, new UpdateWrapper<RiotAccount>()
                        .eq("puuid", account.getPuuid()));
                log.info("Riot account cache updated: puuid={}", account.getPuuid());
            } else {
                // 首次入库：新增一行
                riotAccountMapper.insert(entity);
                log.info("Riot account cache persisted: puuid={}", account.getPuuid());
            }
        } catch (Exception e) {
            // 缓存写入失败不影响搜索主流程：下次同名义查询会重新调 API
            log.error("Failed to persist riot account cache: puuid={}, error={}",
                    account.getPuuid(), e.getMessage());
        }
    }

    /**
     * 实体转 DTO（库命中路径的返回值组装）
     *
     * @param stored 库内账号记录
     * @return 搜索接口响应 DTO
     */
    private RiotAccountDto toDto(RiotAccount stored) {
        RiotAccountDto dto = new RiotAccountDto();
        dto.setPuuid(stored.getPuuid());
        dto.setGameName(stored.getGameName());
        dto.setTagLine(stored.getTagLine());
        dto.setSummonerLevel(stored.getSummonerLevel());
        dto.setProfileIconId(stored.getProfileIconId());
        return dto;
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
