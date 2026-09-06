package com.leagueakari.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * AI 大模型调用配置：从 application.yml 的 ai.* 前缀加载，启动时绑定。
 * <p><b>yaml 唯一真值原则</b>：模型名与采样参数只允许存在于 application.yml（含部署层
 * 环境变量覆盖），本类为 yaml 的类型化视图，<b>不设任何默认值</b>——键缺失时由
 * {@link Validated} 校验在启动阶段直接报错，杜绝"代码默认值与 yml 各说各话"的漂移
 * （历史教训：ai.model 曾在 yml=analysis 与 @Value 默认值间出现三处不一致，见 docs/adr/0004）。
 * 新增/调整任何 ai.* 键：先改 yml，再同步本类字段与 AiPropertiesTest 契约测试。</p>
 * <p><b>参数归一（2026-09-07，docs/adr/0009）</b>：四个 AI 场景（单局分析/复盘叙述/
 * 周报锐评/局后锐评）共用 {@code ai.model} 与 {@code ai.max-tokens}——场景级键
 * post-game-model / weekly-max-tokens / post-game-max-tokens 因三值从未分叉、
 * 注释漂移（AiPropertiesTest 断言 4096/2048 而 yml 实为 16384）而删除；
 * 真有分场景需求时按需加回覆盖键。</p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 网关基础地址（OpenAI 兼容 chat/completions），请求实际打到 baseUrl + "/chat/completions" */
    @NotBlank
    private String baseUrl;

    /**
     * API Key（环境变量 AI_API_KEY 覆盖；允许为空——空时四个 AI 服务在调用前
     * 快速失败并返回明确错误，与 push.* 凭证的"未配置降级"语义一致，故不做非空校验）
     */
    private String apiKey;

    /** 模型名（四个 AI 场景统一，参数归一见类注释与 docs/adr/0009） */
    @NotBlank
    private String model;

    /**
     * 是否开启模型思考模式：true = 先输出长思维链再出正文（前端灰字展示推理过程）；
     * false = 直出正文（延迟低）。经 DeepSeek 官方嵌套参数
     * {@code thinking:{type:enabled|disabled}} 透传网关。
     * <b>四个 AI 场景（单局分析/复盘叙述/周报锐评/局后锐评）统一读此键</b>，
     * 不允许任何场景硬编码旁路
     */
    private boolean thinking;

    /**
     * 思考强度（官方 reasoning_effort：low/high/max；null = 不传，网关默认强度）。
     * 仅 thinking=true 时进 payload；网关对该参数的执行效果未在短提示词下
     * 与噪音区分开（2026-09-07 实测），保留为调参管道
     */
    private String thinkingEffort;

    /**
     * 调用失败后的重试次数（不含首次；0 = 不重试）。<b>四个 AI 场景统一读此键</b>：
     * 非流式场景（AiClient.callWithRetry）对空正文自动重试共 retryCount 次；
     * 流式场景（单局分析）在尚未推送任何增量前失败时同样按此次数重试
     */
    @NotNull
    @jakarta.validation.constraints.Min(0)
    private Integer retryCount;

    /** 采样温度：降随机性，抑制长文本重复输出 */
    @NotNull
    private Double temperature;

    /** 频率惩罚：惩罚已出现过的词，抑制循环重复 */
    @NotNull
    private Double frequencyPenalty;

    /** 存在惩罚：鼓励引入新话题，减少车轱辘话 */
    @NotNull
    private Double presencePenalty;

    /** 输出 token 上限（思维链与正文共享预算；四个 AI 场景统一，docs/adr/0009） */
    @NotNull
    private Integer maxTokens;

    /** 单局分析系统提示词文件（classpath，md 格式，可直接编辑） */
    @NotBlank
    private String promptFile;

    /** 时间线复盘叙述提示词文件（团队教练视角，区别于单局分析的毒舌锐评） */
    @NotBlank
    private String replayPromptFile;

    /** 周报锐评提示词文件（classpath；历史上仅存在于代码默认值，统一后补进 yml 作为唯一真值） */
    @NotBlank
    private String weeklyPromptFile;

    /** 局后锐评提示词文件（classpath，车队群视角、正文短） */
    @NotBlank
    private String postGamePromptFile;
}
