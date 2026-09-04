package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.config.AiProperties;
import com.leagueakari.dto.WeeklyReportResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车队周报 AI 锐评服务（业务编排层）：
 * 把周报聚合摘要组装为一次 AI 调用（非流式），输出一段可直接发群的锐评文本。
 * 独立于 TeamStatsService——聚合口径不依赖 AI，AI 失败由调用方优雅降级。
 * <p>HTTP 调用统一走公共 {@link AiClient}（见 docs/adr/0005），本服务只负责
 * 业务编排：摘要组装、提示词加载、缓存与空正文重试。</p>
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

    /** API Key（环境变量 AI_API_KEY；前置校验用） */
    private final String apiKey;

    /** 周锐评提示词文件（classpath，可直接编辑，改动即时生效） */
    private final String promptFile;

    /** 采样参数（组装后经 AiCompletionRequest 显式传给 AiClient，见 docs/adr/0005） */
    private final AiCompletionRequest completionRequest;

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    /** 缓存：周标签 → 锐评条目（成功才缓存，失败下次重试） */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 构造注入：AI 配置统一取自 AiProperties（yaml 唯一真值，见 docs/adr/0004） */
    public WeeklyAiCommentService(
            AiProperties ai,
            AiClient aiClient,
            ObjectMapper objectMapper) {
        this.apiKey = ai.getApiKey();
        this.promptFile = ai.getWeeklyPromptFile();
        // 周锐评场景采样参数：无 penalty（保持既有采样行为），关闭思考直出正文
        this.completionRequest = new AiCompletionRequest(
                ai.getModel(), ai.getTemperature(),
                null, null, ai.getWeeklyMaxTokens(), false);
        this.aiClient = aiClient;
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
            comment = aiClient.call(completionRequest, systemPrompt, summary,
                    "weekly:" + report.getWeekLabel());
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
}
