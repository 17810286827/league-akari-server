package com.leagueakari.qqbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * QQ 官方 WS 事件分发解析（纯函数，可单测）：
 * 把网关下行的 JSON 帧解析为关注的事件模型。
 * 目前只关心群生命周期事件（入群/退群），用于提示把群 openid 填入配置；
 * 其余事件（群消息等）忽略——本功能推送是纯主动出站，不消费群聊消息。
 */
@Slf4j
@Component
public class QqEventDispatcher {

    /** 关注的群事件：入群（可得 group_openid）与退群 */
    private static final String GROUP_ADD_ROBOT = "GROUP_ADD_ROBOT";
    private static final String GROUP_DEL_ROBOT = "GROUP_DEL_ROBOT";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 解析后的事件：类型 + 群 openid */
    public record GroupEvent(String type, String groupOpenId) {}

    /**
     * 解析一帧 WS payload：
     *
     * @param payload 下行 JSON 文本
     * @return 群生命周期事件；非关注事件/心跳帧/畸形输入返回 null（绝不抛异常，连接稳定性优先）
     */
    public GroupEvent parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("QQ WS payload parse failed: {}", e.getMessage());
            return null;
        }
        String type = root.path("t").asText("");
        if (!GROUP_ADD_ROBOT.equals(type) && !GROUP_DEL_ROBOT.equals(type)) {
            return null;
        }
        String groupOpenId = root.path("d").path("group_openid").asText("");
        if (groupOpenId.isBlank()) {
            log.warn("QQ group event missing group_openid: type={}", type);
            return null;
        }
        return new GroupEvent(type, groupOpenId);
    }
}
