package com.leagueakari.ai;

import lombok.Value;

/**
 * 一次 AI 调用的采样参数（公共 {@link AiClient} 的请求参数对象，Lombok {@code @Value} 不可变对象）：
 * 由各业务场景（单局分析/周报锐评/局后锐评）在构造时从 AiProperties 读取自己那几个键后
 * <b>显式组装</b>传入——AiClient 不感知业务场景、不读场景级配置键（见 docs/adr/0005）。
 * <p>字段语义与 chat/completions 请求体一一对应：</p>
 * <ul>
 *   <li>{@code frequencyPenalty}/{@code presencePenalty} 为 <b>null 时该参数不进 payload</b>
 *       （周报/局后场景不传 penalty，保持既有采样行为）</li>
 *   <li>{@code thinking=false} 时写 chat_template_kwargs.thinking=false（DeepSeek 原生参数
 *       直出正文）；{@code true} 时不写该键（保持模型默认推理模式，思维链经回调透传）</li>
 * </ul>
 */
@Value
public class AiCompletionRequest {

    /** 模型名（来自 ai.model 或 ai.post-game-model） */
    String model;

    /** 采样温度：降随机性，抑制长文本重复 */
    double temperature;

    /** 频率惩罚：惩罚已出现过的词；null = 不传 */
    Double frequencyPenalty;

    /** 存在惩罚：鼓励引入新话题；null = 不传 */
    Double presencePenalty;

    /** 输出 token 上限（思维链与正文共享预算） */
    int maxTokens;

    /** 是否开启思考模式（false = 写 chat_template_kwargs.thinking=false 直出正文） */
    boolean thinking;
}
