package com.leagueakari.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.ai.AiStreamHandler;
import com.leagueakari.config.AiProperties;
import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.dto.replay.ReplayResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReplayAiService 单元测试（工单 #40：AI 复盘叙述）：
 * 事件契约（与单局分析一致）、AI 输入只含转折点+摘要（不喂原始 frames）、
 * 无时间线降级（抛 2002 语义供流前拦截）、重试门控、Key 校验。
 */
class ReplayAiServiceTest {

    private AiClient aiClient;
    private ReplayService replayService;
    private ReplayAiService service;
    private ObjectMapper objectMapper;

    /** 捕获 SseEmitter 推送的事件（顺序追加） */
    private final List<Map<String, Object>> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        replayService = mock(ReplayService.class);
        objectMapper = new ObjectMapper();
        service = new ReplayAiService(
                aiProps(), aiClient, objectMapper,
                new com.leagueakari.ai.PromptLoader(), replayService, Runnable::run);
        events.clear();
    }

    /** 构造 AI 配置（重试 1 次） */
    private AiProperties aiProps() {
        AiProperties props = new AiProperties();
        props.setBaseUrl("https://ai.test");
        props.setApiKey("test-key");
        props.setModel("test-model");
        props.setPromptFile("ai/not-exist.md");
        props.setThinking(true);
        props.setTemperature(1.0);
        props.setMaxTokens(2048);
        props.setRetryCount(1);
        return props;
    }

    /** 构造可用复盘（含一血 + 反超两个转折点） */
    private ReplayResponse replayFixture() {
        return ReplayResponse.builder()
                .available(true)
                .perspectiveTeamId(100)
                .goldDiffSeries(List.of(
                        ReplayResponse.GoldDiffPoint.builder().timestampMs(60_000L).goldDiff(2000).build(),
                        ReplayResponse.GoldDiffPoint.builder().timestampMs(120_000L).goldDiff(-1000).build()))
                .killEvents(List.of(ReplayResponse.KillEvent.builder()
                        .timestampMs(65_000L).killerName("玩家一").killerChampion("阿狸")
                        .victimName("玩家二").victimChampion("锐雯").killerIsPerspective(true).build()))
                .turningPoints(List.of(
                        ReplayResponse.TurningPoint.builder().type("FIRST_BLOOD").timestampMs(65_000L)
                                .goldDiff(2000).title("一血").detail("我方 阿狸 击杀 玩家二").involved(List.of()).build(),
                        ReplayResponse.TurningPoint.builder().type("GOLD_LEAD_CHANGE").timestampMs(120_000L)
                                .goldDiff(-1000).title("经济反超").detail("我方被反超，落后 1000 金币").involved(List.of()).build()))
                .build();
    }

    /** 让 mock AiClient 按脚本回放流式增量 */
    private void mockAiStream(String finishReason) {
        doAnswer(inv -> {
            AiStreamHandler handler = inv.getArgument(3);
            handler.onReasoning("正在复盘");
            handler.onContent("这局的胜负手在中期被反超");
            return finishReason;
        }).when(aiClient).callStream(any(AiCompletionRequest.class), anyString(), anyString(), any(), anyString());
    }

    /** 创建 mock SseEmitter 捕获事件 */
    private SseEmitter mockEmitter() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doAnswer(inv -> {
            SseEmitter.SseEventBuilder builder = inv.getArgument(0);
            for (SseEmitter.DataWithMediaType data : builder.build()) {
                if (data.getMediaType() != null) {
                    continue;
                }
                events.add(objectMapper.readValue((String) data.getData(), Map.class));
            }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        return emitter;
    }

    private List<String> eventTypes() {
        return events.stream().map(e -> (String) e.get("type")).toList();
    }

    /** 用例：流式叙述——事件契约与单局分析一致（start/reasoning/chunk/done），正文完整 */
    @Test
    void streamComment_pushesChunksThenDone() throws Exception {
        when(replayService.replay(100L)).thenReturn(replayFixture());
        mockAiStream("stop");

        service.streamComment(100L, mockEmitter());

        assertThat(eventTypes()).containsExactly("start", "reasoning", "chunk", "done");
        String full = events.stream()
                .filter(e -> "chunk".equals(e.get("type")))
                .map(e -> (String) e.get("content"))
                .reduce("", String::concat);
        assertThat(full).isEqualTo("这局的胜负手在中期被反超");
    }

    /** 用例：AI 输入只含转折点与摘要——不含原始 frames（防 AI 编造 + 控制 token） */
    @Test
    void streamComment_sendsTurningPointsNotRawFrames() throws Exception {
        when(replayService.replay(100L)).thenReturn(replayFixture());
        mockAiStream("stop");

        service.streamComment(100L, mockEmitter());

        ArgumentCaptor<String> userContent = ArgumentCaptor.forClass(String.class);
        verify(aiClient).callStream(any(), anyString(), userContent.capture(), any(), anyString());
        String payload = userContent.getValue();
        // 转折点素材在内（type/detail/时刻/经济差）
        assertThat(payload).contains("FIRST_BLOOD").contains("GOLD_LEAD_CHANGE")
                .contains("我方被反超").contains("击杀");
        // 原始 frames 不在内（participantFrames 是帧结构特征字段）
        assertThat(payload).doesNotContain("participantFrames");
    }

    /** 用例：无时间线对局——validateAndConfigured 前置拦截抛 2002（流建立前） */
    @Test
    void validateFailsWhenTimelineMissing() {
        when(aiClient.isConfigured()).thenReturn(true);
        when(replayService.replay(100L)).thenReturn(ReplayResponse.builder()
                .available(false).goldDiffSeries(List.of()).killEvents(List.of()).turningPoints(List.of()).build());

        assertThatThrownBy(() -> service.validateAndConfigured(100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("时间线");
    }

    /** 用例：Key 未配置抛 4101 */
    @Test
    void validateFailsWithoutApiKey() {
        when(aiClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.validateAndConfigured(100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("AI API Key 未配置");
    }

    /** 用例：仅思维链推送后失败可重试（reset 事件）；正文已推送不可重试 */
    @Test
    void streamComment_reasoningOnlyRetriesWithReset() throws Exception {
        when(replayService.replay(100L)).thenReturn(replayFixture());
        when(aiClient.callStream(any(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> {
                    AiStreamHandler handler = inv.getArgument(3);
                    handler.onReasoning("第一轮");
                    throw new BizException(ErrorCode.AI_API_ERROR, "AI 接口调用失败");
                })
                .thenAnswer(inv -> {
                    AiStreamHandler handler = inv.getArgument(3);
                    handler.onContent("重试正文");
                    return "stop";
                });

        service.streamComment(100L, mockEmitter());

        assertThat(eventTypes()).containsExactly("start", "reasoning", "reasoning-reset", "chunk", "done");
        verify(aiClient, times(2)).callStream(any(), anyString(), anyString(), any(), anyString());
    }

    /** 用例：AI 失败（未推送正文）推 error 事件 */
    @Test
    void streamComment_aiErrorPushesErrorEvent() throws Exception {
        when(replayService.replay(100L)).thenReturn(replayFixture());
        when(aiClient.callStream(any(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new BizException(ErrorCode.AI_API_ERROR, "AI 接口调用失败（HTTP 502）"));

        service.streamComment(100L, mockEmitter());

        assertThat(eventTypes()).containsExactly("start", "error");
    }
}
