package com.leagueakari.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.AiProperties;
import com.leagueakari.dto.WeeklyReportResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车队周报 AI 锐评服务（外部 I/O 接缝）：
 * 把周报聚合摘要组装为一次 AI 调用（非流式），输出一段可直接发群的锐评文本。
 * 独立于 TeamStatsService——聚合口径不依赖 AI，AI 失败由调用方优雅降级。
 * <p>缓存：按周标签缓存生成结果（10 分钟 TTL）——同一周重复生成不重复计费；
 * 过期后重新生成（数据回填变化时锐评也会随之刷新）。</p>
 */
@Slf4j
@Service
public class WeeklyAiCommentService {

    /** 缓存条目：锐评文本 + 写入时间戳 */
    private record CacheEntry(String comment, long timestamp) {}

    /** 缓存有效期：10 分钟（毫秒）。周报数据一周一变，10 分钟足够覆盖页内反复刷新 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** 浏览器 UA：opencode go 经 Cloudflare 防护，无 UA 会被 403/503 拦截（与对局分析一致） */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** opencode go API 基础地址（OpenAI 兼容） */
    private final String baseUrl;

    /** API Key（环境变量 AI_API_KEY） */
    private final String apiKey;

    /** 模型名（ai.model：与单局分析共用同一键） */
    private final String model;

    /** 周锐评提示词文件（classpath，可直接编辑，改动即时生效） */
    private final String promptFile;

    /** 采样温度 */
    private final double temperature;

    /** 输出 token 上限 */
    private final int maxTokens;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** 缓存：周标签 → 锐评条目（成功才缓存，失败下次重试） */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 构造注入：AI 配置统一取自 AiProperties（yaml 唯一真值，见 docs/adr/0004） */
    public WeeklyAiCommentService(
            AiProperties ai,
            CloseableHttpClient httpClient,
            ObjectMapper objectMapper) {
        this.baseUrl = ai.getBaseUrl();
        this.apiKey = ai.getApiKey();
        this.model = ai.getModel();
        this.promptFile = ai.getWeeklyPromptFile();
        this.temperature = ai.getTemperature();
        this.maxTokens = ai.getWeeklyMaxTokens();
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成周报锐评
     *
     * @param report 已聚合完成的周报（只读其摘要字段）
     * @return 锐评文本（中文，一两句话）
     * @throws IllegalStateException AI Key 未配置 / 接口失败 / 重试后正文仍为空
     */
    public String generateComment(WeeklyReportResponse report) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Weekly AI comment skipped: API key not configured");
            throw new IllegalStateException("AI API Key 未配置，无法生成周报锐评");
        }
        // 缓存命中：同一周直接复用（10 分钟内）
        CacheEntry cached = cache.get(report.getWeekLabel());
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            log.info("Weekly AI comment cache hit: week={}", report.getWeekLabel());
            return cached.comment();
        }
        cache.remove(report.getWeekLabel());

        String summary = buildSummary(report);
        String systemPrompt = loadPrompt();
        // 空正文重试：推理模型偶发把输出预算耗在思维链上（finish_reason=length，正文为空），
        // 重试一次通常能拿到正文；两次都为空才判定失败
        String comment = null;
        for (int attempt = 1; attempt <= 2 && comment == null; attempt++) {
            comment = callAi(systemPrompt, summary);
            if (comment == null) {
                log.warn("Weekly AI comment empty, retrying: attempt={}/2, week={}", attempt, report.getWeekLabel());
            }
        }
        if (comment == null) {
            throw new IllegalStateException("AI 返回内容为空，请稍后重试");
        }
        cache.put(report.getWeekLabel(), new CacheEntry(comment, System.currentTimeMillis()));
        log.info("Weekly AI comment generated: week={}, length={}", report.getWeekLabel(), comment.length());
        return comment;
    }

    /**
     * 组装周报摘要（user 消息）：总览 + 各榜单前若干条 + 名场面，紧凑 JSON。
     * 只给"梗素材"，不给逐局明细——锐评要的是全局视角
     */
    private String buildSummary(WeeklyReportResponse report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("week", report.getWeekLabel());
        if (report.getOverview() != null) {
            summary.put("overview", Map.of(
                    "games", report.getOverview().getGameCount(),
                    "memberWins", report.getOverview().getWinCount(),
                    "memberLosses", report.getOverview().getLossCount(),
                    "busiestDay", report.getOverview().getBusiestDay() == null ? "" : report.getOverview().getBusiestDay(),
                    "activeMembers", report.getOverview().getActiveMembers() == null
                            ? List.of() : report.getOverview().getActiveMembers()));
        }
        // 各榜单只取前 3 条：锐评点名的素材足够了
        summary.put("mvpBoard", topOfBoard(report.getMvpBoard()));
        summary.put("criminalBoard", topOfBoard(report.getCriminalBoard()));
        summary.put("feederBoard", topOfBoard(report.getFeederBoard()));
        summary.put("carryBoard", topOfBoard(report.getCarryBoard()));
        summary.put("signatureBoard", topOfBoard(report.getSignatureBoard()));
        if (report.getHighlights() != null) {
            List<Map<String, Object>> highlights = new ArrayList<>();
            collectHighlight(highlights, report.getHighlights().getBiggestComeback());
            collectHighlight(highlights, report.getHighlights().getWorstStreak());
            collectHighlight(highlights, report.getHighlights().getMultiKillMoment());
            collectHighlight(highlights, report.getHighlights().getMostKillsGame());
            summary.put("highlights", highlights);
        }
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.error("Failed to serialize weekly summary: {}", e.getMessage());
            throw new IllegalStateException("周报摘要组装失败");
        }
    }

    /** 榜单前 3 条摘要（riotId + 口径说明），榜单为 null/空时返回空数组 */
    private List<Map<String, Object>> topOfBoard(List<WeeklyReportResponse.BoardEntry> board) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (board == null) {
            return out;
        }
        for (WeeklyReportResponse.BoardEntry entry : board.stream().limit(3).toList()) {
            out.add(Map.of("player", entry.getRiotId() == null ? "" : entry.getRiotId(),
                    "value", entry.getValue() == null ? 0 : entry.getValue(),
                    "detail", entry.getDetail() == null ? "" : entry.getDetail()));
        }
        return out;
    }

    /** 收集非空名场面 */
    private void collectHighlight(List<Map<String, Object>> out, WeeklyReportResponse.HighlightItem item) {
        if (item != null) {
            out.add(Map.of("title", item.getTitle() == null ? "" : item.getTitle(),
                    "detail", item.getDetail() == null ? "" : item.getDetail()));
        }
    }

    /** 读取周锐评提示词（classpath；缺失时回退内置默认，保证接口可用） */
    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource(promptFile);
            if (resource.exists()) {
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load weekly prompt file {}: {}", promptFile, e.getMessage());
        }
        return "你是车队战绩群的锐评官，根据提供的周报摘要，用中文写一段 100 字以内的毒舌但善意的锐评，"
                + "直接点名 MVP 与战犯，语气幽默，不要使用 markdown 格式。";
    }

    /**
     * 非流式调用 chat/completions：{ model, messages, temperature, max_tokens, stream:false }。
     * 非 200 抛状态异常；正文为空返回 null（由调用方重试），其余情况返回正文
     */
    private String callAi(String systemPrompt, String userContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", false);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        // 与对局分析一致：关闭思考模式直接出正文（周锐评不需要思维链）
        payload.put("chat_template_kwargs", Map.of("thinking", false));
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)));

        HttpPost post = new HttpPost(baseUrl + "/chat/completions");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Authorization", "Bearer " + apiKey);
        post.setHeader("User-Agent", BROWSER_USER_AGENT);
        try {
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload),
                    ContentType.APPLICATION_JSON));
        } catch (Exception e) {
            log.error("Failed to serialize weekly AI payload: {}", e.getMessage());
            throw new IllegalStateException("周锐评请求组装失败");
        }
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
            if (status != HttpStatus.OK.value()) {
                log.error("Weekly AI API error: status={}, body={}", status, body);
                throw new IllegalStateException("AI 接口调用失败（HTTP " + status + "），请稍后重试");
            }
            JsonNode choice = objectMapper.readTree(body).path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText("");
            String content = choice.path("message").path("content").asText("");
            if (content.isBlank()) {
                // 正文为空：多为推理模型把输出预算耗在思维链（finish_reason=length），
                // 返回 null 由调用方重试；finish_reason 落日志便于确认根因
                log.warn("Weekly AI returned empty content, finishReason={}", finishReason);
                return null;
            }
            return content;
        } catch (IOException e) {
            log.error("Weekly AI API request failed: {}", e.getMessage());
            throw new IllegalStateException("AI 接口调用失败，请检查网络与 API Key");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Weekly AI response parse failed: {}", e.getMessage());
            throw new IllegalStateException("AI 返回数据异常，请稍后重试");
        }
    }
}
