package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MatchDetailResponse;
import com.leagueakari.entity.MatchParticipant;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiAnalysisService 单元测试：
 * 流式调用成功（逐块推送 chunk + 缓存命中二次推送全文）、AI 非 200 推送 error 事件、
 * 前置校验（API Key 缺失 / 对局不存在）。
 * 通过 mock CloseableHttpClient（返回模拟 SSE 流）与 mock SseEmitter（捕获推送事件）隔离外部依赖；
 * 线程池以同步执行器（Runnable::run）注入，保证断言时序
 */
class AiAnalysisServiceTest {

    /** 模拟 opencode 网关的推理模式流：先输出思维链（reasoning_content），再输出正文（content） */
    private static final String SSE_STREAM = """
            data: {"choices":[{"delta":{"reasoning_content":"正在分析"}}]}

            data: {"choices":[{"delta":{"content":"你好"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"继续推理"}}]}

            data: {"choices":[{"delta":{"content":"，世界"}}]}

            data: [DONE]

            """;

    /** 输出被预算截断的流：最后一个 chunk 携带 finish_reason=length */
    private static final String SSE_STREAM_TRUNCATED = """
            data: {"choices":[{"delta":{"content":"写到一半"}}]}

            data: {"choices":[{"delta":{"content":"戛然而止"},"finish_reason":"length"}]}

            data: [DONE]

            """;

    private CloseableHttpClient httpClient;
    private MatchService matchService;
    private GameDataService gameDataService;
    private AiAnalysisService service;
    private ObjectMapper objectMapper;

    /** 捕获 SseEmitter 推送的事件（顺序追加） */
    private final List<Map<String, Object>> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        httpClient = mock(CloseableHttpClient.class);
        matchService = mock(MatchService.class);
        gameDataService = mock(GameDataService.class);
        objectMapper = new ObjectMapper();
        // promptFile 指向不存在的文件：走内置默认提示词，避免依赖 classpath 资源；
        // thinking=false + 默认采样参数：与生产配置一致
        service = new AiAnalysisService(
                "https://ai.test", "test-key", "test-model", "ai/not-exist.md",
                false, 0.7, 0.6, 0.3, 2048,
                matchService, objectMapper, httpClient, gameDataService, Runnable::run);
        events.clear();
    }

    /** 模拟 AI 接口响应：status 状态码 + 响应体（SSE 流或错误 JSON） */
    private void mockAiResponse(int status, String body) throws Exception {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getCode()).thenReturn(status);
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);
        when(httpClient.execute(any())).thenReturn(response);
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

    @Test
    void streamAnalysisPushesChunksThenDone() throws Exception {
        // AI 接口返回推理流（思维链 + 正文）+ [DONE]，每次 execute 都返回该流
        mockAiResponse(200, SSE_STREAM);
        when(matchService.getMatchDetail(123L)).thenReturn(buildDetail());

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
        mockAiResponse(200, SSE_STREAM);
        when(matchService.getMatchDetail(123L)).thenReturn(buildDetail());
        service.analyzeStream(123L, mockEmitter());
        assertThat(events.get(5)).containsEntry("type", "done");

        // 第二次：命中缓存，直接推送正文全文（fromCache=true），不再调用 AI 接口
        events.clear();
        service.analyzeStream(123L, mockEmitter());
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).containsEntry("type", "start").containsEntry("fromCache", true);
        assertThat(events.get(1)).containsEntry("type", "chunk").containsEntry("content", "你好，世界");
        assertThat(events.get(2)).containsEntry("type", "done");
        // 第一次分析调用过 1 次 AI 接口；第二次命中缓存不再新增调用
        verify(httpClient, times(1)).execute(any());
    }

    @Test
    void aiErrorPushesErrorEvent() throws Exception {
        // AI 接口返回 500：非业务异常，流中推送 error 事件（含状态码提示）
        mockAiResponse(500, "{\"error\":\"boom\"}");
        when(matchService.getMatchDetail(123L)).thenReturn(buildDetail());

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
        when(matchService.getMatchDetail(123L)).thenThrow(new MatchNotFoundException(123L));

        service.analyzeStream(123L, mockEmitter());

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("type", "error");
        assertThat((String) events.get(0).get("message")).contains("对局不存在");
    }

    @Test
    void requestDisablesThinking() throws Exception {
        // 请求体必须携带 chat_template_kwargs.thinking=false（DeepSeek 原生参数，经网关透传关闭思考）：
        // 缺失会导致模型输出长思维链、整流时间翻倍（实测 70s+ → 38s）
        mockAiResponse(200, SSE_STREAM);
        when(matchService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.analyzeStream(123L, mockEmitter());

        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);
        verify(httpClient).execute(captor.capture());
        String body = EntityUtils.toString(captor.getValue().getEntity());
        assertThat(body).contains("\"chat_template_kwargs\"").contains("\"thinking\":false");
    }

    @Test
    void thinkingEnabledOmitsDisableParam() throws Exception {
        // thinking=true 时请求体不得携带 chat_template_kwargs（保持模型默认推理模式，
        // 前端通过 reasoning 事件灰字展示思维链）
        AiAnalysisService thinkingService = new AiAnalysisService(
                "https://ai.test", "test-key", "test-model", "ai/not-exist.md",
                true, 0.7, 0.6, 0.3, 2048,
                matchService, new ObjectMapper(), httpClient, gameDataService, Runnable::run);
        mockAiResponse(200, SSE_STREAM);
        when(matchService.getMatchDetail(123L)).thenReturn(buildDetail());

        thinkingService.analyzeStream(123L, mockEmitter());

        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);
        verify(httpClient).execute(captor.capture());
        String body = EntityUtils.toString(captor.getValue().getEntity());
        assertThat(body).doesNotContain("chat_template_kwargs");
    }

    @Test
    void summaryConvertsIdsToGameDataNames() throws Exception {
        // 摘要组装：英雄/装备 ID 在模型调用前经 GameDataService 转换为中文名，
        // 模型不再按 ID 猜英雄（非思考模式下会猜错，如 103 → 瑞兹）
        when(gameDataService.championName(1)).thenReturn("黑暗之女");
        when(gameDataService.itemName(6672)).thenReturn("收集者");
        when(gameDataService.itemName(6609)).thenReturn("巨蛇之牙");
        mockAiResponse(200, SSE_STREAM);
        when(matchService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.analyzeStream(123L, mockEmitter());

        // 请求体 user 消息应包含转换后的中文英雄名与装备名
        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);
        verify(httpClient).execute(captor.capture());
        String body = EntityUtils.toString(captor.getValue().getEntity());
        assertThat(body).contains("黑暗之女").contains("收集者").contains("巨蛇之牙");
    }

    @Test
    void requestCarriesSamplingParams() throws Exception {
        // 采样参数随请求发送：temperature 降随机性、frequency/presence penalty 抑制长文本重复、
        // max_tokens 限制推理模式无限思考（思维链与正文共享预算）
        mockAiResponse(200, SSE_STREAM);
        when(matchService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.analyzeStream(123L, mockEmitter());

        ArgumentCaptor<HttpPost> captor = ArgumentCaptor.forClass(HttpPost.class);
        verify(httpClient).execute(captor.capture());
        String body = EntityUtils.toString(captor.getValue().getEntity());
        assertThat(body).contains("\"temperature\":0.7")
                .contains("\"frequency_penalty\":0.6")
                .contains("\"presence_penalty\":0.3")
                .contains("\"max_tokens\":2048");
    }

    @Test
    void truncatedStreamMarksDoneEvent() throws Exception {
        // 输出被长度预算截断（finish_reason=length）：done 事件携带 truncated=true，
        // 前端据此提示"内容被截断"，避免"写一半"被误认为完整
        mockAiResponse(200, SSE_STREAM_TRUNCATED);
        when(matchService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.analyzeStream(123L, mockEmitter());

        assertThat(events.get(events.size() - 1))
                .containsEntry("type", "done")
                .containsEntry("truncated", true);
    }

    @Test
    void validateFailsWithoutApiKey() {
        // API Key 未配置：前置校验直接抛异常（由全局处理器转 503）
        AiAnalysisService noKey = new AiAnalysisService(
                "https://ai.test", "", "test-model", "ai/not-exist.md",
                false, 0.7, 0.6, 0.3, 2048,
                matchService, new ObjectMapper(), httpClient, gameDataService, Runnable::run);

        assertThatThrownBy(() -> noKey.validateAndConfigured(123L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void validatePassesWithKeyAndExistingMatch() {
        // 配置齐全 + 对局存在：校验通过不抛异常
        when(matchService.getMatchDetail(123L)).thenReturn(buildDetail());

        service.validateAndConfigured(123L);
    }

    @Test
    void validateThrowsWhenMatchMissing() {
        // 对局不存在：前置校验抛 MatchNotFoundException（由全局处理器转 404）
        when(matchService.getMatchDetail(123L)).thenThrow(new MatchNotFoundException(123L));

        assertThatThrownBy(() -> service.validateAndConfigured(123L))
                .isInstanceOf(MatchNotFoundException.class);
    }
}
