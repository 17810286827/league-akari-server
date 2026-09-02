package com.leagueakari.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.PushProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ 官方开放平台机器人客户端（外部 I/O 接缝）：
 * 负责换取并缓存 access_token、向车队群发送文本与图片消息。
 * 只做 OpenAPI 协议层，不涉及业务判定（判定在 BroadcastCoordinator）。
 * <p>官方文档：凭证 api.bot.qq.com/app/getAppAccessToken；
 * 群消息 POST /v2/groups/{group_openid}/messages（msg_type 0=纯文本 / 7=富媒体）；
 * 图片先经群文件接口上传拿 file_info（ttl 300s，现传现用）再引用发送。
 * 上传走分片流程（upload_prepare → 预签名 PUT → upload_part_finish → files 合并），
 * 支持服务端本地文件（战报图 PNG 无公网 URL 时走此路径）。</p>
 */
@Slf4j
@Service
public class QqBotClient {

    /** 官方 OpenAPI 统一域名（2026-08 起） */
    private static final String API_BASE = "https://api.bot.qq.com";

    /** 缓存条目：token + 过期毫秒时间戳 */
    private record TokenCache(String token, long expiresAtMs) {}

    /** 进程内 token 缓存：过期前 60 秒视为失效提前刷新 */
    private volatile TokenCache tokenCache;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PushProperties pushProperties;

    public QqBotClient(CloseableHttpClient httpClient, ObjectMapper objectMapper,
                       PushProperties pushProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.pushProperties = pushProperties;
    }

    /**
     * 向车队群发送纯文本消息（msg_type=0）
     *
     * @param groupOpenId 目标群 openid
     * @param content     文本内容
     * @throws QqPushException 凭证未配置 / 接口非 200 / 网络失败
     */
    public void sendGroupTextMessage(String groupOpenId, String content) {
        if (groupOpenId == null || groupOpenId.isBlank()) {
            throw new QqPushException("QQ 群 openid 未配置，无法推送");
        }
        String token = obtainAccessToken();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", 0);
        payload.put("content", content);
        postJson(API_BASE + "/v2/groups/" + groupOpenId + "/messages", token, payload, "群消息发送");
    }

    /**
     * 向车队群发送图片消息（msg_type=7 富媒体）：
     * 服务端本地 PNG 先按官方分片上传流程换取 file_info，再以媒体消息引用发送。
     * <p>流程：upload_prepare（摘要与大小）→ 逐片 PUT 预签名 URL → upload_part_finish
     * → files 合并取 file_info（ttl 300s 现传现用）→ messages msg_type=7。
     * 战报图单张远小于分片上限，实际走单分片路径；接口字段以官方文档为准，
     * 联调异常时错误信息携带响应体便于核对。</p>
     *
     * @param groupOpenId 目标群 openid
     * @param pngBytes    战报图 PNG 字节
     * @throws QqPushException 任一步非 200 / 响应结构异常
     */
    public void sendGroupImageMessage(String groupOpenId, byte[] pngBytes) {
        if (groupOpenId == null || groupOpenId.isBlank()) {
            throw new QqPushException("QQ 群 openid 未配置，无法推送");
        }
        String token = obtainAccessToken();
        UploadSession session = prepareUpload(token, groupOpenId, pngBytes);
        uploadParts(token, session, pngBytes);
        finishUpload(token, groupOpenId, session.uploadId());
        String fileInfo = registerFile(token, groupOpenId, session.uploadId());
        sendMediaMessage(token, groupOpenId, fileInfo);
    }

    /** 上传会话：prepare 响应的一次性状态（upload_id + 预签名 URL 列表 + 分片大小） */
    private record UploadSession(String uploadId, List<String> urls, int blockSize) {}

    /**
     * 第 1 步：预上传申请（file_size/file_name/md5/sha1/md5_10m）。
     * 响应含 upload_id、block_size 与各分片预签名 URL（字段名官方各版本有差异，
     * upload_urls/urls 双路径宽松兼容），联调异常时错误信息携带响应体便于核对
     */
    private UploadSession prepareUpload(String token, String groupOpenId, byte[] bytes) {
        String md5 = digest("MD5", bytes);
        String sha1 = digest("SHA-1", bytes);
        // md5_10m：前 10MB 的 MD5（分片规则；战报图小于 10MB 时即整文件）
        String md5_10m = bytes.length <= 10 * 1024 * 1024 ? md5 : digest("MD5", head(bytes, 10 * 1024 * 1024));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_size", bytes.length);
        payload.put("file_name", "report.png");
        payload.put("md5", md5);
        payload.put("sha1", sha1);
        payload.put("md5_10m", md5_10m);

        JsonNode resp = postJson(API_BASE + "/v2/groups/" + groupOpenId + "/upload_prepare",
                token, payload, "图片预上传");
        String uploadId = resp.path("upload_id").asText("");
        if (uploadId.isBlank()) {
            throw new QqPushException("QQ 图片预上传响应缺少 upload_id: " + resp);
        }
        // 预签名 URL：双路径兼容（upload_urls 为主，urls 兜底），缺失时后续 PUT 步骤会报错
        JsonNode urlsNode = resp.has("upload_urls") ? resp.get("upload_urls")
                : resp.get("urls");
        List<String> urls = new ArrayList<>();
        if (urlsNode != null && urlsNode.isArray()) {
            urlsNode.forEach(n -> urls.add(n.asText()));
        }
        int blockSize = resp.path("block_size").asInt(1024 * 1024);
        log.info("QQ upload prepared: size={}, uploadId={}, parts={}", bytes.length, uploadId, urls.size());
        return new UploadSession(uploadId, urls, blockSize);
    }

    /**
     * 第 2 步：分片 PUT 到预签名 URL。单分片（战报图实际场景）直接全量 PUT；
     * 多分片按 block_size 切分逐片 PUT（防御实现）
     */
    private void uploadParts(String token, UploadSession session, byte[] bytes) {
        if (session.urls().isEmpty()) {
            throw new QqPushException("QQ 图片预上传响应缺少预签名 URL，无法上传分片");
        }
        if (bytes.length <= session.blockSize()) {
            putBytes(session.urls().get(0), bytes);
            return;
        }
        for (int i = 0; i < session.urls().size(); i++) {
            int from = i * session.blockSize();
            int to = Math.min(bytes.length, from + session.blockSize());
            putBytes(session.urls().get(i), java.util.Arrays.copyOfRange(bytes, from, to));
        }
    }

    /** PUT 字节到预签名 URL（分片上传；状态码非 200 抛错） */
    private void putBytes(String url, byte[] data) {
        HttpPut put = new HttpPut(url);
        put.setHeader("Content-Type", ContentType.APPLICATION_OCTET_STREAM.toString());
        put.setEntity(new ByteArrayEntity(data, ContentType.APPLICATION_OCTET_STREAM));
        try (CloseableHttpResponse response = httpClient.execute(put)) {
            int status = response.getCode();
            if (status != 200) {
                String body = response.getEntity() != null
                        ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
                log.error("QQ upload part failed: status={}, body={}", status, body);
                throw new QqPushException("QQ 图片分片上传失败（HTTP " + status + "）");
            }
        } catch (IOException | org.apache.hc.core5.http.ParseException e) {
            log.error("QQ upload part request failed: {}", e.getMessage());
            throw new QqPushException("QQ 图片分片上传请求失败，请检查网络", e);
        }
    }

    /** 第 3 步：分片确认（单分片场景直接确认 upload_id） */
    private void finishUpload(String token, String groupOpenId, String uploadId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("upload_id", uploadId);
        postJson(API_BASE + "/v2/groups/" + groupOpenId + "/upload_part_finish",
                token, payload, "图片分片确认");
    }

    /** 第 4 步：files 合并注册，返回 file_info（ttl 300s） */
    private String registerFile(String token, String groupOpenId, String uploadId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_type", 1); // 1=图片
        payload.put("upload_id", uploadId);
        JsonNode resp = postJson(API_BASE + "/v2/groups/" + groupOpenId + "/files",
                token, payload, "图片注册");
        String fileInfo = resp.path("file_info").asText("");
        if (fileInfo.isBlank()) {
            throw new QqPushException("QQ 图片注册响应缺少 file_info: " + resp);
        }
        log.info("QQ file registered: ttl={}", resp.path("ttl").asText(""));
        return fileInfo;
    }

    /** 第 5 步：富媒体消息（msg_type=7）引用 file_info 发送 */
    private void sendMediaMessage(String token, String groupOpenId, String fileInfo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", 7);
        payload.put("media", Map.of("file_info", fileInfo));
        postJson(API_BASE + "/v2/groups/" + groupOpenId + "/messages",
                token, payload, "图片消息发送");
    }

    /**
     * 统一 JSON POST 组装：设置 Content-Type/Bearer 与 JSON 实体后执行。
     * 所有业务 POST（文本/凭证/上传各步）共用，避免各调用点重复 setEntity
     */
    private JsonNode postJson(String url, String token, Map<String, Object> payload, String action) {
        HttpPost post = new HttpPost(url);
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON.toString());
        post.setHeader("Authorization", "Bearer " + token);
        try {
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload),
                    ContentType.APPLICATION_JSON));
        } catch (Exception e) {
            log.error("Failed to serialize QQ payload: {}", e.getMessage());
            throw new QqPushException("QQ " + action + "请求组装失败", e);
        }
        return executeJson(post, action);
    }

    /** 摘要计算（MD5/SHA-1），用于上传前置校验 */
    private static String digest(String algorithm, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] out = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("摘要算法不可用: " + algorithm, e);
        }
    }

    private static byte[] head(byte[] data, int n) {
        byte[] out = new byte[Math.min(n, data.length)];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    /** 执行 POST 并解析 JSON 响应；非 200 抛 QqPushException */
    private JsonNode executeJson(HttpPost post, String action) {
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            if (status != 200) {
                log.error("QQ {} API error: status={}, body={}", action, status, body);
                throw new QqPushException("QQ " + action + "失败（HTTP " + status + "）：" + body);
            }
            try {
                return objectMapper.readTree(body);
            } catch (Exception e) {
                log.error("QQ {} response parse failed: body={}", action, body);
                throw new QqPushException("QQ " + action + "响应解析失败", e);
            }
        } catch (IOException | org.apache.hc.core5.http.ParseException e) {
            log.error("QQ {} request failed: {}", action, e.getMessage());
            throw new QqPushException("QQ " + action + "请求失败，请检查网络", e);
        }
    }

    /**
     * 换取并缓存 access_token：凭证接口返回 expires_in（秒），
     * 缓存至过期前 60 秒；并发下重复换取无害（幂等），由最后一次写覆盖。
     * 供两类场景共用：OpenAPI 请求的 Bearer 头，以及 WS 事件网关
     * identify 鉴权（"QQBot " + access_token，官方 SDK botpy 同款）
     *
     * @throws QqPushException 凭证未配置或换取失败
     */
    public String obtainAccessToken() {
        if (!pushProperties.isConfigured()) {
            throw new QqPushException("QQ 机器人凭证未配置（push.app-id / push.client-secret）");
        }
        TokenCache cached = tokenCache;
        if (cached != null && cached.expiresAtMs() - 60_000 > System.currentTimeMillis()) {
            return cached.token();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appId", pushProperties.getAppId());
        payload.put("clientSecret", pushProperties.getClientSecret());
        HttpPost post = new HttpPost(API_BASE + "/app/getAppAccessToken");
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON.toString());
        try {
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload),
                    ContentType.APPLICATION_JSON));
        } catch (Exception e) {
            log.error("Failed to serialize QQ token payload: {}", e.getMessage());
            throw new QqPushException("QQ 凭证请求组装失败", e);
        }

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            if (status != 200) {
                log.error("QQ token API error: status={}, body={}", status, body);
                throw new QqPushException("QQ 凭证换取失败（HTTP " + status + "）");
            }
            JsonNode json = objectMapper.readTree(body);
            String token = json.path("access_token").asText("");
            long expiresIn = json.path("expires_in").asLong(7200);
            if (token.isBlank()) {
                throw new QqPushException("QQ 凭证响应缺少 access_token");
            }
            tokenCache = new TokenCache(token, System.currentTimeMillis() + expiresIn * 1000);
            log.info("QQ access token refreshed: expiresIn={}s", expiresIn);
            return token;
        } catch (IOException e) {
            log.error("QQ token request failed: {}", e.getMessage());
            throw new QqPushException("QQ 凭证请求失败，请检查网络与凭证", e);
        } catch (QqPushException e) {
            throw e;
        } catch (Exception e) {
            log.error("QQ token response parse failed: {}", e.getMessage());
            throw new QqPushException("QQ 凭证响应异常", e);
        }
    }

    /**
     * 执行 POST 并校验：非 200 抛 QqPushException（携带官方错误码便于排障），
     * 网络/解析异常同样包装抛出
     */
    private void executeChecked(HttpPost post, String action) {
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            if (status != 200) {
                log.error("QQ {} API error: status={}, body={}", action, status, body);
                throw new QqPushException("QQ " + action + "失败（HTTP " + status + "）：" + body);
            }
            log.info("QQ {} success", action);
        } catch (IOException | org.apache.hc.core5.http.ParseException e) {
            log.error("QQ {} request failed: {}", action, e.getMessage());
            throw new QqPushException("QQ " + action + "请求失败，请检查网络", e);
        }
    }
}
