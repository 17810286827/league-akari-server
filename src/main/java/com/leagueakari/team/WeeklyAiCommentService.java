package com.leagueakari.team;

import com.leagueakari.common.exception.ErrorCode;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.web.SseEventSender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.PromptLoader;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.ai.AiStreamHandler;
import com.leagueakari.config.AiProperties;
import com.leagueakari.dto.team.WeeklyReportResponse;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 车队周报 AI 锐评服务（流式版，工单 #33 / ADR 0007）：
 * 周报统计数据与 AI 锐评拆分——锐评经独立 SSE 端点流式输出（打字机效果），
 * 周报统计接口不再被 AI 生成时间拖慢。
 * <p>HTTP 调用统一走公共 {@link AiClient}（见 docs/adr/0005），本服务只负责
 * 业务编排：周报聚合委托 {@link WeeklyReportService}（口径唯一，不二次实现）、
 * 摘要组装、提示词加载、缓存与 SSE 事件协议（与单局 AI 分析一致）。</p>
 * <p>缓存：按周标签缓存生成结果（10 分钟 TTL）——同一周重复生成不重复计费；
 * 命中时直接推送缓存全文（fromCache=true）。历史周持久化落库见工单 T2（#39）。</p>
 * <p>SSE 事件协议与单局分析一致：
 * start(fromCache) / chunk / reasoning / reasoning-reset / done(truncated) / error；
 * 重试门控遵循 ADR 0006 根治结论（正文已推送不可重试；仅思维链可重试并先推 reset）。</p>
 */
@Slf4j
@Service
public class WeeklyAiCommentService {

    /** 缓存有效期：10 分钟（毫秒）。周报数据一周一变，10 分钟足够覆盖页内反复刷新 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** 缓存条目：锐评文本 + 写入时间戳（Lombok @Value 不可变对象） */
    @Value
    private static class CacheEntry {

        /** 周报锐评正文 */
        String comment;

        /** 缓存写入时间（毫秒时间戳） */
        long timestamp;
    }

    /** 提示词加载器：文件读取 + 内置默认回退（全项目唯一实现，架构清理 T7） */
    private final PromptLoader promptLoader;

    /** 周锐评提示词文件（classpath，可直接编辑，改动即时生效） */
    private final String promptFile;

    /** 采样参数（组装后经 AiCompletionRequest 显式传给 AiClient，见 docs/adr/0005） */
    private final AiCompletionRequest completionRequest;

    /** 流式零内容失败重试次数（yaml ai.retry-count，不含首次；与三个 AI 场景统一） */
    private final int retryCount;

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    /** 周报聚合服务：流式锐评的素材来源（聚合口径唯一出处） */
    private final WeeklyReportService weeklyReportService;

    /** 流式锐评专用线程池（与单局分析共用 aiStreamExecutor，见 HttpClientConfig） */
    private final Executor executor;

    /** 缓存：周标签 → 锐评条目（成功才缓存，失败下次重试） */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 构造注入：AI 配置统一取自 AiProperties（yaml 唯一真值，见 docs/adr/0004） */
    public WeeklyAiCommentService(
            AiProperties ai,
            AiClient aiClient,
            ObjectMapper objectMapper,
            PromptLoader promptLoader,
            WeeklyReportService weeklyReportService,
            Executor aiStreamExecutor) {
        this.promptFile = ai.getWeeklyPromptFile();
        this.promptLoader = promptLoader;
        // 周锐评场景采样参数：无 penalty（保持既有采样行为）；thinking 跟随 yaml（ai.thinking），
        // 三个 AI 场景统一读同一开关；thinkingBudget 限制思维链上限（防推理模型耗尽输出预算、正文为空）
        this.completionRequest = new AiCompletionRequest(
                ai.getModel(), ai.getTemperature(),
                null, null, ai.getWeeklyMaxTokens(), ai.isThinking(), ai.getThinkingBudget());
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.weeklyReportService = weeklyReportService;
        this.executor = aiStreamExecutor;
        // 重试次数 yaml 统一配置（三个 AI 场景同一键），构造时快照
        this.retryCount = ai.getRetryCount();
    }

    /**
     * 前置校验：API Key 已配置。
     * 供 controller 在返回 SseEmitter 前同步调用——校验失败时由全局异常处理器
     * 在 HTTP 响应阶段返回明确错误（业务码 4101），避免流已建立后再中断。
     * 周报聚合（roster 校验等）在流内执行，失败以 error 事件收尾（锐评区提示，
     * 周报统计部分不受影响）
     */
    public void validateAndConfigured() {
        // Key 状态判定统一走 AiClient（架构清理 T7：消除各服务自判的真相分裂）
        if (!aiClient.isConfigured()) {
            log.error("AI API key not configured, weekly comment skipped");
            throw new BizException(ErrorCode.AI_KEY_MISSING, "AI API Key 未配置，无法生成周报锐评");
        }
    }

    /**
     * 流式生成周报锐评并推送 SSE 事件（异步执行，不阻塞 controller）：
     * 命中缓存时直接推送缓存全文；未命中时流式调用 AI，逐块推送增量片段。
     * 事件契约与单局 AI 分析一致（见类 javadoc）
     *
     * @param anyDayOfWeek 该周内任意一天；null 表示上一周（与周报统计接口同语义）
     * @param emitter      前端 SSE 连接（由 controller 创建并返回）
     */
    public void streamComment(LocalDate anyDayOfWeek, SseEmitter emitter) {
        // 提交到专用线程池：SseEmitter 由异步线程推送，controller 立即返回响应头
        log.info("Weekly AI comment stream submitted to executor: date={}", anyDayOfWeek);
        executor.execute(() -> doStreamComment(anyDayOfWeek, emitter));
    }

    /**
     * 流式锐评主流程（在线程池中执行）：
     * 周报聚合 → 缓存判定 →（未命中）组装摘要流式调 AI 逐块推送 → 写缓存收尾。
     * 每个关键节点打印耗时日志，便于定位"无响应"时卡在哪个环节
     */
    private void doStreamComment(LocalDate anyDayOfWeek, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        try {
            // 周报聚合：口径唯一出处（周边界/车队局判定/七榜单/名场面），本服务不二次实现；
            // 失败（roster 未配置 1101 等）由外层统一 error 收尾——锐评区提示，统计部分不受影响
            WeeklyReportResponse report = weeklyReportService.weeklyReport(anyDayOfWeek);
            String weekLabel = report.getWeekLabel();
            log.info("Weekly AI comment report loaded: week={}, elapsed={}ms",
                    weekLabel, System.currentTimeMillis() - startTime);

            // 缓存命中：10 分钟内重复请求直接推送缓存全文（标记 fromCache，前端提示）
            CacheEntry cached = cache.get(weekLabel);
            if (cached != null && System.currentTimeMillis() - cached.getTimestamp() < CACHE_TTL_MS) {
                log.info("Weekly AI comment cache hit: week={}", weekLabel);
                SseEventSender.send(emitter, objectMapper, "start", Map.of("fromCache", true));
                SseEventSender.send(emitter, objectMapper, "chunk", Map.of("content", cached.getComment()));
                SseEventSender.send(emitter, objectMapper, "done", Map.of());
                emitter.complete();
                return;
            }
            // 过期清理：超过 10 分钟视为失效，重新调用 AI
            cache.remove(weekLabel);

            String summary = buildSummary(report);
            // 提示词：文件读取 + 内置默认回退，每次读取 md 文件（用户编辑后即时生效，无需重启）
            String systemPrompt = promptLoader.load(promptFile,
                    "你是车队战绩群的锐评官，根据提供的周报摘要，用中文写一段 100 字以内的毒舌但善意的锐评，"
                            + "直接点名 MVP 与战犯，语气幽默，不要使用 markdown 格式。");

            // 先推送 start（含缓存标记），再逐块推送增量文本；start 只推一次（重试不重推）
            SseEventSender.send(emitter, objectMapper, "start", Map.of("fromCache", false));
            log.info("Weekly AI comment start event sent: week={}, elapsed={}ms",
                    weekLabel, System.currentTimeMillis() - startTime);
            // 最终锐评正文（content 拼接，写入缓存）；思考过程（reasoning）单独透传不进缓存
            StringBuilder full = new StringBuilder();
            // 流式重试门控：正文已推送（chunk）后失败不可重试——重发内容会在前端打字机里重复展示；
            // 仅思维链已推送可重试（重试前推 reasoning-reset 清空前端思维链缓冲，ADR 0006）
            boolean[] contentStreamed = {false};
            boolean[] reasoningStreamed = {false};
            String finishReason = null;
            int maxAttempts = retryCount + 1;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    if (attempt > 1) {
                        // 重试对前端不可见（零内容失败）；首次已推思维链则先 reset
                        if (reasoningStreamed[0]) {
                            SseEventSender.send(emitter, objectMapper, "reasoning-reset", Map.of());
                        }
                        log.warn("Weekly AI comment stream retrying: week={}, attempt={}/{}",
                                weekLabel, attempt, maxAttempts);
                    }
                    finishReason = aiClient.callStream(completionRequest, systemPrompt, summary,
                            new AiStreamHandler() {
                                @Override
                                public void onContent(String chunk) {
                                    full.append(chunk);
                                    contentStreamed[0] = true;
                                    SseEventSender.send(emitter, objectMapper, "chunk", Map.of("content", chunk));
                                }

                                @Override
                                public void onReasoning(String chunk) {
                                    reasoningStreamed[0] = true;
                                    SseEventSender.send(emitter, objectMapper, "reasoning", Map.of("content", chunk));
                                }
                            }, "weekly:" + weekLabel);
                    // 流自然结束但正文为空（推理模型把预算耗尽）：与零内容失败同语义纳入重试
                    if (full.isEmpty()) {
                        throw new BizException(ErrorCode.AI_API_ERROR, "AI 返回内容为空，请稍后重试");
                    }
                    break;
                } catch (BizException e) {
                    // 正文已推送或重试额度耗尽：放弃重试上抛，外层统一 error 收尾
                    if (contentStreamed[0] || attempt == maxAttempts) {
                        throw e;
                    }
                    log.warn("Weekly AI comment stream failed before any content, will retry: "
                            + "week={}, attempt={}, reason={}", weekLabel, attempt, e.getMessage());
                }
            }
            // finishReason：stop=自然完成；length=输出预算耗尽被截断（正文可能不完整）
            boolean truncated = "length".equals(finishReason);
            // 完成：写入缓存（成功才缓存，失败不缓存下次重试）并推送 done
            cache.put(weekLabel, new CacheEntry(full.toString(), System.currentTimeMillis()));
            SseEventSender.send(emitter, objectMapper, "done", truncated ? Map.of("truncated", true) : Map.of());
            emitter.complete();
            log.info("Weekly AI comment stream completed: week={}, length={}, truncated={}, elapsed={}ms",
                    weekLabel, full.length(), truncated, System.currentTimeMillis() - startTime);
        } catch (SseEventSender.ClientDisconnectedException e) {
            // 客户端已断开（关页面/刷新/网络断开）：预期现象——停止上游消费即可，
            // 不推 error、不调 complete（连接已断均无意义），仅记 INFO 无堆栈
            log.info("Weekly AI comment stream stopped: client disconnected, elapsed={}ms",
                    System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 流式中途失败（连接已建立，HTTP 状态无法变更）：推送 error 事件后关闭连接，前端据此提示；
            // 打印完整堆栈便于定位
            log.error("Weekly AI comment stream failed: elapsed={}ms",
                    System.currentTimeMillis() - startTime, e);
            try {
                SseEventSender.send(emitter, objectMapper, "error", Map.of("message", e.getMessage()));
                emitter.complete();
            } catch (Exception sendError) {
                // 推送失败（连接已断开/未初始化）：仅记日志
                log.warn("Failed to send weekly AI comment error event", sendError);
            }
        }
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
