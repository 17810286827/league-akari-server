package com.leagueakari.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.config.AiProperties;
import com.leagueakari.dto.WeeklyReportResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WeeklyAiCommentService 单元测试（业务编排层；HTTP 细节由 AiClientTest 覆盖）：
 * API Key 校验、AI 返回内容透出、调用摘要携带周报素材、同一周结果缓存
 * （重复生成不重复计费）、空正文自动重试一次。
 */
class WeeklyAiCommentServiceTest {

    private AiClient aiClient;
    private WeeklyAiCommentService service;

    /** 构造指定 Key 的被测服务（其余配置固定；测试替身属性对应 ai.* 键） */
    private WeeklyAiCommentService serviceWithKey(String apiKey) {
        AiProperties props = new AiProperties();
        props.setBaseUrl("https://ai.example.com/v1");
        props.setApiKey(apiKey);
        props.setModel("test-model");
        props.setWeeklyPromptFile("ai/weekly-prompt.md");
        props.setTemperature(1.0);
        props.setWeeklyMaxTokens(512);
        return new WeeklyAiCommentService(props, aiClient, new ObjectMapper(), new com.leagueakari.ai.PromptLoader());
    }

    /** 构造最小周报：仅含 AI 摘要会用到的字段 */
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

    @BeforeEach
    void setUp() {
        aiClient = mock(AiClient.class);
        service = serviceWithKey("test-key");
    }

    /** 用例：API Key 未配置时抛状态异常（调用方降级为 null） */
    @Test
    void generateComment_keyGateLivesInAiClient() {
        // Key 状态判定已下沉 AiClient（架构清理 T7）：服务不再自判——
        // mock 重载照常返回正文即透出，无 Key 拦截由 AiClient 承担（AiClientTest 锁定）
        WeeklyAiCommentService noKey = serviceWithKey("");
        when(aiClient.call(any(), anyString(), anyString(), anyString(), anyInt())).thenReturn("正文照常");

        String comment = noKey.generateComment(report("2026-08-24 ~ 2026-08-30"));
        assertThat(comment).contains("正文照常");
    }

    /** 用例：AI 返回正文 → 透出给调用方；调用摘要里带上周报素材（周标签/榜单/名场面） */
    @Test
    void generateComment_returnsAiContentAndSendsWeeklySummary() throws Exception {
        when(aiClient.call(any(), anyString(), anyString(), anyString(), anyInt())).thenReturn("本周赌书封神，鬼子战犯实锤");

        String comment = service.generateComment(report("2026-08-24 ~ 2026-08-30"));

        assertThat(comment).isEqualTo("本周赌书封神，鬼子战犯实锤");
        // 摘要校验：user 消息里带上周标签与榜单素材（锐评点名用）
        ArgumentCaptor<String> userContent = ArgumentCaptor.forClass(String.class);
        verify(aiClient).call(any(), anyString(), userContent.capture(), anyString(), anyInt());
        assertThat(userContent.getValue())
                .contains("2026-08-24 ~ 2026-08-30").contains("MVP").contains("手裂鬼子");
    }

    /** 用例：AI 调用失败（AiClient 转 IllegalStateException）→ 原样上抛（调用方降级） */
    @Test
    void generateComment_propagatesAiFailure() {
        when(aiClient.call(any(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("AI 接口调用失败（HTTP 502），请稍后重试"));

        assertThatThrownBy(() -> service.generateComment(report("2026-08-24 ~ 2026-08-30")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("502");
    }

    /** 用例：同一周缓存命中——第二次生成不再调 AI（避免重复计费） */
    @Test
    void generateComment_cachesPerWeek() {
        when(aiClient.call(any(), anyString(), anyString(), anyString(), anyInt())).thenReturn("锐评");

        service.generateComment(report("2026-08-24 ~ 2026-08-30"));
        service.generateComment(report("2026-08-24 ~ 2026-08-30"));

        verify(aiClient, times(1)).call(any(), anyString(), anyString(), anyString(), anyInt());
        // 换一周则重新生成
        service.generateComment(report("2026-08-31 ~ 2026-09-06"));
        verify(aiClient, times(2)).call(any(), anyString(), anyString(), anyString(), anyInt());
    }

    /**
     * 用例：空正文重试已下沉为 AiClient 重载原语（架构清理 T7）——
     * 重载返回 null（重试耗尽）→ 本层抛状态异常（调用方降级为 null）
     */
    @Test
    void generateComment_throwsWhenBothAttemptsEmpty() {
        when(aiClient.call(any(), anyString(), anyString(), anyString(), anyInt())).thenReturn(null);

        assertThatThrownBy(() -> service.generateComment(report("2026-08-24 ~ 2026-08-30")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("内容为空");
        verify(aiClient, times(1)).call(any(), anyString(), anyString(), anyString(), anyInt());
    }
}
