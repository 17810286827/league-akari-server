package com.leagueakari.ai;

/**
 * 流式 AI 调用的回调接口：{@link AiClient#callStream} 在线程池中逐行解析 SSE，
 * 每收到一个增量块就回调对应方法，调用方在回调里转发给前端（打字机效果）。
 * <p>函数式接口：只关心正文的调用方用单个 lambda 即可（如
 * {@code callStream(req, sys, user, chunk -> out.write(chunk))}）；
 * 需要思维链的调用方（单局分析，前端灰字展示推理过程）用匿名类同时覆写两个方法。</p>
 * <p>回调内抛出的运行时异常（如客户端断开信号）会原样穿透 callStream 上抛，
 * 用于让流式主流程立即终止、停止上游消费。</p>
 */
@FunctionalInterface
public interface AiStreamHandler {

    /**
     * 正文增量（delta.content）：最终回答的片段，每收到一个调用一次
     *
     * @param chunk 正文增量片段
     */
    void onContent(String chunk);

    /**
     * 思维链增量（delta.reasoning_content）：推理模型的中间思考片段，每收到一个调用一次。
     * 默认忽略——只有需要在前端展示"正在思考"的场景才覆写
     *
     * @param chunk 思维链增量片段
     */
    default void onReasoning(String chunk) {
        // 默认空实现：不关心思维链的调用方静默丢弃
    }
}
