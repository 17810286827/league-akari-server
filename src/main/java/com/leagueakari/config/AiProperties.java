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
 * <p>场景分工：{@link #getModel()} 供单局 AI 分析与周报锐评共用；
 * {@link #getPostGameModel()} 是局后播报的独立键——播报对延迟敏感，未来分析模型
 * 更换为慢推理模型时不受影响。部署环境只注入 {@code AI_API_KEY}，其余值以 yml 为准。</p>
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
     * API Key（环境变量 AI_API_KEY 覆盖；允许为空——空时三个 AI 服务在调用前
     * 快速失败并返回明确错误，与 push.* 凭证的"未配置降级"语义一致，故不做非空校验）
     */
    private String apiKey;

    /** 分析模型（单局 AI 分析与周报锐评共用） */
    @NotBlank
    private String model;

    /**
     * 是否开启模型思考模式：true = 先输出长思维链再出正文（前端灰字展示推理过程）；
     * false = 直出正文（延迟低）。通过请求体 chat_template_kwargs.thinking 透传网关
     */
    private boolean thinking;

    /** 采样温度：降随机性，抑制长文本重复输出 */
    @NotNull
    private Double temperature;

    /** 频率惩罚：惩罚已出现过的词，抑制循环重复 */
    @NotNull
    private Double frequencyPenalty;

    /** 存在惩罚：鼓励引入新话题，减少车轱辘话 */
    @NotNull
    private Double presencePenalty;

    /** 单局分析输出 token 上限（思维链与正文共享预算，限制推理模式无限思考） */
    @NotNull
    private Integer maxTokens;

    /** 周报锐评输出 token 上限（推理模型偶发把预算耗在思维链导致正文为空，配合空正文重试） */
    @NotNull
    private Integer weeklyMaxTokens;

    /** 单局分析系统提示词文件（classpath，md 格式，可直接编辑） */
    @NotBlank
    private String promptFile;

    /** 周报锐评提示词文件（classpath；历史上仅存在于代码默认值，统一后补进 yml 作为唯一真值） */
    @NotBlank
    private String weeklyPromptFile;

    /** 局后锐评提示词文件（classpath，车队群视角、正文短） */
    @NotBlank
    private String postGamePromptFile;

    /** 局后锐评输出 token 上限 */
    @NotNull
    private Integer postGameMaxTokens;

    /** 局后锐评独立模型（短任务，直出正文延迟低；与 ai.model 解耦，见类注释） */
    @NotBlank
    private String postGameModel;
}
