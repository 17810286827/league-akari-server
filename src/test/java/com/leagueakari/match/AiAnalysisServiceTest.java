package com.leagueakari.match;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.ai.AiStreamHandler;
import com.leagueakari.config.AiProperties;
import com.leagueakari.dto.MatchDetailResponse;
import com.leagueakari.entity.MatchParticipant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.leagueakari.gamedata.GameDataService;

/**
 * AiAnalysisService 单元测试（业务编排层；HTTP 细节由 AiClientTest 覆盖）：
 * 通过 mock AiClient（以脚本回放流式增量）与 mock SseEmitter（捕获推送事件）验证——
 * 流式推送（chunk/reasoning 分流 + done）、缓存命中二次推送全文、AI 失败推 error 事件、
 * 客户端断开零 ERROR 停流、摘要组装（英雄/装备 ID 转中文名）、前置校验。
 * 线程池以同步执行器（Runnable::run）注入，保证断言时序
 */
class AiAnalysisServiceTest {

    private AiClient aiClient;
    private MatchQueryService matchQueryService;
    private GameDataService gameDataService;
    private AiAnalysisService service;
    private ObjectMapper objectMapper;

    /** 捕获 SseEmitter 推送的事件（顺序追加） */
    private final List<Map<String, Object>> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        matchQueryService = mock(MatchQueryService.class);
        gameDataService = mock(GameDataService.class);
        objectMapper = new ObjectMapper();
        // promptFile 指向不存在的文件：走内置默认提示词，避免依赖 classpath 资源
        service = new AiAnalysisService(
                aiProps("test-key", false),
                matchQueryService, objectMapper, aiClient, new com.leagueakari.ai.PromptLoader(),
                gameDataService, Runnable::run);
        events.clear();
    }

    /** 构造 AI 配置（yaml 唯一真值的测试替身；thinking/apiKey 按用例调整） */
    private AiProperties aiProps(String apiKey, boolean thinking) {
        AiProperties props = new AiProperties();
        props.setBaseUrl("https://ai.test");
        props.setApiKey(apiKey);
        props.setModel("test-model");
        props.setPromptFile("ai/not-exist.md");
        props.setThinking(thinking);
        props.setTemperature(0.7);
        props.setFrequencyPenalty(0.6);
        props.setPresencePenalty(0.3);
        props.setMaxTokens(2048);
        return props;
    }

    /**
     * 让 mock AiClient 按脚本回放流式增量（模拟推理模式：思维链与正文交替）：
     * 增量经真实回调链路（服务里的 handler → send → emitter）推送，事件顺序可断言。
     * 回调内抛出的异常（客户端断开信号）也会按真实语义穿透
     */
    private void mockAiStream(String finishReason) {
        doAnswer(inv -> {
            AiStreamHandler handler = inv.getArgument(3);
            handler.onReasoning("正在分析");
            handler.onContent("你好");
            handler.onReasoning("继续推理");
            handler.onContent("，世界");
            return finishReason;
        }).when(aiClient).callStream(any(AiCompletionRequest.class), anyString(), anyString(), any(), anyString());
    }

    /** 创建 mock SseEmitter：把每次 send 的 data（JSON 字符串）解析为 Map 存入 events */
    private SseEmitter mockEmitter() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(inv -> {
            SseEmitter.SseEventBuilder builder = inv.getArgument(0);
            // build() 返回事件数据集合：data(Object) 会产生"data:"前缀与换行两个 TEXT_PLAIN 文本 +
            // 真正的数据对象（mediaType=null），只解析数据对象
            for (SseEmitter.DataWithMediaType data : builder.build()) {
                if (data.getMediaType() != null) {
                    continue;
                }
                try {
                    events.add(objectMapper.readValue((String) data.getData(), Map.class));
                } catch (Exception e) {
                    // 测试数据解析失败视为测试错误：立即抛出
                    throw new IllegalStateException("Failed to parse SSE event in test", e);
                }
            }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        return emitter;
    }

    /** 构造含我方/对手各一人的对局详情（self 在 100 队） */
    private MatchDetailResponse buildDetail() {
        MatchParticipant self = new MatchParticipant();
        self.setPuuid("p1");
        self.setSummonerName("玩家一");
        self.setChampionId(1);
        self.setTeamId(100);
        self.setWin(true);
        self.setKills(10);
        self.setDeaths(2);
        self.setAssists(5);
        // stats 快照含出装（item0-6），供摘要转换用例验证装备名
        self.setStatsJson("{\"totalDamageDealtToChampions\": 20000, \"item0\": 6672, \"item1\": 6609}");

        MatchParticipant enemy = new MatchParticipant();
        enemy.setPuuid("p2");
        enemy.setSummonerName("玩家二");
        enemy.setChampionId(2);
        enemy.setTeamId(200);
        enemy.setWin(false);
        enemy.setStatsJson("{}");

        MatchDetailResponse detail = new MatchDetailResponse();
        detail.setGameId(123L);
        detail.setGameMode("CLASSIC");
        detail.setGameDuration(1800);
        detail.setSelfPuuid("p1");
        detail.setParticipants(List.of(self, enemy));
        return detail;
    }

    /**
     * 用例：客户端中途断开（SSE 推送抛 AsyncRequestNotUsableException/Broken pipe）
     * <p>预期行为：立即停止流式推送与上游消费，<b>不推 error 事件、不调 complete</b>
     * （连接已断，推送无意义），且<b>不产生任何 ERROR 日志</b>——客户端断开是预期现象
     * （关页面/刷新），不是服务端故障，同一断开不得在多层打 ERROR 堆栈刷屏。</p>
     */
    @Test
    void clientDisconnectStopsStreamWithoutErrorEvent() throws Exception {
        // 捕获 AiAnalysisService 日志：断开场景应零 ERROR
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(AiAnalysisService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);

        try {
            // AI 流回放：start 后第一个回调是 reasoning，会触发第二次 send（此时客户端已断开）
            mockAiStream(null);
            when(matchQueryService.getMatchDetail(123L)).thenReturn(buildDetail());

            SseEmitter emitter = mock(SseEmitter.class);
            AtomicInteger sendCount = new AtomicInteger();
            doAnswer(inv -> {
                if (sendCount.incrementAndGet() >= 2) {
                    // 第二次推送（reasoning）时客户端已断开：模拟 SSE 输出失效
                    throw new AsyncRequestNotUsableException(
                            "ServletOutputStream failed to flush: java.io.IOException: Broken pipe");
                }
                // 第一次推送（start）正常：解析进 events 供断言
                SseEmitter.SseEventBuilder builder = inv.getArgument(0);
                for (SseEmitter.DataWithMediaType data : builder.build()) {
                    if (data.getMediaType() == null) {
                        events.add(objectMapper.readValue((String) data.getData(), Map.class));
                    }
                }
                return null;
            }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

            service.analyzeStream(123L, emitter);

            // 断开后流立即终止：只有 start 事件，没有 error/done（连接已断，无需也无法推送）
            assertThat(events).extracting(e -> e.get("type")).containsExactly("start");
            // 不调用 complete()：连接已断，complete 无意义
            verify(emitter, never()).complete();
            // 不再尝试第三次推送（error 事件）：断开即停，不做无意义的推送
            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            // 日志契约：客户端断开是预期事件，全程零 ERROR（不刷堆栈）
            assertThat(appender.list)
                    .noneMatch(evt -> evt.getLevel() == Level.ERROR);
        } finally {
            serviceLogger.detachAppender(appender);
        }
    }

    @Test
    void streamAnalysisPushesChunksThenDone() throws Exception {
        // AI 回放推理流（思维链 + 正文交替），finish_reason=null（自然结束）
        mockAiStream(null);
        when(matchQueryService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.analyzeStream(123L, mockEmitter());

        // 事件顺序：start → reasoning → chunk → reasoning → chunk → done；
        // 思维链与正文分别推送，正文按序拼接
        assertThat(events).hasSize(6);
        assertThat(events.get(0)).containsEntry("type", "start").containsEntry("fromCache", false);
        assertThat(events.get(1)).containsEntry("type", "reasoning").containsEntry("content", "正在分析");
        assertThat(events.get(2)).containsEntry("type", "chunk").containsEntry("content", "你好");
        assertThat(events.get(3)).containsEntry("type", "reasoning").containsEntry("content", "继续推理");
        assertThat(events.get(4)).containsEntry("type", "chunk").containsEntry("content", "，世界");
        assertThat(events.get(5)).containsEntry("type", "done");
    }

    @Test
    void cacheHitPushesFullTextWithoutCallingAi() throws Exception {
        // 第一次：流式分析成功（写缓存；缓存只含正文，不含思维链）
        mockAiStream(null);
        when(matchQueryService.getMatchDetail(123L)).thenReturn(buildDetail());
        service.analyzeStream(123L, mockEmitter());
        assertThat(events.get(5)).containsEntry("type", "done");

        // 第二次：命中缓存，直接推送正文全文（fromCache=true），不再调用 AI 接口
        events.clear();
        service.analyzeStream(123L, mockEmitter());
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).containsEntry("type", "start").containsEntry("fromCache", true);
        assertThat(events.get(1)).containsEntry("type", "chunk").containsEntry("content", "你好，世界");
        assertThat(events.get(2)).containsEntry("type", "done");
        // 第一次分析调用过 1 次 AI；第二次命中缓存不再新增调用
        verify(aiClient, times(1)).callStream(any(AiCompletionRequest.class), anyString(), anyString(), any(), anyString());
    }

    @Test
    void aiErrorPushesErrorEvent() throws Exception {
        // AI 调用失败（AiClient 转 IllegalStateException）：流中推送 error 事件（含状态码提示）
        when(aiClient.callStream(any(AiCompletionRequest.class), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("AI 接口调用失败（HTTP 500），请稍后重试"));
        when(matchQueryService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.analyzeStream(123L, mockEmitter());

        // start 之后紧跟 error，不再有 done
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("type", "start");
        assertThat(events.get(1)).containsEntry("type", "error");
        assertThat((String) events.get(1).get("message")).contains("HTTP 500");
    }

    @Test
    void matchNotFoundPushesErrorEvent() throws Exception {
        // 流式线程内取详情失败（校验后数据被删等）：推送 error 事件而非崩溃
        when(matchQueryService.getMatchDetail(123L)).thenThrow(new MatchNotFoundException(123L));

        service.analyzeStream(123L, mockEmitter());

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("type", "error");
        assertThat((String) events.get(0).get("message")).contains("对局不存在");
    }

    @Test
    void summaryConvertsIdsToGameDataNames() throws Exception {
        // 摘要组装：英雄/装备 ID 在模型调用前经 GameDataService 转换为中文名，
        // 模型不再按 ID 猜英雄（非思考模式下会猜错，如 103 → 瑞兹）
        when(gameDataService.championName(1)).thenReturn("黑暗之女");
        when(gameDataService.itemName(6672)).thenReturn("收集者");
        when(gameDataService.itemName(6609)).thenReturn("巨蛇之牙");
        mockAiStream(null);
        when(matchQueryService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.analyzeStream(123L, mockEmitter());

        // 用户消息（对局摘要）应包含转换后的中文英雄名与装备名
        ArgumentCaptor<String> userContent = ArgumentCaptor.forClass(String.class);
        verify(aiClient).callStream(any(AiCompletionRequest.class), anyString(),
                userContent.capture(), any(), anyString());
        assertThat(userContent.getValue()).contains("黑暗之女").contains("收集者").contains("巨蛇之牙");
    }

    @Test
    void truncatedStreamMarksDoneEvent() throws Exception {
        // 输出被长度预算截断（finish_reason=length）：done 事件携带 truncated=true，
        // 前端据此提示"内容被截断"，避免"写一半"被误认为完整
        mockAiStream("length");
        when(matchQueryService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.analyzeStream(123L, mockEmitter());

        assertThat(events.get(events.size() - 1))
                .containsEntry("type", "done")
                .containsEntry("truncated", true);
    }

    @Test
    void emptyStreamThrowsAndPushesError() throws Exception {
        // 流无任何增量（服务端提前断开等）：正文为空 → 推 error 事件，不写缓存
        when(aiClient.callStream(any(AiCompletionRequest.class), anyString(), anyString(), any(), anyString()))
                .thenReturn(null);
        when(matchQueryService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.analyzeStream(123L, mockEmitter());

        assertThat(events.get(events.size() - 1)).containsEntry("type", "error");
        assertThat((String) events.get(events.size() - 1).get("message")).contains("内容为空");
        // 失败不缓存：再次分析会重新调用 AI
        service.analyzeStream(123L, mockEmitter());
        verify(aiClient, times(2)).callStream(any(AiCompletionRequest.class), anyString(), anyString(), any(), anyString());
    }

    @Test
    void validateFailsWithoutApiKey() {
        // API Key 未配置（Key 状态由 AiClient.isConfigured 提供，架构清理 T7）：前置校验直接抛
        when(aiClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.validateAndConfigured(123L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void validatePassesWithKeyAndExistingMatch() {
        // 配置齐全（Key 状态走 AiClient.isConfigured，架构清理 T7 统一真相）+ 对局存在：校验通过
        when(aiClient.isConfigured()).thenReturn(true);
        when(matchQueryService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.validateAndConfigured(123L);
    }

    @Test
    void validateThrowsWhenMatchMissing() {
        // 对局不存在：前置校验抛 MatchNotFoundException（由全局处理器转 404）
        when(aiClient.isConfigured()).thenReturn(true);
        when(matchQueryService.getMatchDetail(123L)).thenThrow(new MatchNotFoundException(123L));

        assertThatThrownBy(() -> service.validateAndConfigured(123L))
                .isInstanceOf(MatchNotFoundException.class);
    }
}
