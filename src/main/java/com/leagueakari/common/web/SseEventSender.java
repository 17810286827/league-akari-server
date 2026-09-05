package com.leagueakari.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 事件推送的共享原语（单局 AI 分析与周报锐评两个流式服务复用，工单 #33 抽取）：
 * 把"事件组装 + JSON 序列化 + 推送失败分类"收敛到一处，避免各服务复制 send 方法。
 * <p>推送失败二分（与抽取前 AiAnalysisService 的语义逐字一致）：</p>
 * <ul>
 *   <li>客户端断开（Broken pipe 等，经 {@link ClientDisconnectDetector} 判定）→
 *       抛 {@link ClientDisconnectedException}，调用方据此<b>立即终止</b>上游流消费
 *       （不推 error、不调 complete，连接已断均无意义），仅记 INFO 无堆栈</li>
 *   <li>其余推送失败（SseEmitter 未初始化竞态等）→ 抛 IllegalStateException，
 *       完整堆栈，由调用方统一 error 收尾</li>
 * </ul>
 */
@Slf4j
public final class SseEventSender {

    private SseEventSender() {
        // 工具类：禁止实例化
    }

    /**
     * 推送一条 SSE 事件：data 为 JSON 字符串（type 字段 + 业务字段）。
     *
     * @param emitter      SSE 连接
     * @param objectMapper JSON 序列化器（复用 Spring 上下文单例）
     * @param type         事件类型（start/chunk/reasoning/reasoning-reset/done/error）
     * @param payload      业务字段（写入 data JSON）
     */
    public static void send(SseEmitter emitter, ObjectMapper objectMapper,
            String type, Map<String, Object> payload) {
        try {
            // LinkedHashMap 保证 type 位于 JSON 首位，便于前端/测试解析
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.putAll(payload);
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            if (ClientDisconnectDetector.isClientDisconnect(e)) {
                // 客户端已断开（关页面/刷新/网络闪断）：预期现象——INFO 无堆栈，
                // 抛专用信号让流式主流程立即终止（停止上游消费与后续推送）
                log.info("SSE client disconnected, stop pushing: type={}", type);
                throw new ClientDisconnectedException(e);
            }
            // 其余推送失败（SseEmitter 未初始化竞态等）：完整堆栈，由外层统一收尾
            log.error("SSE send failed: type={}, payload={}", type, payload, e);
            throw new IllegalStateException("SSE 推送失败: " + e.getMessage());
        }
    }

    /**
     * 客户端断开信号：SSE 推送因对端关闭连接失败时由 {@link #send} 抛出，
     * 用于让流式主流程<b>立即终止</b>（停止上游 AI 流消费与后续推送），
     * 并与普通推送失败（IllegalStateException，需推 error 事件）区分处理
     */
    public static class ClientDisconnectedException extends RuntimeException {

        public ClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
