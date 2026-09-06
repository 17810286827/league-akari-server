package com.leagueakari.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.ai.AiStreamHandler;
import com.leagueakari.ai.PromptLoader;
import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.common.web.SseEventSender;
import com.leagueakari.config.AiProperties;
import com.leagueakari.dto.replay.ReplayResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * AI 复盘叙述服务（工单 #40 / spec #29，ADR 0008"规则算点 + AI 叙述"）：
 * AI 只消费转折点列表 + 复盘摘要做解读，**不喂原始 frames**——
 * 转折点由规则引擎确定性提取（真实数据背书），AI 幻觉无从发生。
 * <p>事件契约与单局分析/周报锐评一致（start/chunk/reasoning/reasoning-reset/
 * done/error），重试门控沿用 ADR 0006 根治结论。手动按钮触发（没兴趣的局不花钱），
 * 结果 JVM 缓存 2 分钟（fromCache 标记）。</p>
 */
@Slf4j
@Service
public class ReplayAiService {

    /** 缓存有效期：2 分钟（毫秒）——同单局分析口径 */
    private static final long CACHE_TTL_MS = 2 * 60 * 1000L;

    /** 系统提示词文件路径（classpath，可编辑即时生效） */
    private final String promptFile;

    /** 采样参数 */
    private final AiCompletionRequest completionRequest;

    /** 流式零内容失败重试次数 */
    private final int retryCount;

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final ReplayService replayService;
    private final Executor executor;

    /** JVM 缓存：gameId → 叙述文本 + 时间戳（成功才缓存） */
    private final Map<Long, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 缓存条目（Lombok @Value 不可变对象） */
    @lombok.Value
    private static class CacheEntry {
        String narration;
        long timestamp;
    }

    public ReplayAiService(AiProperties ai, AiClient aiClient, ObjectMapper objectMapper,
            PromptLoader promptLoader, ReplayService replayService, Executor aiStreamExecutor) {
        this.promptFile = ai.getReplayPromptFile();
        this.completionRequest = new AiCompletionRequest(
                ai.getModel(), ai.getTemperature(),
                ai.getFrequencyPenalty(), ai.getPresencePenalty(),
                ai.getMaxTokens(), ai.isThinking(), ai.getThinkingBudget());
        this.retryCount = ai.getRetryCount();
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.replayService = replayService;
        this.executor = aiStreamExecutor;
    }

    /**
     * 前置校验：API Key 已配置 + 对局有时间线数据（复盘叙述的前提）。
     * 供 controller 在返回 SseEmitter 前同步调用——无时间线对局按钮降级的
     * 服务端语义（2002），避免流建立后再中断
     */
    public void validateAndConfigured(Long gameId) {
        if (!aiClient.isConfigured()) {
            log.error("AI API key not configured, replay narration skipped: gameId={}", gameId);
            throw new BizException(ErrorCode.AI_KEY_MISSING, "AI API Key 未配置，无法生成复盘叙述");
        }
        ReplayResponse replay = replayService.replay(gameId);
        if (!replay.isAvailable()) {
            // 无时间线：复盘叙述无从谈起（曲线都没有），2002 语义
            throw new BizException(ErrorCode.TIMELINE_NOT_FOUND,
                    "对局时间线不存在: gameId=" + gameId);
        }
    }

    /**
     * 流式生成复盘叙述并推送 SSE 事件（异步执行）：
     * 输入 = 转折点列表 + 复盘摘要（经济差走势/击杀/视角），不含原始 frames
     */
    public void streamComment(Long gameId, SseEmitter emitter) {
        log.info("Replay AI narration submitted to executor: gameId={}", gameId);
        executor.execute(() -> doStreamComment(gameId, emitter));
    }

    /** 流式叙述主流程（线程池中执行） */
    private void doStreamComment(Long gameId, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        try {
            // 缓存命中：2 分钟内重复叙述直接推全文
            CacheEntry cached = cache.get(gameId);
            if (cached != null && System.currentTimeMillis() - cached.getTimestamp() < CACHE_TTL_MS) {
                log.info("Replay AI narration cache hit: gameId={}", gameId);
                SseEventSender.send(emitter, objectMapper, "start", Map.of("fromCache", true));
                SseEventSender.send(emitter, objectMapper, "chunk", Map.of("content", cached.getNarration()));
                SseEventSender.send(emitter, objectMapper, "done", Map.of());
                emitter.complete();
                return;
            }
            cache.remove(gameId);

            ReplayResponse replay = replayService.replay(gameId);
            if (!replay.isAvailable()) {
                // 双重防御：流内发现无时间线以 error 收尾（正常路径已被前置校验拦截）
                throw new BizException(ErrorCode.TIMELINE_NOT_FOUND,
                        "对局时间线不存在: gameId=" + gameId);
            }
            String summary = buildSummary(replay);
            String systemPrompt = promptLoader.load(promptFile,
                    "你是车队教练，以整个团队为视角复盘这一局：串联关键转折点讲清局势何时倒向"
                            + "哪边、为什么，成员表现客观中性不甩锅，最后给一句改进建议。"
                            + "200 字以内中文，可使用 markdown 加粗关键结论。");

            SseEventSender.send(emitter, objectMapper, "start", Map.of("fromCache", false));
            StringBuilder full = new StringBuilder();
            boolean[] contentStreamed = {false};
            boolean[] reasoningStreamed = {false};
            String finishReason = null;
            int maxAttempts = retryCount + 1;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    if (attempt > 1) {
                        if (reasoningStreamed[0]) {
                            SseEventSender.send(emitter, objectMapper, "reasoning-reset", Map.of());
                        }
                        log.warn("Replay AI narration retrying: gameId={}, attempt={}/{}",
                                gameId, attempt, maxAttempts);
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
                            }, "replay:" + gameId);
                    if (full.isEmpty()) {
                        throw new BizException(ErrorCode.AI_API_ERROR, "AI 返回内容为空，请稍后重试");
                    }
                    break;
                } catch (BizException e) {
                    if (contentStreamed[0] || attempt == maxAttempts) {
                        throw e;
                    }
                    log.warn("Replay AI narration failed before any content, will retry: gameId={}, reason={}",
                            gameId, e.getMessage());
                }
            }
            boolean truncated = "length".equals(finishReason);
            cache.put(gameId, new CacheEntry(full.toString(), System.currentTimeMillis()));
            SseEventSender.send(emitter, objectMapper, "done", truncated ? Map.of("truncated", true) : Map.of());
            emitter.complete();
            log.info("Replay AI narration completed: gameId={}, length={}, elapsed={}ms",
                    gameId, full.length(), System.currentTimeMillis() - startTime);
        } catch (SseEventSender.ClientDisconnectedException e) {
            log.info("Replay AI narration stopped: client disconnected, gameId={}", gameId);
        } catch (Exception e) {
            log.error("Replay AI narration failed: gameId={}, elapsed={}ms",
                    gameId, System.currentTimeMillis() - startTime, e);
            try {
                SseEventSender.send(emitter, objectMapper, "error", Map.of("message", e.getMessage()));
                emitter.complete();
            } catch (Exception sendError) {
                log.warn("Failed to send replay AI narration error event, gameId={}", gameId, sendError);
            }
        }
    }

    /**
     * 组装备盘摘要（user 消息）：视角 + 经济差走势概要 + 击杀列表 + 转折点明细。
     * 只给 AI 真实数据背书的素材（转折点由规则引擎确定性提取），不给原始 frames
     */
    private String buildSummary(ReplayResponse replay) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("perspectiveTeamId", replay.getPerspectiveTeamId());
        // 经济差走势：首/末/最大/最小（概要，不逐帧）
        List<ReplayResponse.GoldDiffPoint> series = replay.getGoldDiffSeries();
        if (!series.isEmpty()) {
            double first = series.get(0).getGoldDiff();
            double last = series.get(series.size() - 1).getGoldDiff();
            double max = series.stream().mapToDouble(ReplayResponse.GoldDiffPoint::getGoldDiff).max().orElse(0);
            double min = series.stream().mapToDouble(ReplayResponse.GoldDiffPoint::getGoldDiff).min().orElse(0);
            summary.put("goldDiff", Map.of(
                    "start", first, "end", last, "max", max, "min", min,
                    "durationMin", series.get(series.size() - 1).getTimestampMs() / 60000));
        }
        // 击杀事件（压缩为时刻 + 敌我 + 击杀者→被击杀者）
        List<Map<String, Object>> kills = new ArrayList<>();
        for (ReplayResponse.KillEvent kill : replay.getKillEvents()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("min", kill.getTimestampMs() / 60000);
            k.put("by", (kill.isKillerIsPerspective() ? "我方" : "敌方") + kill.getKillerChampion());
            k.put("victim", kill.getVictimChampion());
            kills.add(k);
        }
        summary.put("kills", kills);
        // 转折点明细（AI 叙述的主素材——每个点都有真实数据背书）
        List<Map<String, Object>> points = new ArrayList<>();
        for (ReplayResponse.TurningPoint point : replay.getTurningPoints()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", point.getType());
            p.put("min", point.getTimestampMs() / 60000);
            p.put("goldDiff", Math.round(point.getGoldDiff()));
            p.put("detail", point.getDetail());
            points.add(p);
        }
        summary.put("turningPoints", points);
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new BizException(ErrorCode.DATA_ASSEMBLY_FAILED, "复盘摘要组装失败", e);
        }
    }
}
