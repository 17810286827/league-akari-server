package com.leagueakari.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.common.exception.QqPushException;
import com.leagueakari.config.PushProperties;
import lombok.Value;
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

    /** md5_10m 口径：前 10_002_432 字节的 MD5（官方分片规则，非整 10MB；与 AstrBot 对齐） */
    private static final int MD5_10M_BYTES = 10_002_432;

    /** 缓存条目：token + 过期毫秒时间戳（Lombok @Value 不可变对象） */
    @Value
    private static class TokenCache {

        /** 访问凭证 */
        String token;

        /** 过期时间（毫秒时间戳） */
        long expiresAtMs;
    }

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
     * 向车队群发送 Markdown 消息（msg_type=2）：支持 **加粗** 等富文本语法。
     * <p>官方 2026-04-23 起群聊/单聊"自定义 Markdown"对全部机器人开放（免模板申请），
     * 个人实名认证机器人即可使用；频控按 Bot 维度 60/qpm，锐评每局一条无压力。
     * 纯文本（msg_type=0）无任何格式能力，醒目标记必须走本通道。</p>
     *
     * @param groupOpenId 目标群 openid
     * @param content     Markdown 文本（**加粗** 等语法由 QQ 客户端渲染）
     * @throws QqPushException 凭证未配置 / 接口非 200 / 网络失败
     */
    public void sendGroupMarkdownMessage(String groupOpenId, String content) {
        if (groupOpenId == null || groupOpenId.isBlank()) {
            throw new QqPushException("QQ 群 openid 未配置，无法推送");
        }
        String token = obtainAccessToken();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msg_type", 2);
        payload.put("markdown", Map.of("content", content == null ? "" : content));
        postJson(API_BASE + "/v2/groups/" + groupOpenId + "/messages", token, payload,
                "群消息发送(markdown)");
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
    /**
     * 向车队群发送图片消息（msg_type=7 富媒体）：
     * 服务端本地 PNG 按官方分片上传协议换取 file_info，再以媒体消息引用发送。
     * <p>协议（与生产级实现 AstrBot 对齐）：
     * upload_prepare（file_type/file_size/file_name/md5/sha1/md5_10m）
     * → 逐片 PUT 预签名 URL（COS）→ 每片 POST upload_part_finish 确认
     * → POST files 合并取 {file_uuid, file_info} → messages msg_type=7。
     * 战报图单张远小于分片上限，实际走单分片路径。</p>
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
        uploadParts(token, groupOpenId, session, pngBytes);
        String fileInfo = mergeFile(token, groupOpenId, session);
        sendMediaMessage(token, groupOpenId, fileInfo);
    }

    /** 单个待传分片：服务端下发的序号与预签名 URL（Lombok @Value 不可变对象） */
    @Value
    private static class UploadPart {

        /** 分片序号（服务端从 0 或 1 起发，按实际下发值回传） */
        int index;

        /** 该分片的 COS 预签名上传 URL */
        String presignedUrl;
    }

    /** 上传会话：prepare 响应的一次性状态（Lombok @Value 不可变对象） */
    @Value
    private static class UploadSession {

        /** 会话 ID（合并接口回传用） */
        String uploadId;

        /** 分片块大小（字节，服务端下发） */
        int blockSize;

        /** 待传分片列表（序号 + 预签名 URL） */
        List<UploadPart> parts;
    }

    /**
     * 第 1 步：预上传申请。请求体含 file_type（1=图片）与 file_size（字符串），
     * 响应可能带 data 包装，分片数组字段为 parts（每项 presigned_url + index）
     */
    private UploadSession prepareUpload(String token, String groupOpenId, byte[] bytes) {
        String md5 = digest("MD5", bytes);
        String sha1 = digest("SHA-1", bytes);
        // md5_10m：前 10_002_432 字节的 MD5（文件更小时即整文件 md5）
        String md5_10m = bytes.length <= MD5_10M_BYTES ? md5 : digest("MD5", head(bytes, MD5_10M_BYTES));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_type", 1); // 1=图片
        payload.put("file_size", String.valueOf(bytes.length)); // 官方要求字符串
        payload.put("file_name", "report.png");
        payload.put("md5", md5);
        payload.put("sha1", sha1);
        payload.put("md5_10m", md5_10m);

        JsonNode resp = postJson(API_BASE + "/v2/groups/" + groupOpenId + "/upload_prepare",
                token, payload, "图片预上传");
        JsonNode body = resp.has("data") && resp.get("data").isObject() ? resp.get("data") : resp;
        String uploadId = body.path("upload_id").asText("");
        int blockSize = body.path("block_size").asInt(1024 * 1024);
        JsonNode partsNode = body.get("parts");
        List<UploadPart> parts = new ArrayList<>();
        if (partsNode != null && partsNode.isArray()) {
            for (JsonNode part : partsNode) {
                String url = part.path("presigned_url").asText("");
                if (!url.isBlank() && part.hasNonNull("index")) {
                    parts.add(new UploadPart(part.path("index").asInt(), url));
                }
            }
        }
        if (uploadId.isBlank() || parts.isEmpty()) {
            throw new QqPushException("QQ 图片预上传响应不完整（缺 upload_id 或 parts）: " + resp);
        }
        log.info("QQ upload prepared: size={}, uploadId={}, parts={}, blockSize={}",
                bytes.length, uploadId, parts.size(), blockSize);
        return new UploadSession(uploadId, blockSize, parts);
    }

    /**
     * 第 2 步：逐片上传——PUT 预签名 URL 后每片单独 POST upload_part_finish 确认。
     * 分片序号可能从 0 或 1 起（服务端下发为准），偏移按最小序号归一
     */
    private void uploadParts(String token, String groupOpenId, UploadSession session, byte[] bytes) {
        int minIndex = session.getParts().stream().mapToInt(UploadPart::getIndex).min().orElse(0);
        for (UploadPart part : session.getParts()) {
            int offset = (part.getIndex() - minIndex) * session.getBlockSize();
            int length = Math.min(session.getBlockSize(), bytes.length - offset);
            if (length <= 0) {
                throw new QqPushException("QQ 上传分片越界: index=" + part.getIndex());
            }
            byte[] partBytes = java.util.Arrays.copyOfRange(bytes, offset, offset + length);
            putBytes(part.getPresignedUrl(), partBytes);
            finishPart(token, groupOpenId, session.getUploadId(), part.getIndex(), length, partBytes);
        }
    }

    /** PUT 分片字节到预签名 URL（COS 直传；状态码非 2xx 抛错） */
    private void putBytes(String url, byte[] data) {
        HttpPut put = new HttpPut(url);
        put.setHeader("Content-Type", ContentType.APPLICATION_OCTET_STREAM.toString());
        put.setEntity(new ByteArrayEntity(data, ContentType.APPLICATION_OCTET_STREAM));
        try (CloseableHttpResponse response = httpClient.execute(put)) {
            int status = response.getCode();
            if (status < 200 || status >= 300) {
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

    /** 单片确认：upload_part_finish（body 含 upload_id/part_index/block_size/md5） */
    private void finishPart(String token, String groupOpenId, String uploadId,
                            int partIndex, int partSize, byte[] partBytes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("upload_id", uploadId);
        payload.put("part_index", partIndex);
        payload.put("block_size", String.valueOf(partSize));
        payload.put("md5", digest("MD5", partBytes));
        postJson(API_BASE + "/v2/groups/" + groupOpenId + "/upload_part_finish",
                token, payload, "图片分片确认");
    }

    /**
     * 第 3 步：files 合并注册，返回 file_info（ttl 300s 现传现用）。
     * 请求体需带 file_type/srv_send_msg/file_name/upload_id
     */
    private String mergeFile(String token, String groupOpenId, UploadSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file_type", 1); // 1=图片
        payload.put("srv_send_msg", false); // 仅注册不直接发送（消息由 msg_type=7 单独发）
        payload.put("file_name", "report.png");
        payload.put("upload_id", session.getUploadId());
        JsonNode resp = postJson(API_BASE + "/v2/groups/" + groupOpenId + "/files",
                token, payload, "图片合并注册");
        JsonNode body = resp.has("data") && resp.get("data").isObject() ? resp.get("data") : resp;
        String fileInfo = body.path("file_info").asText("");
        if (fileInfo.isBlank()) {
            throw new QqPushException("QQ 图片合并响应缺少 file_info: " + resp);
        }
        log.info("QQ file merged: file_uuid={}, ttl={}",
                body.path("file_uuid").asText(""), body.path("ttl").asText(""));
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
     * 统一 JSON POST 组装：设置 Content-Type、鉴权头与 JSON 实体后执行。
     * 鉴权头按官方 SDK（botpy）：Authorization = "QQBot " + access_token（非 Bearer），
     * 另附 X-Union-Appid 头；凭证换取请求本身不带这些头
     */
    private JsonNode postJson(String url, String token, Map<String, Object> payload, String action) {
        HttpPost post = new HttpPost(url);
        post.setHeader("Content-Type", ContentType.APPLICATION_JSON.toString());
        post.setHeader("Authorization", "QQBot " + token);
        post.setHeader("X-Union-Appid", pushProperties.getAppId());
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
        // 仅需机器人凭证（appId/secret）：不检查 group-open-id——群 openid 是推送目标，
        // 与凭证换取无关（曾误用 isConfigured() 把 openid 纳入，导致 WS identify 永远被拒）
        if (pushProperties.getAppId() == null || pushProperties.getAppId().isBlank()
                || pushProperties.getClientSecret() == null || pushProperties.getClientSecret().isBlank()) {
            throw new QqPushException("QQ 机器人凭证未配置（push.app-id / push.client-secret）");
        }
        TokenCache cached = tokenCache;
        if (cached != null && cached.getExpiresAtMs() - 60_000 > System.currentTimeMillis()) {
            return cached.getToken();
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
