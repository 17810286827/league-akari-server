package com.leagueakari.team;

import com.leagueakari.common.exception.ErrorCode;

import com.leagueakari.common.exception.BizException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.PromptLoader;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.config.AiProperties;
import com.leagueakari.dto.team.WeeklyReportResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /** 提示词加载器：文件读取 + 内置默认回退（全项目唯一实现，架构清理 T7） */
    private final PromptLoader promptLoader;

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
            ObjectMapper objectMapper,
            PromptLoader promptLoader) {
        this.promptFile = ai.getWeeklyPromptFile();
        this.promptLoader = promptLoader;
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
     * AI Key 未配置 / 接口失败 / 重试后正文仍为空时抛 BizException(AI_API_ERROR)
     */
    public String generateComment(WeeklyReportResponse report) {
        // apiKey 前置校验收敛到 AiClient 重载（架构清理 T7）；缓存命中（历史成功产物）不受 Key 状态影响
        // 缓存命中：同一周直接复用（10 分钟内）
        CacheEntry cached = cache.get(report.getWeekLabel());
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            log.info("Weekly AI comment cache hit: week={}", report.getWeekLabel());
            return cached.comment();
        }
        cache.remove(report.getWeekLabel());

        String summary = buildSummary(report);
        // 提示词：文件读取 + 内置默认回退（PromptLoader 唯一实现）
        String systemPrompt = promptLoader.load(promptFile,
                "你是车队战绩群的锐评官，根据提供的周报摘要，用中文写一段 100 字以内的毒舌但善意的锐评，"
                        + "直接点名 MVP 与战犯，语气幽默，不要使用 markdown 格式。");
        // apiKey 前置校验与空正文重试由 AiClient 重载承载（架构清理 T7；2 = 首次 + 1 次重试）
        String comment = aiClient.call(completionRequest, systemPrompt, summary,
                "weekly:" + report.getWeekLabel(), 2);
        if (comment == null) {
            throw new BizException(ErrorCode.AI_API_ERROR, "AI 返回内容为空，请稍后重试");
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
            throw new BizException(ErrorCode.DATA_ASSEMBLY_FAILED, "周报摘要组装失败", e);
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

}
