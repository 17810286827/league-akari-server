package com.leagueakari.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.PromptLoader;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.ai.AiStreamHandler;
import com.leagueakari.config.AiProperties;
import com.leagueakari.dto.MatchDetailResponse;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.util.ClientDisconnectDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * AI 对局表现分析（service 层）：
 * 取指定对局详情 → 组装"系统提示词（md 文件，可编辑）+ 对局数据摘要" →
 * 流式调用 opencode go 的 chat/completions（模型取配置 ai.model，经
 * chat_template_kwargs.thinking=false 关闭思考模式，直接输出正文）→
 * 解析 SSE 增量并逐块推送给前端（打字机效果）。
 * 结果做 JVM 缓存（按 gameId，2 分钟过期），过期前重复分析直接推送缓存全文（fromCache=true）。
 * <p>AI HTTP 调用统一走公共 {@link AiClient}（见 docs/adr/0005），本服务只负责
 * 业务编排：摘要组装、提示词加载、缓存与 SSE 事件协议。</p>
 */
@Slf4j
@Service
public class AiAnalysisService {

    /** 缓存条目：分析文本 + 写入时间戳（2 分钟过期判定） */
    private record CacheEntry(String analysis, long timestamp) {}

    /** 缓存有效期：2 分钟（毫秒） */
    private static final long CACHE_TTL_MS = 2 * 60 * 1000L;

    /** 系统提示词文件路径（classpath，md 格式，可直接编辑） */
    private final String promptFile;

    /** 采样参数（组装后经 AiCompletionRequest 显式传给 AiClient，见 docs/adr/0005） */
    private final AiCompletionRequest completionRequest;

    private final MatchQueryService matchQueryService;
    private final ObjectMapper objectMapper;
    private final AiClient aiClient;
    /** 提示词加载器：文件读取 + 内置默认回退（全项目唯一实现，架构清理 T7） */
    private final PromptLoader promptLoader;

    /** 游戏资源数据服务：英雄/装备 ID → 中文名（模型调用前转换，避免模型瞎猜 ID） */
    private final GameDataService gameDataService;

    /** 流式分析专用线程池（见 HttpClientConfig.aiStreamExecutor） */
    private final Executor executor;

    /** JVM 缓存：gameId → 缓存条目（2 分钟过期；成功才缓存，失败不缓存下次重试） */
    private final Map<Long, CacheEntry> analysisCache = new ConcurrentHashMap<>();

    /**
     * 构造注入：AI 配置统一取自 AiProperties（yaml 唯一真值，见 docs/adr/0004），
     * 在构造阶段组装本场景的采样参数对象；HTTP 调用下沉到公共 AiClient
     */
    public AiAnalysisService(
            AiProperties ai,
            MatchQueryService matchQueryService,
            ObjectMapper objectMapper,
            AiClient aiClient,
            PromptLoader promptLoader,
            GameDataService gameDataService,
            Executor aiStreamExecutor) {
        this.promptLoader = promptLoader;
        this.promptFile = ai.getPromptFile();
        // 单局分析场景采样参数：penalty 抑制长文本重复，thinking 可经配置开启（前端灰字展示思维链）
        this.completionRequest = new AiCompletionRequest(
                ai.getModel(), ai.getTemperature(),
                ai.getFrequencyPenalty(), ai.getPresencePenalty(),
                ai.getMaxTokens(), ai.isThinking());
        this.matchQueryService = matchQueryService;
        this.objectMapper = objectMapper;
        this.aiClient = aiClient;
        this.gameDataService = gameDataService;
        this.executor = aiStreamExecutor;
    }

    /**
     * 前置校验：API Key 已配置 + 对局存在。
     * 供 controller 在返回 SseEmitter 前同步调用——校验失败时由全局异常处理器
     * 在 HTTP 响应阶段返回明确错误（503/404），避免流已建立后再中断
     *
     * @param gameId 对局 ID
     * @throws IllegalStateException   API Key 未配置
     * @throws MatchNotFoundException 对局不存在
     */
    public void validateAndConfigured(Long gameId) {
        long startTime = System.currentTimeMillis();
        // Key 状态判定统一走 AiClient（架构清理 T7：消除各服务自判的真相分裂）
        if (!aiClient.isConfigured()) {
            log.error("AI API key not configured, analysis skipped: gameId={}", gameId);
            throw new IllegalStateException("AI API Key 未配置，无法进行对局分析");
        }
        // 对局不存在时抛 MatchNotFoundException（全局处理器转 404）；
        // 记录耗时：若此处卡住（DB 慢/连接池耗尽）会导致响应头迟迟不返回
        matchQueryService.getMatchDetail(gameId);
        log.info("AI analysis validated: gameId={}, elapsed={}ms", gameId, System.currentTimeMillis() - startTime);
    }

    /**
     * 流式分析指定对局并推送 SSE 事件（异步执行，不阻塞 controller）：
     * 命中缓存时直接推送缓存全文；未命中时流式调用 AI，逐块推送增量片段。
     * <p>SSE 事件协议（data 均为 JSON）：</p>
     * <ul>
     *   <li>{@code {"type":"start","fromCache":bool}} —— 开始，携带是否命中缓存</li>
     *   <li>{@code {"type":"chunk","content":"..."}} —— 分析文本增量片段（逐块到达）</li>
     *   <li>{@code {"type":"reasoning","content":"..."}} —— 思维链增量片段（前端灰字展示）</li>
     *   <li>{@code {"type":"done"}} —— 正常结束（被截断时携带 truncated=true）</li>
     *   <li>{@code {"type":"error","message":"..."}} —— 中途失败（随后关闭连接）</li>
     * </ul>
     *
     * @param gameId  对局 ID（需先通过 validateAndConfigured 校验）
     * @param emitter 前端 SSE 连接（由 controller 创建并返回）
     */
    public void analyzeStream(Long gameId, SseEmitter emitter) {
        // 提交到专用线程池：SseEmitter 由异步线程推送，controller 立即返回响应头
        log.info("AI analysis stream submitted to executor: gameId={}", gameId);
        executor.execute(() -> doAnalyzeStream(gameId, emitter));
    }

    /**
     * 流式分析主流程（在线程池中执行）：
     * 缓存命中 → 推送全文；未命中 → 取详情组装摘要 → 流式调 AI 逐块推送 → 写缓存收尾。
     * 每个关键节点打印耗时日志，便于定位"无响应"时卡在哪个环节
     */
    private void doAnalyzeStream(Long gameId, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        log.info("AI analysis stream started: gameId={}", gameId);
        try {
            // 缓存命中：2 分钟内重复分析直接推送缓存全文（标记 fromCache，前端提示）
            CacheEntry cached = analysisCache.get(gameId);
            if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
                log.info("AI analysis cache hit: gameId={}, elapsed={}ms",
                        gameId, System.currentTimeMillis() - startTime);
                send(emitter, "start", Map.of("fromCache", true));
                send(emitter, "chunk", Map.of("content", cached.analysis()));
                send(emitter, "done", Map.of());
                emitter.complete();
                log.info("AI analysis cache served: gameId={}, length={}, elapsed={}ms",
                        gameId, cached.analysis().length(), System.currentTimeMillis() - startTime);
                return;
            }
            // 过期清理：超过 2 分钟视为失效，重新调用 AI
            analysisCache.remove(gameId);

            // 取对局详情并组装紧凑数据摘要（只提取分析所需字段，控制 prompt 体积）
            MatchDetailResponse detail = matchQueryService.getMatchDetail(gameId);
            String matchSummary = buildMatchSummary(detail);
            // 提示词：文件读取 + 内置默认回退，每次读取 md 文件（用户编辑后即时生效，无需重启）
            String systemPrompt = promptLoader.load(promptFile,
                    "你是一名资深的英雄联盟对局分析师，请根据提供的对局数据，用中文分析查询玩家（self）的本局表现，"
                            + "包含 KDA/经济/伤害/承伤/关键表现等维度，最后给出总结评分。");
            log.info("AI analysis payload prepared: gameId={}, summaryLength={}, elapsed={}ms",
                    gameId, matchSummary.length(), System.currentTimeMillis() - startTime);

            // 先推送 start（含缓存标记），再逐块推送增量文本；
            // start 事件同时也是响应头的 flush 时机——前端收到响应头即代表执行到此处
            send(emitter, "start", Map.of("fromCache", false));
            log.info("AI analysis start event sent: gameId={}, elapsed={}ms",
                    gameId, System.currentTimeMillis() - startTime);
            // 最终分析正文（content 拼接，写入缓存）；思考过程（reasoning）单独透传不进缓存
            StringBuilder full = new StringBuilder();
            // 流式调用公共 AiClient：SSE 解析与增量分发在回调中完成，
            // 回调内抛出的断开信号原样穿透、立即停止上游消费；日志上下文带 gameId 便于排障关联
            String finishReason = aiClient.callStream(completionRequest, systemPrompt, matchSummary,
                    new AiStreamHandler() {
                        @Override
                        public void onContent(String chunk) {
                            full.append(chunk);
                            send(emitter, "chunk", Map.of("content", chunk));
                        }

                        @Override
                        public void onReasoning(String chunk) {
                            send(emitter, "reasoning", Map.of("content", chunk));
                        }
                    }, "gameId=" + gameId);
            // finishReason：stop=自然完成；length=输出预算耗尽被截断（正文可能不完整）
            boolean truncated = "length".equals(finishReason);
            if (full.isEmpty()) {
                throw new IllegalStateException("AI 返回内容为空，请稍后重试");
            }
            // 完成：写入缓存（成功才缓存，失败不缓存下次重试）并推送 done
            // （被截断时 done 携带 truncated=true，前端提示用户，避免"写一半"被误认为完整）
            analysisCache.put(gameId, new CacheEntry(full.toString(), System.currentTimeMillis()));
            send(emitter, "done", truncated ? Map.of("truncated", true) : Map.of());
            emitter.complete();
            log.info("AI analysis stream completed: gameId={}, length={}, truncated={}, elapsed={}ms",
                    gameId, full.length(), truncated, System.currentTimeMillis() - startTime);
        } catch (ClientDisconnectedException e) {
            // 客户端已断开（关页面/刷新/网络断开）：预期现象而非服务端故障——
            // 停止上游消费与推送即可，不推 error 事件、不调 complete（连接已断，均无意义），
            // 仅记 INFO 无堆栈，避免同一断开在多层重复打 ERROR 堆栈刷屏
            log.info("AI analysis stream stopped: client disconnected, gameId={}, elapsed={}ms",
                    gameId, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 流式中途失败（连接已建立，HTTP 状态无法变更）：推送 error 事件后关闭连接，前端据此提示；
            // 打印完整堆栈——SseEmitter 未初始化竞态/连接异常等仅凭 message 无法定位
            log.error("AI analysis stream failed: gameId={}, elapsed={}ms", gameId,
                    System.currentTimeMillis() - startTime, e);
            try {
                send(emitter, "error", Map.of("message", e.getMessage()));
                emitter.complete();
            } catch (Exception sendError) {
                // 推送失败（连接已断开/未初始化，如前端关闭页面）：仅记日志
                log.warn("Failed to send AI analysis error event, gameId={}", gameId, sendError);
            }
        }
    }

    /**
     * 客户端断开信号：SSE 推送因对端关闭连接（Broken pipe 等）失败时由 {@link #send}
     * 抛出，用于让流式主流程<b>立即终止</b>（停止上游 AI 流消费与后续推送），
     * 并与普通推送失败（IllegalStateException，需推 error 事件）区分处理
     */
    private static class ClientDisconnectedException extends RuntimeException {
        ClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * 组装对局数据摘要：只提取 **self 所在队伍 5 人** 的精选字段（分析目标是"我方"，
     * 战犯/战神/奉献队友都来自本队），输出紧凑 JSON 字符串作为 user 消息。
     * 字段名刻意用短键（kda/dmg/taken/gold/cs…），含义已在系统提示词的"数据字段说明"中约定——
     * 实测推理模型的首 token 耗时与输入 token 数正相关，精简摘要可缩短思考前的处理时间
     */
    private String buildMatchSummary(MatchDetailResponse detail) {
        List<Map<String, Object>> players = new ArrayList<>();
        // self 队伍 ID：以 selfPuuid 对应参与者所在队为准
        Integer selfTeamId = detail.getParticipants().stream()
                .filter(p -> p.getPuuid() != null && p.getPuuid().equals(detail.getSelfPuuid()))
                .map(MatchParticipant::getTeamId)
                .findFirst()
                .orElse(null);
        for (MatchParticipant p : detail.getParticipants()) {
            // 只保留 self 所在队伍（我方）5 人
            if (selfTeamId != null && !selfTeamId.equals(p.getTeamId())) {
                continue;
            }
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("name", p.getSummonerName());
            // 英雄 ID 在调用前经 GameDataService 转换为中文名（如 103 → 阿狸）：
            // 模型按 ID 猜英雄在非思考模式下会出错（103 猜成瑞兹），转换后不再依赖模型记忆；
            // 数据源不可用时回退 ID 字符串，由提示词的 ID 对照表兜底
            player.put("champ", gameDataService.championName(p.getChampionId()));
            player.put("win", p.getWin());
            // KDA 合并为数组，减少 JSON 体积（[击杀, 死亡, 助攻]）
            player.put("kda", List.of(p.getKills(), p.getDeaths(), p.getAssists()));
            player.put("self", p.getPuuid() != null && p.getPuuid().equals(detail.getSelfPuuid()));
            // 从 statsJson 提取关键统计（缺失字段跳过，由 AI 侧自行处理）
            try {
                JsonNode stats = objectMapper.readTree(p.getStatsJson());
                if (stats != null) {
                    // 出装 7 槽（item0-6，出装平衡评分修正用）：同样转换为中文名（收集者/巨蛇之牙…）
                    List<String> items = new ArrayList<>();
                    for (int i = 0; i < 7; i++) {
                        if (stats.has("item" + i) && !stats.get("item" + i).isNull()) {
                            items.add(gameDataService.itemName(stats.get("item" + i).asInt()));
                        }
                    }
                    if (!items.isEmpty()) {
                        player.put("items", items);
                    }
                    extract(stats, player, "dmg", "totalDamageDealtToChampions");
                    extract(stats, player, "taken", "totalDamageTaken");
                    extract(stats, player, "gold", "goldEarned");
                    extract(stats, player, "cs", "totalMinionsKilled");
                    extract(stats, player, "jg", "neutralMinionsKilled");
                    extract(stats, player, "wards", "wardsPlaced");
                    extract(stats, player, "vision", "visionScore");
                    extract(stats, player, "cc", "timeCCingOthers");
                    // 最大连杀：doubleKills 计 2、tripleKills 计 3……取最大值（连杀体现爆发）
                    int multiKill = 0;
                    multiKill = Math.max(multiKill, stats.path("doubleKills").asInt() > 0 ? 2 : 0);
                    multiKill = Math.max(multiKill, stats.path("tripleKills").asInt() > 0 ? 3 : 0);
                    multiKill = Math.max(multiKill, stats.path("quadraKills").asInt() > 0 ? 4 : 0);
                    multiKill = Math.max(multiKill, stats.path("pentaKills").asInt() > 0 ? 5 : 0);
                    if (multiKill > 0) {
                        player.put("multiKill", multiKill);
                    }
                    // 击杀参与率（challenges 独有，SGP 数据）
                    if (stats.has("challenges") && stats.get("challenges").has("killParticipation")) {
                        player.put("kp", stats.get("challenges").get("killParticipation").asDouble());
                    }
                }
            } catch (Exception e) {
                // 单名参与者 statsJson 解析失败不影响整体（缺失字段由 AI 侧跳过）
                log.warn("Failed to parse statsJson for player {}: {}", p.getSummonerName(), e.getMessage());
            }
            players.add(player);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("gameId", detail.getGameId());
        summary.put("mode", detail.getGameMode());
        summary.put("duration", detail.getGameDuration());
        summary.put("selfPuuid", detail.getSelfPuuid());
        summary.put("players", players);
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.error("Failed to serialize match summary: {}", e.getMessage());
            throw new IllegalStateException("对局数据组装失败");
        }
    }

    /** 提取 statsJson 数值字段到玩家摘要（输出短键，来源为 statsJson 长键；缺失跳过） */
    private void extract(JsonNode stats, Map<String, Object> player, String shortKey, String statsKey) {
        if (stats.has(statsKey) && !stats.get(statsKey).isNull()) {
            player.put(shortKey, stats.get(statsKey).asDouble());
        }
    }

    /**
     * 推送一条 SSE 事件：data 为 JSON 字符串（type 字段 + 业务字段）；
     * 推送失败（连接已断开/未初始化）转为运行时异常，终止流式流程由外层统一收尾。
     * 失败时打印完整堆栈——SseEmitter 未初始化竞态（send 早于 controller 返回）只能靠堆栈识别
     *
     * @param emitter SSE 连接
     * @param type    事件类型（start/chunk/reasoning/done/error）
     * @param payload 业务字段（写入 data JSON）
     */
    private void send(SseEmitter emitter, String type, Map<String, Object> payload) {
        try {
            // LinkedHashMap 保证 type 位于 JSON 首位，便于前端/测试解析
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.putAll(payload);
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            if (ClientDisconnectDetector.isClientDisconnect(e)) {
                // 客户端已断开（关页面/刷新/网络闪断）：预期现象——INFO 无堆栈，
                // 抛专用信号让流式主流程立即终止（停止上游消费与后续推送）
                log.info("SSE client disconnected, stop pushing: type={}", type);
                throw new ClientDisconnectedException(e);
            }
            // 其余推送失败（SseEmitter 未初始化竞态等）：完整堆栈，由外层统一收尾
            log.error("SSE send failed: type={}, payload={}", type, payload, e);
            throw new IllegalStateException("SSE 推送失败: " + e.getMessage());
        }
    }
}
