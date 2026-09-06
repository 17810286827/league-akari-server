package com.leagueakari.ai;

import lombok.Value;

/**
 * 一次 AI 调用的采样参数（公共 {@link AiClient} 的请求参数对象，Lombok {@code @Value} 不可变对象）：
 * 由各业务场景（单局分析/复盘叙述/周报锐评/局后锐评）在构造时从 AiProperties 读取统一键后
 * <b>显式组装</b>传入——AiClient 不感知业务场景、不读场景级配置键（见 docs/adr/0005）。
 * <p>字段语义与 chat/completions 请求体一一对应：</p>
 * <ul>
 *   <li>{@code frequencyPenalty}/{@code presencePenalty} 为 <b>null 时该参数不进 payload</b>
 *       （周报/局后场景不传 penalty，保持既有采样行为）</li>
 *   <li>思考模式走 DeepSeek 官方嵌套参数 {@code thinking:{type:enabled|disabled}}——
 *       2026-09-07 网关实测（ADR 0009）：vLLM 式 chat_template_kwargs 开关/预算网关不执行，
 *       官方嵌套 disabled 连续 3 轮 reasoning 为 0（真实生效）</li>
 *   <li>{@code thinkingEffort} 非 null 时并入 {@code thinking:{reasoning_effort}}（low/high/max，
 *       降思考强度用；null = 不传，网关默认强度）</li>
 * </ul>
 */
@Value
public class AiCompletionRequest {

    /** 模型名（来自 ai.model，全场景统一） */
    String model;

    /** 采样温度：降随机性，抑制长文本重复 */
    double temperature;

    /** 频率惩罚：惩罚已出现过的词；null = 不传 */
    Double frequencyPenalty;

    /** 存在惩罚：鼓励引入新话题；null = 不传 */
    Double presencePenalty;

    /** 输出 token 上限（思维链与正文共享预算，全场景统一 ai.max-tokens） */
    int maxTokens;

    /** 是否开启思考模式（true = 官方嵌套 thinking:{type:enabled}；false = type:disabled 直出正文） */
    boolean thinking;

    /**
     * 思考强度（官方 reasoning_effort：low/high/max）；null = 不传该键（网关默认强度）。
     * 仅 thinking=true 时有意义（false 时思维链本就被关闭，强度无意义）
     */
    String thinkingEffort;
}
