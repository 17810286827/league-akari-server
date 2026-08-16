package com.leagueakari.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MatchDetailResponse;
import com.leagueakari.entity.MatchParticipant;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * AI 对局表现分析（service 层）：
 * 取指定对局详情 → 组装"系统提示词（md 文件，可编辑）+ 对局数据摘要" →
 * 流式调用 opencode go 的 chat/completions（stream=true，模型 deepseek-v4-flash，
 * 经 chat_template_kwargs.thinking=false 关闭思考模式，直接输出正文）→
 * 解析 SSE 增量并逐块推送给前端（打字机效果）。
 * 结果做 JVM 缓存（按 gameId，2 分钟过期），过期前重复分析直接推送缓存全文（fromCache=true）。
 * HTTP 调用走 Apache HttpClient 5（全局连接池实例），替换原 RestTemplate 方案
 */
@Slf4j
@Service
public class AiAnalysisService {

    /** 缓存条目：分析文本 + 写入时间戳（2 分钟过期判定） */
    private record CacheEntry(String analysis, long timestamp) {}

    /** 缓存有效期：2 分钟（毫秒） */
    private static final long CACHE_TTL_MS = 2 * 60 * 1000L;

    /** 浏览器 UA：opencode go 经 Cloudflare 防护，无 UA/程序化请求会被 403/503 拦截 */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** opencode go API 基础地址 */
    private final String baseUrl;

    /** API Key（application.yml ai.api-key，环境变量 AI_API_KEY 可覆盖） */
    private final String apiKey;

    /** 分析模型 */
    private final String model;

    /** 系统提示词文件路径（classpath，md 格式，可直接编辑） */
    private final String promptFile;

    /**
     * 是否开启模型思考模式（application.yml ai.thinking，默认 false）：
     * 开启 → 模型先输出长思维链再出正文，整流慢（约 90s）但分析更细致、英雄识别更准；
     * 关闭 → 直接输出正文，整流快（约 25s），配合提示词中的英雄 ID 映射保证识别准确
     */
    private final boolean thinking;

    /** 采样温度（ai.temperature）：降低随机性，抑制长文本重复 */
    private final double temperature;

    /** 频率惩罚（ai.frequency-penalty）：惩罚已出现过的词，抑制循环重复 */
    private final double frequencyPenalty;

    /** 存在惩罚（ai.presence-penalty）：鼓励引入新话题，减少车轱辘话 */
    private final double presencePenalty;

    /**
     * 输出 token 上限（ai.max-tokens）：思维链与正文共享预算。
     * deepseek-v4-flash 思考模式会无限展开思维链（实测最长 2046 块/90s+），
     * 设 2048 后思维链被预算压力收敛（549 块），整流 38.9s，正文仍完整输出
     */
    private final int maxTokens;

    private final MatchService matchService;
    private final ObjectMapper objectMapper;
    private final CloseableHttpClient httpClient;

    /** 游戏资源数据服务：英雄/装备 ID → 中文名（模型调用前转换，避免模型瞎猜 ID） */
    private final GameDataService gameDataService;

    /** 流式分析专用线程池（见 HttpClientConfig.aiStreamExecutor） */
    private final Executor executor;

    /** JVM 缓存：gameId → 缓存条目（2 分钟过期；成功才缓存，失败不缓存下次重试） */
    private final Map<Long, CacheEntry> analysisCache = new ConcurrentHashMap<>();

    /**
     * 构造注入配置与依赖
     */
    public AiAnalysisService(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key:}") String apiKey,
            @Value("${ai.model:deepseek-v4-flash}") String model,
            @Value("${ai.prompt-file:ai/system-prompt.md}") String promptFile,
            @Value("${ai.thinking:false}") boolean thinking,
            @Value("${ai.temperature:0.7}") double temperature,
            @Value("${ai.frequency-penalty:0.6}") double frequencyPenalty,
            @Value("${ai.presence-penalty:0.3}") double presencePenalty,
            @Value("${ai.max-tokens:2048}") int maxTokens,
            MatchService matchService,
            ObjectMapper objectMapper,
            CloseableHttpClient httpClient,
            GameDataService gameDataService,
            Executor aiStreamExecutor) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.promptFile = promptFile;
        this.thinking = thinking;
        this.temperature = temperature;
        this.frequencyPenalty = frequencyPenalty;
        this.presencePenalty = presencePenalty;
        this.maxTokens = maxTokens;
        this.matchService = matchService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
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
        if (apiKey == null || apiKey.isBlank()) {
            log.error("AI API key not configured, analysis skipped: gameId={}", gameId);
            throw new IllegalStateException("AI API Key 未配置，无法进行对局分析");
        }
        // 对局不存在时抛 MatchNotFoundException（全局处理器转 404）；
        // 记录耗时：若此处卡住（DB 慢/连接池耗尽）会导致响应头迟迟不返回
        matchService.getMatchDetail(gameId);
        log.info("AI analysis validated: gameId={}, elapsed={}ms", gameId, System.currentTimeMillis() - startTime);
    }

    /**
     * 流式分析指定对局并推送 SSE 事件（异步执行，不阻塞 controller）：
     * 命中缓存时直接推送缓存全文；未命中时流式调用 AI，逐块推送增量片段。
     * <p>SSE 事件协议（data 均为 JSON）：</p>
     * <ul>
     *   <li>{@code {"type":"start","fromCache":bool}} —— 开始，携带是否命中缓存</li>
     *   <li>{@code {"type":"chunk","content":"..."}} —— 分析文本增量片段（逐块到达）</li>
     *   <li>{@code {"type":"done"}} —— 正常结束</li>
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
            MatchDetailResponse detail = matchService.getMatchDetail(gameId);
            String matchSummary = buildMatchSummary(detail);
            // 系统提示词：每次读取 md 文件（用户编辑后即时生效，无需重启）
            String systemPrompt = loadSystemPrompt();
            log.info("AI analysis payload prepared: gameId={}, summaryLength={}, elapsed={}ms",
                    gameId, matchSummary.length(), System.currentTimeMillis() - startTime);

            // 先推送 start（含缓存标记），再逐块推送增量文本；
            // start 事件同时也是响应头的 flush 时机——前端收到响应头即代表执行到此处
            send(emitter, "start", Map.of("fromCache", false));
            log.info("AI analysis start event sent: gameId={}, elapsed={}ms",
                    gameId, System.currentTimeMillis() - startTime);
            // 最终分析正文（content 拼接，写入缓存）；思考过程（reasoning）单独透传不进缓存
            StringBuilder full = new StringBuilder();
            // finishReason：stop=自然完成；length=输出预算耗尽被截断（正文可能不完整）
            String finishReason = callAiStream(systemPrompt, matchSummary, gameId, startTime,
                    chunk -> {
                        full.append(chunk);
                        send(emitter, "chunk", Map.of("content", chunk));
                    },
                    reasoning -> send(emitter, "reasoning", Map.of("content", reasoning)));
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
     * 读取系统提示词文件（classpath，UTF-8）；
     * 文件缺失时返回内置默认提示词，保证接口可用
     */
    private String loadSystemPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource(promptFile);
            if (resource.exists()) {
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load AI prompt file {}: {}", promptFile, e.getMessage());
        }
        return "你是一名资深的英雄联盟对局分析师，请根据提供的对局数据，用中文分析查询玩家（self）的本局表现，"
                + "包含 KDA/经济/伤害/承伤/关键表现等维度，最后给出总结评分。";
    }

    /**
     * 流式调用 opencode go 的 chat/completions（OpenAI 兼容格式）：
     * 请求体 { model, messages: [system, user], stream: true }。
     * 实测 deepseek-v4-flash 在 opencode 网关为推理模式：流中先输出大量
     * delta.reasoning_content（思维链），最后才输出 delta.content（最终回答），
     * 无法通过请求参数关闭——因此两者都解析：思维链交 reasoningConsumer（前端灰字展示，
     * 让用户看到模型"正在思考"而非无响应），正文交 chunkConsumer。
     * 关键节点打日志：请求发出、响应状态、首块耗时（TTFT）、块数/字数统计
     *
     * @param systemPrompt       系统提示词
     * @param userContent        对局数据摘要
     * @param gameId             对局 ID（仅日志）
     * @param startTime          流开始时间戳（毫秒，计算各节点耗时）
     * @param chunkConsumer      正文增量消费者（delta.content，每收到一个调用一次）
     * @param reasoningConsumer  思维链增量消费者（delta.reasoning_content，每收到一个调用一次）
     * @return 流结束原因（finish_reason）：stop=自然完成，length=预算截断，未返回时 null
     * @throws IllegalStateException AI 接口错误（非 200 / 网络异常 / 数据异常）
     */
    private String callAiStream(String systemPrompt, String userContent, Long gameId, long startTime,
            Consumer<String> chunkConsumer, Consumer<String> reasoningConsumer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", true);
        // 采样参数：抑制长文本重复输出（deepseek-v4-flash 生成超长内容时易循环重复，
        // temperature 降随机性、frequency_penalty 惩罚已出现词、presence_penalty 鼓励新话题；
        // 实测该组合输出无重复且字数落在提示词要求区间）
        payload.put("temperature", temperature);
        payload.put("frequency_penalty", frequencyPenalty);
        payload.put("presence_penalty", presencePenalty);
        // 输出上限：思维链与正文共享预算，限制推理模型的无限思考（实测 2048 时整流 90s→39s，
        // 思维链重复基本消除；若正文被截断（finish_reason=length）可调大该值）
        payload.put("max_tokens", maxTokens);
        // 思考模式开关（ai.thinking）：关闭时用 DeepSeek 原生参数 chat_template_kwargs.thinking=false
        // 直接输出正文（整流 90s → 25s）；开启时输出思维链（前端灰字展示，兼容 reasoning 事件）。
        // 网关不识别 reasoning_effort/thinking.type 等 OpenAI 风格参数，只认该 DeepSeek 原生参数
        if (!thinking) {
            payload.put("chat_template_kwargs", Map.of("thinking", false));
        }
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)
        ));

        HttpPost post = new HttpPost(baseUrl + "/chat/completions");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Authorization", "Bearer " + apiKey);
        post.setHeader("User-Agent", BROWSER_USER_AGENT);
        try {
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));
        } catch (JsonProcessingException e) {
            // 请求体序列化失败：配置/数据异常，不可能发生（payload 结构固定）
            log.error("Failed to serialize AI request payload: {}", e.getMessage());
            throw new IllegalStateException("AI 请求组装失败");
        }
        // 请求发出前打日志：若此处之后长时间无日志，说明卡在连接建立/TLS 握手
        log.info("AI API request starting: gameId={}, url={}, model={}, elapsed={}ms",
                gameId, baseUrl + "/chat/completions", model, System.currentTimeMillis() - startTime);
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            // 非 200：读取错误体（OpenAI 兼容接口返回 JSON 错误）后抛出
            int status = response.getCode();
            log.info("AI API response received: gameId={}, status={}, elapsed={}ms",
                    gameId, status, System.currentTimeMillis() - startTime);
            if (status != HttpStatus.OK.value()) {
                String errBody = response.getEntity() != null
                        ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
                log.error("AI API error: gameId={}, status={}, body={}", gameId, status, errBody);
                throw new IllegalStateException("AI 接口调用失败（HTTP " + status + "），请稍后重试");
            }
            // 逐行解析 SSE 流：空行分隔事件，data: 前缀承载 JSON；[DONE] 表示结束。
            // 记录首块耗时（TTFT）与块数/字数——思维链或正文任一到达都算有数据，
            // 避免"模型在思考但看似无响应"的误判
            int chunkCount = 0;
            int reasoningCount = 0;
            int totalChars = 0;
            long firstChunkAt = -1L;
            String finishReason = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.getEntity().getContent(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        log.info("AI API stream done marker: gameId={}, chunks={}, reasoning={}, chars={}, elapsed={}ms",
                                gameId, chunkCount, reasoningCount, totalChars,
                                System.currentTimeMillis() - startTime);
                        break;
                    }
                    // 推理模式增量在 delta.reasoning_content，正文在 delta.content
                    JsonNode choice = objectMapper.readTree(data).path("choices").path(0);
                    // finish_reason 在流的最后一个 chunk 携带：stop=自然完成 / length=预算截断
                    if (choice.hasNonNull("finish_reason")) {
                        finishReason = choice.get("finish_reason").asText();
                    }
                    JsonNode delta = choice.path("delta");
                    String content = delta.path("content").asText("");
                    String reasoning = delta.path("reasoning_content").asText("");
                    if (firstChunkAt < 0 && (!content.isEmpty() || !reasoning.isEmpty())) {
                        // 首块到达时间：连接建立后到第一个数据块的延迟（模型响应速度指标）
                        firstChunkAt = System.currentTimeMillis();
                        log.info("AI API first data received: gameId={}, elapsed={}ms",
                                gameId, firstChunkAt - startTime);
                    }
                    if (!content.isEmpty()) {
                        chunkCount++;
                        totalChars += content.length();
                        chunkConsumer.accept(content);
                    }
                    if (!reasoning.isEmpty()) {
                        reasoningCount++;
                        reasoningConsumer.accept(reasoning);
                    }
                }
            }
            // 正常退出循环但没看到 [DONE]（如流被服务器提前断开）也能走到这里，靠 done 事件兜底
            log.info("AI API stream read finished: gameId={}, chunks={}, reasoning={}, chars={}, "
                    + "finishReason={}, elapsed={}ms",
                    gameId, chunkCount, reasoningCount, totalChars, finishReason,
                    System.currentTimeMillis() - startTime);
            return finishReason;
        } catch (IllegalStateException e) {
            // 业务错误（非 200 等）：原样上抛，由流式主流程转 error 事件
            throw e;
        } catch (JsonProcessingException | org.apache.hc.core5.http.ParseException e) {
            // SSE 数据解析失败：模型返回非预期格式
            log.error("Failed to parse AI stream data: gameId={}", gameId, e);
            throw new IllegalStateException("AI 返回数据异常，请稍后重试");
        } catch (IOException e) {
            // 网络/超时等客户端异常：完整堆栈（区分连接超时/读超时/SSL 异常）
            log.error("AI API request failed: gameId={}, elapsed={}ms", gameId,
                    System.currentTimeMillis() - startTime, e);
            throw new IllegalStateException("AI 接口调用失败，请检查网络与 API Key");
        }
    }

    /**
     * 推送一条 SSE 事件：data 为 JSON 字符串（type 字段 + 业务字段）；
     * 推送失败（连接已断开/未初始化）转为运行时异常，终止流式流程由外层统一收尾。
     * 失败时打印完整堆栈——SseEmitter 未初始化竞态（send 早于 controller 返回）只能靠堆栈识别
     *
     * @param emitter SSE 连接
     * @param type    事件类型（start/chunk/done/error）
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
            // 推送失败：连接已断开（如前端关闭页面）或 SseEmitter 尚未初始化（竞态），无需继续推送
            log.error("SSE send failed: type={}, payload={}", type, payload, e);
            throw new IllegalStateException("SSE 推送失败: " + e.getMessage());
        }
    }
}
