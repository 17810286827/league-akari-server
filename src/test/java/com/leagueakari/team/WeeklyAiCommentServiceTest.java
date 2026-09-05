package com.leagueakari.team;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.ai.AiStreamHandler;
import com.leagueakari.config.AiProperties;
import com.leagueakari.dto.team.WeeklyReportResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
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

/**
 * WeeklyAiCommentService 单元测试（流式改造，工单 #33）：
 * 通过 mock AiClient（脚本回放流式增量）与 mock SseEmitter（捕获推送事件）验证——
 * 流式推送（chunk/reasoning 分流 + done）、缓存命中二次推送全文、AI 失败推 error 事件、
 * 客户端断开零 ERROR 停流、重试门控（正文已推送不可重试 / 仅思维链可重试并推 reset）、
 * 摘要携带周报素材、前置校验（Key 未配置）。
 * 线程池以同步执行器（Runnable::run）注入，保证断言时序。
 * 周报聚合本身由 mocked WeeklyReportService 提供（聚合口径由 WeeklyReportServiceTest 覆盖）
 */
class WeeklyAiCommentServiceTest {

    private AiClient aiClient;
    private WeeklyReportService weeklyReportService;
    private WeeklyAiCommentService service;
    private ObjectMapper objectMapper;

    /** 捕获 SseEmitter 推送的事件（顺序追加） */
    private final List<Map<String, Object>> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        weeklyReportService = mock(WeeklyReportService.class);
        objectMapper = new ObjectMapper();
        // promptFile 指向不存在的文件：走内置默认提示词，避免依赖 classpath 资源
        service = new WeeklyAiCommentService(
                aiProps("test-key"), aiClient, objectMapper,
                new com.leagueakari.ai.PromptLoader(), weeklyReportService, Runnable::run);
        events.clear();
    }

    /** 构造 AI 配置（yaml 唯一真值的测试替身；重试 1 次 = 零内容失败后最多再试 1 次） */
    private AiProperties aiProps(String apiKey) {
        AiProperties props = new AiProperties();
        props.setBaseUrl("https://ai.test");
        props.setApiKey(apiKey);
        props.setModel("test-model");
        props.setWeeklyPromptFile("ai/not-exist.md");
        props.setThinking(true);
        props.setTemperature(1.0);
        props.setWeeklyMaxTokens(512);
        props.setRetryCount(1);
        return props;
    }

    /** 构造最小周报：仅含 AI 摘要会用到的字段（weekLabel 即缓存键） */
    private WeeklyReportResponse report(String weekLabel) {
        return WeeklyReportResponse.builder()
                .weekLabel(weekLabel)
                .overview(WeeklyReportResponse.Overview.builder()
                        .gameCount(3).winCount(2).lossCount(1)
                        .busiestDay("2026-08-26")
                        .activeMembers(List.of("赌书消得泼茶香#iKun", "手裂鬼子#tw2"))
                        .build())
                .mvpBoard(List.of(WeeklyReportResponse.BoardEntry.builder()
                        .riotId("赌书消得泼茶香#iKun").value(2.0).detail("MVP×1 SVP×1").build()))
                .criminalBoard(List.of(WeeklyReportResponse.BoardEntry.builder()
                        .riotId("手裂鬼子#tw2").value(4.0).detail("2场").build()))
                .build();
    }

    /** 打桩周报聚合：按日期返回指定周标签的周报（聚合细节不在本测试范围） */
    private void stubReport(String weekLabel) {
        when(weeklyReportService.weeklyReport(any(LocalDate.class))).thenReturn(report(weekLabel));
    }

    /**
     * 让 mock AiClient 按脚本回放流式增量（模拟推理模式：思维链与正文交替）：
     * 增量经真实回调链路（服务里的 handler → send → emitter）推送，事件顺序可断言。
     * 回调内抛出的异常（客户端断开信号）也会按真实语义穿透
     */
    private void mockAiStream(String finishReason) {
        doAnswer(inv -> {
            AiStreamHandler handler = inv.getArgument(3);
            handler.onReasoning("正在锐评");
            handler.onContent("本周赌书封神");
            handler.onReasoning("继续推理");
            handler.onContent("，鬼子战犯实锤");
            return finishReason;
        }).when(aiClient).callStream(any(AiCompletionRequest.class), anyString(), anyString(), any(), anyString());
    }

    /** 创建 mock SseEmitter：把每次 send 的 data（JSON 字符串）解析为 Map 存入 events */
    private SseEmitter mockEmitter() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(inv -> {
            captureEvent(inv.getArgument(0));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        return emitter;
    }

    /** 解析一次 SSE 事件构建器中的数据对象为 Map 存入 events（跳过媒体类型行） */
    private void captureEvent(SseEmitter.SseEventBuilder builder) {
        // build() 返回事件数据集合：data(Object) 会产生"data:"前缀与换行两个 TEXT_PLAIN 文本 +
        // 真正的数据对象（mediaType=null），只解析数据对象
        for (SseEmitter.DataWithMediaType data : builder.build()) {
            if (data.getMediaType() != null) {
                continue;
            }
            try {
                events.add(objectMapper.readValue((String) data.getData(), Map.class));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse SSE event in test", e);
            }
        }
    }

    /** 事件类型序列快捷断言 */
    private List<String> eventTypes() {
        return events.stream().map(e -> (String) e.get("type")).toList();
    }

    /** 用例：流式生成——start(fromCache=false) → reasoning/chunk 交替 → done，正文拼完整 */
    @Test
    void streamComment_pushesChunksThenDone() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("stop");
        SseEmitter emitter = mockEmitter();

        service.streamComment(WeekFixture.AUG_26, emitter);

        assertThat(eventTypes()).containsExactly(
                "start", "reasoning", "chunk", "reasoning", "chunk", "done");
        assertThat(events.get(0)).containsEntry("fromCache", false);
        // 正文 chunk 按到达顺序拼接为完整锐评
        String full = events.stream()
                .filter(e -> "chunk".equals(e.get("type")))
                .map(e -> (String) e.get("content"))
                .reduce("", String::concat);
        assertThat(full).isEqualTo("本周赌书封神，鬼子战犯实锤");
        verify(emitter).complete();
    }

    /** 用例：调用摘要携带周报素材（周标签/榜单成员），供锐评点名 */
    @Test
    void streamComment_sendsWeeklySummaryToAi() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("stop");
        SseEmitter emitter = mockEmitter();

        service.streamComment(WeekFixture.AUG_26, emitter);

        ArgumentCaptor<String> userContent = ArgumentCaptor.forClass(String.class);
        verify(aiClient).callStream(any(), anyString(), userContent.capture(), any(), anyString());
        assertThat(userContent.getValue())
                .contains("2026-08-24 ~ 2026-08-30").contains("MVP").contains("手裂鬼子");
    }

    /** 用例：周报请求的 thinking 跟随 yaml（ai.thinking）——三个 AI 场景统一读配置 */
    @Test
    void streamComment_requestThinkingFollowsYaml() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("stop");
        SseEmitter emitter = mockEmitter();

        service.streamComment(WeekFixture.AUG_26, emitter);

        ArgumentCaptor<AiCompletionRequest> req = ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(aiClient).callStream(req.capture(), anyString(), anyString(), any(), anyString());
        assertThat(req.getValue().isThinking()).isTrue();
    }

    /** 用例：缓存命中——同一周第二次流式不再调 AI，直接推送全文（fromCache=true） */
    @Test
    void streamComment_cacheHitPushesFullTextWithoutCallingAi() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("stop");
        service.streamComment(WeekFixture.AUG_26, mockEmitter());
        events.clear();

        service.streamComment(WeekFixture.AUG_26, mockEmitter());

        assertThat(eventTypes()).containsExactly("start", "chunk", "done");
        assertThat(events.get(0)).containsEntry("fromCache", true);
        // 缓存全文一次性推送
        assertThat(events.get(1)).containsEntry("content", "本周赌书封神，鬼子战犯实锤");
        verify(aiClient, times(1)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例：换周缓存不命中——重新生成（缓存键是周标签） */
    @Test
    void streamComment_differentWeekRegenerates() throws Exception {
        // 第一次请求由聚合服务返回 8 月周，第二次返回 9 月周（同一 date 打桩覆盖）
        when(weeklyReportService.weeklyReport(any(LocalDate.class)))
                .thenReturn(report("2026-08-24 ~ 2026-08-30"))
                .thenReturn(report("2026-08-31 ~ 2026-09-06"));
        mockAiStream("stop");

        service.streamComment(WeekFixture.AUG_26, mockEmitter());
        service.streamComment(WeekFixture.AUG_26, mockEmitter());

        verify(aiClient, times(2)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例：AI 调用失败（未推送任何增量）→ 推 error 事件并关闭连接 */
    @Test
    void streamComment_aiErrorPushesErrorEvent() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        when(aiClient.callStream(any(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new BizException(ErrorCode.AI_API_ERROR, "AI 接口调用失败（HTTP 502）"));
        SseEmitter emitter = mockEmitter();

        service.streamComment(WeekFixture.AUG_26, emitter);

        assertThat(eventTypes()).containsExactly("start", "error");
        assertThat(events.get(1).get("message")).asString().contains("502");
        verify(emitter).complete();
    }

    /** 用例：客户端断开（推送中途 Broken pipe）→ 停流不推 error、不调 complete */
    @Test
    void streamComment_clientDisconnectStopsStreamWithoutErrorEvent() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        // 单一 stub 同时完成"捕获事件"与"第二次 send 抛断开信号"（后打 stub 会覆盖，不能叠加两个）
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(inv -> {
            if (sendCount.incrementAndGet() >= 2) {
                throw new AsyncRequestNotUsableException("Broken pipe");
            }
            captureEvent(inv.getArgument(0));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        doAnswer(inv -> {
            AiStreamHandler handler = inv.getArgument(3);
            handler.onReasoning("正在锐评");
            handler.onContent("正文");   // 断开后不应到达
            return "stop";
        }).when(aiClient).callStream(any(), anyString(), anyString(), any(), anyString());

        service.streamComment(WeekFixture.AUG_26, emitter);

        assertThat(eventTypes()).containsExactly("start");
        verify(emitter, never()).complete();
    }

    /** 用例：仅思维链推送后失败 → 可重试；重试前推 reasoning-reset 清空前端思维链缓冲 */
    @Test
    void streamComment_reasoningOnlyStreamRetriesWithResetEvent() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        SseEmitter emitter = mockEmitter();
        // 第一次：只推思维链就失败；第二次：正常出正文
        when(aiClient.callStream(any(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> {
                    AiStreamHandler handler = inv.getArgument(3);
                    handler.onReasoning("第一轮思考");
                    throw new BizException(ErrorCode.AI_API_ERROR, "AI 接口调用失败");
                })
                .thenAnswer(inv -> {
                    AiStreamHandler handler = inv.getArgument(3);
                    handler.onContent("重试后的正文");
                    return "stop";
                });

        service.streamComment(WeekFixture.AUG_26, emitter);

        assertThat(eventTypes()).containsExactly(
                "start", "reasoning", "reasoning-reset", "chunk", "done");
        verify(aiClient, times(2)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例：正文已推送后失败 → 不可重试（重发会在打字机里重复），直接 error 收尾 */
    @Test
    void streamComment_contentStreamDoesNotRetryEvenIfReasoningStreamed() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        SseEmitter emitter = mockEmitter();
        when(aiClient.callStream(any(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> {
                    AiStreamHandler handler = inv.getArgument(3);
                    handler.onReasoning("思考");
                    handler.onContent("部分正文");
                    throw new BizException(ErrorCode.AI_API_ERROR, "AI 接口调用失败");
                });

        service.streamComment(WeekFixture.AUG_26, emitter);

        assertThat(eventTypes()).containsExactly("start", "reasoning", "chunk", "error");
        verify(aiClient, times(1)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例：重试额度耗尽仍无正文 → error 收尾（缓存不写入） */
    @Test
    void streamComment_emptyStreamThrowsAndPushesError() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        when(aiClient.callStream(any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(null);   // 流自然结束但正文为空（思维链耗尽预算形态）
        SseEmitter emitter = mockEmitter();

        service.streamComment(WeekFixture.AUG_26, emitter);

        // retryCount=1：共尝试 2 次，均无正文 → error
        verify(aiClient, times(2)).callStream(any(), anyString(), anyString(), any(), anyString());
        assertThat(eventTypes()).containsExactly("start", "error");
        // 失败不缓存：再次请求仍会调 AI
        events.clear();
        when(aiClient.callStream(any(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> {
                    AiStreamHandler handler = inv.getArgument(3);
                    handler.onContent("恢复后的正文");
                    return "stop";
                });
        service.streamComment(WeekFixture.AUG_26, mockEmitter());
        assertThat(eventTypes()).containsExactly("start", "chunk", "done");
    }

    /** 用例：finishReason=length（输出预算截断）→ done 携带 truncated=true */
    @Test
    void streamComment_truncatedStreamMarksDoneEvent() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("length");
        SseEmitter emitter = mockEmitter();

        service.streamComment(WeekFixture.AUG_26, emitter);

        assertThat(eventTypes()).endsWith("done");
        assertThat(events.getLast()).containsEntry("truncated", true);
    }

    /** 用例：Key 未配置 → 前置校验抛状态异常（controller 返回 emitter 前拦截） */
    @Test
    void validateFailsWithoutApiKey() {
        WeeklyAiCommentService noKey = new WeeklyAiCommentService(
                aiProps(""), aiClient, objectMapper,
                new com.leagueakari.ai.PromptLoader(), weeklyReportService, Runnable::run);

        assertThatThrownBy(noKey::validateAndConfigured)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AI API Key 未配置");
        verify(aiClient, never()).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例：Key 已配置 → 校验通过不抛异常 */
    @Test
    void validatePassesWithKey() {
        when(aiClient.isConfigured()).thenReturn(true);
        service.validateAndConfigured();
    }

    /** 测试夹具：周内日期（周三 2026-08-26，属 08-24 ~ 08-30 周） */
    private static final class WeekFixture {
        static final LocalDate AUG_26 = LocalDate.of(2026, 8, 26);
    }
}
