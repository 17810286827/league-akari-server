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
    private com.leagueakari.mapper.WeeklyReportAiMapper weeklyReportAiMapper;
    /** 固定时钟：2026-09-06（周日）——当前周 = 08-31 ~ 09-06，历史周 = 之前 */
    private final java.time.Clock clock = java.time.Clock.fixed(
            java.time.ZonedDateTime.of(2026, 9, 6, 10, 0, 0, 0, java.time.ZoneId.of("Asia/Shanghai")).toInstant(),
            java.time.ZoneId.of("Asia/Shanghai"));
    private WeeklyAiCommentService service;
    private ObjectMapper objectMapper;

    /** 捕获 SseEmitter 推送的事件（顺序追加） */
    private final List<Map<String, Object>> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        weeklyReportService = mock(WeeklyReportService.class);
        weeklyReportAiMapper = mock(com.leagueakari.mapper.WeeklyReportAiMapper.class);
        // 历史周判定：库内无记录（selectOne 返回 null）
        org.mockito.Mockito.lenient().when(weeklyReportAiMapper.selectOne(any())).thenReturn(null);
        objectMapper = new ObjectMapper();
        // promptFile 指向不存在的文件：走内置默认提示词，避免依赖 classpath 资源
        service = new WeeklyAiCommentService(
                aiProps("test-key"), aiClient, objectMapper,
                new com.leagueakari.ai.PromptLoader(), weeklyReportService,
                weeklyReportAiMapper, clock, Runnable::run);
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
        // 参数归一（ADR 0009）：全场景统一读 ai.max-tokens
        props.setMaxTokens(512);
        props.setRetryCount(1);
        return props;
    }

    /** 构造最小周报：仅含 AI 摘要会用到的字段（weekLabel 即缓存键；
     *  weekEndMs 由周标签解析——次周一 00:00，历史周判定用） */
    private WeeklyReportResponse report(String weekLabel) {
        // 周标签形如 "2026-08-24 ~ 2026-08-30"：结束 = 周日 + 1 天的 00:00（Asia/Shanghai）
        String endDate = weekLabel.split(" ~ ")[1];
        long weekEndMs = java.time.LocalDate.parse(endDate)
                .plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli();
        return WeeklyReportResponse.builder()
                .weekLabel(weekLabel)
                .weekEndMs(weekEndMs)
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

        service.streamComment(WeekFixture.AUG_26, false, emitter);

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

        service.streamComment(WeekFixture.AUG_26, false, emitter);

        ArgumentCaptor<String> userContent = ArgumentCaptor.forClass(String.class);
        verify(aiClient).callStream(any(), anyString(), userContent.capture(), any(), anyString());
        assertThat(userContent.getValue())
                .contains("2026-08-24 ~ 2026-08-30").contains("MVP").contains("手裂鬼子");
    }

    /**
     * 用例：摘要补出勤榜投影（AI 加厚 spec #43 用户故事 9）——
     * 提示词承诺的六个榜单终于齐了（原 buildSummary 漏投影 attendanceBoard）。
     */
    @Test
    void streamComment_sendsAttendanceBoardToAi() throws Exception {
        WeeklyReportResponse report = report("2026-08-24 ~ 2026-08-30");
        report.setAttendanceBoard(List.of(WeeklyReportResponse.BoardEntry.builder()
                .riotId("赌书消得泼茶香#iKun").value(3.0).detail("3场").build()));
        when(weeklyReportService.weeklyReport(any(LocalDate.class))).thenReturn(report);
        mockAiStream("stop");

        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());

        ArgumentCaptor<String> userContent = ArgumentCaptor.forClass(String.class);
        verify(aiClient).callStream(any(), anyString(), userContent.capture(), any(), anyString());
        // 出勤榜前 3 在摘要内（键名与既有榜单命名一致：attendanceBoard）
        assertThat(userContent.getValue()).contains("attendanceBoard").contains("3场");
    }

    /** 用例：周报请求的 thinking 跟随 yaml（ai.thinking）——三个 AI 场景统一读配置 */
    @Test
    void streamComment_requestThinkingFollowsYaml() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("stop");
        SseEmitter emitter = mockEmitter();

        service.streamComment(WeekFixture.AUG_26, false, emitter);

        ArgumentCaptor<AiCompletionRequest> req = ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(aiClient).callStream(req.capture(), anyString(), anyString(), any(), anyString());
        assertThat(req.getValue().isThinking()).isTrue();
    }

    /** 用例：缓存命中——同一周第二次流式不再调 AI，直接推送全文（fromCache=true） */
    @Test
    void streamComment_cacheHitPushesFullTextWithoutCallingAi() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("stop");
        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());
        events.clear();

        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());

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

        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());
        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());

        verify(aiClient, times(2)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例：AI 调用失败（未推送任何增量）→ 推 error 事件并关闭连接 */
    @Test
    void streamComment_aiErrorPushesErrorEvent() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        when(aiClient.callStream(any(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new BizException(ErrorCode.AI_API_ERROR, "AI 接口调用失败（HTTP 502）"));
        SseEmitter emitter = mockEmitter();

        service.streamComment(WeekFixture.AUG_26, false, emitter);

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

        service.streamComment(WeekFixture.AUG_26, false, emitter);

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

        service.streamComment(WeekFixture.AUG_26, false, emitter);

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

        service.streamComment(WeekFixture.AUG_26, false, emitter);

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

        service.streamComment(WeekFixture.AUG_26, false, emitter);

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
        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());
        assertThat(eventTypes()).containsExactly("start", "chunk", "done");
    }

    /** 用例：finishReason=length（输出预算截断）→ done 携带 truncated=true */
    @Test
    void streamComment_truncatedStreamMarksDoneEvent() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("length");
        SseEmitter emitter = mockEmitter();

        service.streamComment(WeekFixture.AUG_26, false, emitter);

        assertThat(eventTypes()).endsWith("done");
        assertThat(events.getLast()).containsEntry("truncated", true);
    }

    /** 用例：Key 未配置 → 前置校验抛状态异常（controller 返回 emitter 前拦截） */
    @Test
    void validateFailsWithoutApiKey() {
        WeeklyAiCommentService noKey = new WeeklyAiCommentService(
                aiProps(""), aiClient, objectMapper,
                new com.leagueakari.ai.PromptLoader(), weeklyReportService,
                weeklyReportAiMapper, clock, Runnable::run);

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

    /** 用例（工单 #39）：历史周生成后落库——再次请求命中持久化，不调 AI、推全文（fromCache=true） */
    @Test
    void streamComment_persistsHistoricalWeekAndServesFromDb() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("stop");

        // 第一次：生成 + 落库（周结束 08-31 00:00 < 当前时钟 09-06 → 历史周）
        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());

        org.mockito.ArgumentCaptor<com.leagueakari.entity.WeeklyReportAi> captor =
                org.mockito.ArgumentCaptor.forClass(com.leagueakari.entity.WeeklyReportAi.class);
        verify(weeklyReportAiMapper).insert(captor.capture());
        assertThat(captor.getValue().getWeekLabel()).isEqualTo("2026-08-24 ~ 2026-08-30");
        assertThat(captor.getValue().getComment()).contains("本周赌书封神");
        // 落库后内存缓存同时写入：第二次直接秒回
        events.clear();
        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());
        assertThat(eventTypes()).containsExactly("start", "chunk", "done");
        assertThat(events.get(0)).containsEntry("fromCache", true);
        verify(aiClient, times(1)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例（工单 #39）：历史周已有落库记录 → 直接同步返回落库文本，不调 AI（老周报秒开） */
    @Test
    void streamComment_servesFromDbWithoutCallingAi() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        // 库内已有该周锐评（此前生成过，JVM 缓存已过期/重启后）
        when(weeklyReportAiMapper.selectOne(any())).thenReturn(persisted("2026-08-24 ~ 2026-08-30",
                "库内历史锐评"));

        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());

        assertThat(eventTypes()).containsExactly("start", "chunk", "done");
        assertThat(events.get(0)).containsEntry("fromCache", true);
        assertThat(events.get(1)).containsEntry("content", "库内历史锐评");
        // AI 零调用
        verify(aiClient, never()).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例（工单 #39）：当前周（进行中）不落库——只走内存短缓存，下周内容会变 */
    @Test
    void streamComment_currentWeekNotPersisted() throws Exception {
        // 当前周 = 08-31 ~ 09-06（固定时钟 09-06）
        when(weeklyReportService.weeklyReport(any(java.time.LocalDate.class)))
                .thenReturn(report("2026-08-31 ~ 2026-09-06"));
        mockAiStream("stop");

        service.streamComment(java.time.LocalDate.of(2026, 9, 2), false, mockEmitter());

        // 当前周：生成但不落库（内容随新对局变化）
        verify(weeklyReportAiMapper, never()).insert(any(com.leagueakari.entity.WeeklyReportAi.class));
        // 仍走内存缓存（第二次秒回）
        events.clear();
        service.streamComment(java.time.LocalDate.of(2026, 9, 2), false, mockEmitter());
        assertThat(eventTypes()).containsExactly("start", "chunk", "done");
    }

    /** 用例（工单 #39）：并发首次生成历史周——后写者发现已存在则丢弃（唯一键兜底） */
    @Test
    void streamComment_concurrentFirstInsertDiscardsLoser() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        mockAiStream("stop");
        // 模拟并发：插入时另一请求已先落库（DuplicateKeyException 场景由唯一键兜底，
        // 这里验证捕获异常后吞掉不推 error）
        when(weeklyReportAiMapper.insert(any(com.leagueakari.entity.WeeklyReportAi.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_week_label"));

        service.streamComment(WeekFixture.AUG_26, false, mockEmitter());

        // 主体流程不受影响（正常 done 收尾，丢弃败者的落库）
        assertThat(eventTypes()).endsWith("done");
    }

    /** 用例（ADR 0010）：当前周 force=true 跳过缓存重新生成——缓存已有内容仍调 AI，全文重新流式推送 */
    @Test
    void streamComment_forceOnCurrentWeekBypassesCache() throws Exception {
        // 当前周 = 08-31 ~ 09-06（固定时钟 09-06）
        when(weeklyReportService.weeklyReport(any(LocalDate.class)))
                .thenReturn(report("2026-08-31 ~ 2026-09-06"));
        mockAiStream("stop");
        // 第一次：正常生成（写入缓存）
        service.streamComment(LocalDate.of(2026, 9, 2), false, mockEmitter());
        events.clear();

        // 第二次：force=true 强制刷新——绕过缓存重新调 AI（fromCache=false）
        service.streamComment(LocalDate.of(2026, 9, 2), true, mockEmitter());

        assertThat(eventTypes()).containsExactly(
                "start", "reasoning", "chunk", "reasoning", "chunk", "done");
        assertThat(events.get(0)).containsEntry("fromCache", false);
        verify(aiClient, times(2)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例（ADR 0010）：60 秒冷却期内再次 force——不调 AI，直接返回缓存内容 */
    @Test
    void streamComment_forceWithinCooldownReturnsCached() throws Exception {
        when(weeklyReportService.weeklyReport(any(LocalDate.class)))
                .thenReturn(report("2026-08-31 ~ 2026-09-06"));
        mockAiStream("stop");
        // 第一次 force：真实生成（受理时即占位冷却）
        service.streamComment(LocalDate.of(2026, 9, 2), true, mockEmitter());
        events.clear();

        // 第二次 force（同一时刻，冷却期内）：缓存已有内容 → 秒回缓存，AI 不再调用
        service.streamComment(LocalDate.of(2026, 9, 2), true, mockEmitter());

        assertThat(eventTypes()).containsExactly("start", "chunk", "done");
        assertThat(events.get(0)).containsEntry("fromCache", true);
        assertThat(events.get(1)).containsEntry("content", "本周赌书封神，鬼子战犯实锤");
        verify(aiClient, times(1)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例（ADR 0010）：冷却过期后再次 force——重新生成（AI 第二次调用） */
    @Test
    void streamComment_forceAfterCooldownRegenerates() throws Exception {
        MutableClock mutableClock = new MutableClock();
        WeeklyAiCommentService svc = new WeeklyAiCommentService(
                aiProps("test-key"), aiClient, objectMapper,
                new com.leagueakari.ai.PromptLoader(), weeklyReportService,
                weeklyReportAiMapper, mutableClock, Runnable::run);
        when(weeklyReportService.weeklyReport(any(LocalDate.class)))
                .thenReturn(report("2026-08-31 ~ 2026-09-06"));
        mockAiStream("stop");

        // 第一次 force：生成
        svc.streamComment(LocalDate.of(2026, 9, 2), true, mockEmitter());
        // 时钟推进 61 秒：冷却已过
        mutableClock.advanceSeconds(61);
        svc.streamComment(LocalDate.of(2026, 9, 2), true, mockEmitter());

        verify(aiClient, times(2)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例（ADR 0010）：历史周 force=true 被忽略——照常走持久化命中，不调 AI（不可变原则优先） */
    @Test
    void streamComment_forceOnHistoricalWeekIgnored() throws Exception {
        stubReport("2026-08-24 ~ 2026-08-30");
        // 库内已有该周锐评（历史周 = 08-24 ~ 08-30，时钟 09-06 已过周结束）
        when(weeklyReportAiMapper.selectOne(any())).thenReturn(persisted("2026-08-24 ~ 2026-08-30",
                "库内历史锐评"));

        service.streamComment(WeekFixture.AUG_26, true, mockEmitter());

        // force 对历史周无效：直接返回存档文本，AI 零调用
        assertThat(eventTypes()).containsExactly("start", "chunk", "done");
        assertThat(events.get(1)).containsEntry("content", "库内历史锐评");
        verify(aiClient, never()).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例（ADR 0010）：冷却在请求受理时占位——生成进行中并发 force 被拦截，不重复调 AI */
    @Test
    void streamComment_forceDuringOngoingGenerationBlockedByCooldown() throws Exception {
        when(weeklyReportService.weeklyReport(any(LocalDate.class)))
                .thenReturn(report("2026-08-31 ~ 2026-09-06"));
        // 第一条 force 的 AI 调用挂起（模拟流式生成进行中）；第二条调用立即返回正文。
        // Runnable::run 是同步执行器：第一条流必须跑在独立线程（否则测试线程自死锁），
        // 第二条 force 由测试线程同步执行（被冷却拦截时根本不会到达 AI 调用）
        java.util.concurrent.CompletableFuture<String> release = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(aiClient.callStream(any(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> {
                    if (calls.incrementAndGet() == 1) {
                        // 第一条流：先推正文再挂起（避免释放后被"零内容失败"逻辑判定重试）
                        AiStreamHandler handler = inv.getArgument(3);
                        handler.onContent("第一份锐评正文");
                        return release.get();
                    }
                    AiStreamHandler handler = inv.getArgument(3);
                    handler.onContent("并发被拦截");
                    return "stop";
                });
        SseEmitter first = mockEmitter();
        Thread firstStream = new Thread(() ->
                service.streamComment(LocalDate.of(2026, 9, 2), true, first));
        firstStream.start();
        // 等第一条流进入 AI 调用（此刻冷却应已占位——修复目标）
        awaitAiCalled(1);

        // 同一时刻并发 force：冷却已占位 → 不再调 AI，直接返回缓存路径
        service.streamComment(LocalDate.of(2026, 9, 2), true, mockEmitter());

        // 释放第一条流，等待其正常收尾后核账：AI 全程只被调用 1 次
        release.complete("stop");
        firstStream.join(5000);
        org.mockito.Mockito.verify(aiClient, times(1))
                .callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 等待 AI 被调用指定次数（轮询 mock 调用计数，生成线程异步推进） */
    private void awaitAiCalled(int wanted) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            long called = org.mockito.Mockito.mockingDetails(aiClient).getInvocations().stream()
                    .filter(i -> "callStream".equals(i.getMethod().getName())).count();
            if (called >= wanted) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("AI not called " + wanted + " time(s) within 5s");
    }

    /** 构造已落库的锐评记录 */
    private com.leagueakari.entity.WeeklyReportAi persisted(String weekLabel, String comment) {
        com.leagueakari.entity.WeeklyReportAi record = new com.leagueakari.entity.WeeklyReportAi();
        record.setWeekLabel(weekLabel);
        record.setComment(comment);
        record.setWeekEndMs(0L);
        return record;
    }

    /** 测试夹具：周内日期（周三 2026-08-26，属 08-24 ~ 08-30 周） */
    private static final class WeekFixture {
        static final LocalDate AUG_26 = LocalDate.of(2026, 8, 26);
    }

    /**
     * 可推进的固定时钟（冷却过期测试用）：基础时刻 2026-09-06 10:00（Asia/Shanghai），
     * advanceSeconds 原子推进，冷却判定读 millis()
     */
    private static final class MutableClock extends java.time.Clock {
        private final java.time.Instant base = java.time.ZonedDateTime.of(
                2026, 9, 6, 10, 0, 0, 0, java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        private final java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        private volatile long offsetMillis = 0;

        /** 时钟前进指定秒数（模拟冷却时间流逝） */
        void advanceSeconds(long seconds) {
            offsetMillis += seconds * 1000;
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException("test fixture");
        }

        @Override
        public java.time.Instant instant() {
            return base.plusMillis(offsetMillis);
        }
    }
}
