package com.leagueakari.broadcast;

import com.leagueakari.service.GameDataService;
import com.leagueakari.service.TeamRosterService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostGameCommentService 单元测试（业务编排层；HTTP 细节由 AiClientTest 覆盖）：
 * mock AiClient 验证局后锐评的正文透出、空正文自动重试（2 次）、
 * API Key 门控与调用参数（独立模型键）透传。
 */
@ExtendWith(MockitoExtension.class)
class PostGameCommentServiceTest {

    @Mock
    private AiClient aiClient;

    private PostGameCommentService service;

    @BeforeEach
    void setUp() {
        service = serviceWith("test-key");
    }

    /** 构造指定 Key 的被测服务（其余配置固定；测试替身属性对应 ai.* 键） */
    private PostGameCommentService serviceWith(String apiKey) {
        AiProperties props = new AiProperties();
        props.setBaseUrl("https://opencode.ai/zen/go/v1");
        props.setApiKey(apiKey);
        props.setPostGameModel("test-model");
        props.setPostGamePromptFile("ai/post-game-prompt.md");
        props.setTemperature(1.0);
        props.setPostGameMaxTokens(1024);
        return new PostGameCommentService(props, aiClient, new ObjectMapper());
    }

    /** 最小局后摘要：胜负、车队成员与焦点 */
    private Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("result", "胜利");
        s.put("score", Map.of("main", 32, "other", 18));
        s.put("teamName", "iKun");
        s.put("hero", Map.of("name", "赌书消得泼茶香", "kda", "12/3/7", "title", "MVP"));
        s.put("fleet", List.of(
                Map.of("name", "赌书消得泼茶香", "kda", "12/3/7", "title", "MVP"),
                Map.of("name", "手裂鬼子", "kda", "3/8/5")));
        return s;
    }

    /** 用例：AI 正常返回正文 → 返回锐评文本，且用局后独立模型（ai.post-game-model）调用 */
    @Test
    void generateComment_returnsAiContentWithPostGameModel() {
        when(aiClient.call(any(), anyString(), anyString(), anyString())).thenReturn("这把养鱼人把对面野区当自己家");

        String comment = service.generateComment(summary());

        assertThat(comment).contains("养鱼人");
        // 参数归属：局后锐评必须用自己的独立模型键（与 ai.model 解耦，播报对延迟敏感）
        ArgumentCaptor<AiCompletionRequest> captor = ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(aiClient, times(1)).call(captor.capture(), anyString(), anyString(), anyString());
        assertThat(captor.getValue().model()).isEqualTo("test-model");
        assertThat(captor.getValue().maxTokens()).isEqualTo(1024);
    }

    /** 用例：第一次正文为空（推理预算耗尽）→ 自动重试第二次成功 */
    @Test
    void generateComment_retriesWhenContentEmpty() {
        when(aiClient.call(any(), anyString(), anyString(), anyString())).thenReturn(null, "输了不丢人，0/9 才丢人");

        String comment = service.generateComment(summary());

        assertThat(comment).contains("0/9");
        verify(aiClient, times(2)).call(any(), anyString(), anyString(), anyString());
    }

    /** 用例：两次都空正文 → 抛 IllegalStateException（由编排层降级为缺席提示） */
    @Test
    void generateComment_throwsWhenAlwaysEmpty() {
        when(aiClient.call(any(), anyString(), anyString(), anyString())).thenReturn(null, (String) null);

        assertThatThrownBy(() -> service.generateComment(summary()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("内容为空");
        verify(aiClient, times(2)).call(any(), anyString(), anyString(), anyString());
    }

    /** 用例：AI 接口失败（AiClient 转 IllegalStateException）→ 原样上抛（由编排层降级） */
    @Test
    void generateComment_propagatesAiFailure() {
        when(aiClient.call(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("AI 接口调用失败（HTTP 502），请稍后重试"));

        assertThatThrownBy(() -> service.generateComment(summary()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("502");
    }

    /** 用例：API Key 未配置 → 快速失败，不发起任何 AI 调用 */
    @Test
    void generateComment_failsFastWithoutApiKey() {
        PostGameCommentService noKey = serviceWith("");

        assertThatThrownBy(() -> noKey.generateComment(summary()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API Key");
        verify(aiClient, times(0)).call(any(), anyString(), anyString(), anyString());
    }
}
