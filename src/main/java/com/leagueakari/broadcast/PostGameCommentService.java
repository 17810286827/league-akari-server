package com.leagueakari.broadcast;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.ai.AiClient;
import com.leagueakari.ai.AiCompletionRequest;
import com.leagueakari.ai.PromptLoader;
import com.leagueakari.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 局后锐评服务（业务编排层）：战报图发送后，按车队视角对本局做一次非流式 AI 点评，
 * 输出可直接发群的正文。区别于单局 AI 分析（self 视角、SSE 流式）与周报锐评（按周聚合）。
 * <p>HTTP 调用与传输层原语统一走公共 {@link AiClient}（见 docs/adr/0005）：
 * 空正文重试走 call 重载、提示词加载走 PromptLoader（架构清理 T7），
 * 本服务只剩缺席降级的业务语义。</p>
 * <p>失败语义：空正文自动重试 1 次（共最多 2 次调用，AiClient 重载承载），
 * 仍失败抛 BizException(AI_API_ERROR)，由 BroadcastCoordinator 降级为"AI 缺席提示"发送。</p>
 */
@Slf4j
@Service
public class PostGameCommentService {

    /** 最大调用次数：1 次正常 + 1 次空正文重试（传给 AiClient 重载） */
    private static final int MAX_ATTEMPTS = 2;

    /** 局后锐评提示词文件（classpath；缺失时回退内置默认，保证接口可用） */
    private final String promptFile;

    /** 提示词加载器：文件读取 + 内置默认回退（全项目唯一实现，架构清理 T7） */
    private final PromptLoader promptLoader;

    /** 采样参数（组装后经 AiCompletionRequest 显式传给 AiClient，见 docs/adr/0005） */
    private final AiCompletionRequest completionRequest;

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    /** 构造注入：AI 配置统一取自 AiProperties（yaml 唯一真值，见 docs/adr/0004） */
    public PostGameCommentService(
            AiProperties ai,
            AiClient aiClient,
            ObjectMapper objectMapper,
            PromptLoader promptLoader) {
        this.promptFile = ai.getPostGamePromptFile();
        this.promptLoader = promptLoader;
        // 局后锐评场景采样参数：独立模型键（ai.post-game-model）、无 penalty（保持既有采样行为）
        this.completionRequest = new AiCompletionRequest(
                ai.getPostGameModel(), ai.getTemperature(),
                null, null, ai.getPostGameMaxTokens(), ai.isThinking());
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成局后锐评
     *
     * @param summary 本局摘要（胜负/比分/车队成员/焦点），由编排层组装
     * @return 可直接发群的锐评正文
     * AI Key 未配置 / 接口失败 / 重试后正文仍为空时抛 BizException（AI_API_ERROR）
     */
    public String generateComment(Map<String, Object> summary) {
        // 提示词：文件读取 + 内置默认回退（PromptLoader 唯一实现）
        String systemPrompt = promptLoader.load(promptFile,
                "你是车队开黑群的锐评官，根据提供的本局数据用中文写一段 200-300 字火力全开的锐评，"
                        + "点名最亮眼与最拉胯的人；重点词可用 **加粗** 标记（不超过 4 处），不要标题、列表、代码块。");
        String userContent;
        try {
            userContent = objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            log.error("Failed to serialize post-game summary: {}", e.getMessage());
            throw new BizException(ErrorCode.DATA_ASSEMBLY_FAILED, "局后摘要组装失败", e);
        }

        // apiKey 前置校验与空正文重试由 AiClient 重载承载（架构清理 T7）
        String comment = aiClient.call(completionRequest, systemPrompt, userContent,
                "post-game", MAX_ATTEMPTS);
        if (comment == null) {
            throw new BizException(ErrorCode.AI_API_ERROR, "AI 返回内容为空，局后锐评生成失败");
        }
        log.info("Post-game comment generated: length={}", comment.length());
        return comment;
    }
}
