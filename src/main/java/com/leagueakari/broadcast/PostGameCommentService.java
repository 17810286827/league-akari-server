package com.leagueakari.broadcast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 局后锐评服务（业务编排层）：战报图发送后，按车队视角对本局做一次非流式 AI 点评，
 * 输出可直接发群的正文。区别于单局 AI 分析（self 视角、SSE 流式）与周报锐评（按周聚合）。
 * <p>HTTP 调用统一走公共 {@link AiClient}（见 docs/adr/0005），本服务只负责
 * 业务编排：提示词加载与空正文重试。</p>
 * <p>失败语义：空正文自动重试 1 次（共最多 2 次调用），仍失败抛 IllegalStateException，
 * 由 BroadcastCoordinator 降级为"AI 缺席提示"发送。</p>
 */
@Slf4j
@Service
public class PostGameCommentService {

    /** 最大调用次数：1 次正常 + 1 次空正文重试 */
    private static final int MAX_ATTEMPTS = 2;

    /** API Key（环境变量 AI_API_KEY；前置校验用） */
    private final String apiKey;

    /** 局后锐评提示词文件（classpath；缺失时回退内置默认，保证接口可用） */
    private final String promptFile;

    /** 采样参数（组装后经 AiCompletionRequest 显式传给 AiClient，见 docs/adr/0005） */
    private final AiCompletionRequest completionRequest;

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    /** 构造注入：AI 配置统一取自 AiProperties（yaml 唯一真值，见 docs/adr/0004） */
    public PostGameCommentService(
            AiProperties ai,
            AiClient aiClient,
            ObjectMapper objectMapper) {
        this.apiKey = ai.getApiKey();
        this.promptFile = ai.getPostGamePromptFile();
        // 局后锐评场景采样参数：独立模型键（ai.post-game-model）、无 penalty（保持既有采样行为）
        this.completionRequest = new AiCompletionRequest(
                ai.getPostGameModel(), ai.getTemperature(),
                null, null, ai.getPostGameMaxTokens(), false);
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成局后锐评
     *
     * @param summary 本局摘要（胜负/比分/车队成员/焦点），由编排层组装
     * @return 可直接发群的锐评正文
     * @throws IllegalStateException API Key 未配置 / 接口失败 / 重试后正文仍为空
     */
    public String generateComment(Map<String, Object> summary) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Post-game comment skipped: API key not configured");
            throw new IllegalStateException("AI API Key 未配置，无法生成局后锐评");
        }
        String systemPrompt = loadPrompt();
        String userContent;
        try {
            userContent = objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.error("Failed to serialize post-game summary: {}", e.getMessage());
            throw new IllegalStateException("局后摘要组装失败", e);
        }

        // 空正文重试：推理模型偶发把输出预算耗在思维链上（finish_reason=length），
        // 重试一次通常能拿到正文；仍为空才判定失败交给编排层降级
        String comment = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && comment == null; attempt++) {
            comment = aiClient.call(completionRequest, systemPrompt, userContent, "post-game");
            if (comment == null) {
                log.warn("Post-game AI empty content, retrying: attempt={}/{}", attempt, MAX_ATTEMPTS);
            }
        }
        if (comment == null) {
            throw new IllegalStateException("AI 返回内容为空，局后锐评生成失败");
        }
        log.info("Post-game comment generated: length={}", comment.length());
        return comment;
    }

    /** 读取局后锐评提示词（classpath；缺失时回退内置默认，保证接口可用） */
    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource(promptFile);
            if (resource.exists()) {
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load post-game prompt file {}: {}", promptFile, e.getMessage());
        }
        return "你是车队开黑群的锐评官，根据提供的本局数据用中文写一段 200-300 字火力全开的锐评，"
                + "点名最亮眼与最拉胯的人；重点词可用 **加粗** 标记（不超过 4 处），不要标题、列表、代码块。";
    }
}
