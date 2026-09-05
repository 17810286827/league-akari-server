package com.leagueakari.qqbot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QQ 事件解析器单元测试：WS 下行的 JSON payload → 事件模型。
 * 连接/心跳/重连属网络生命周期，不在单测范围（冒烟见集成）
 */
class QqEventDispatcherTest {

    private final QqEventDispatcher dispatcher = new QqEventDispatcher();

    /** 用例：机器人入群事件 → 解析出群 openid（供配置 push.group-open-id） */
    @Test
    void groupAddRobotEvent_extractsGroupOpenId() {
        String payload = """
                {"op":0,"s":1,"t":"GROUP_ADD_ROBOT","d":{
                  "group_openid":"GROUP_OPEN_ID_123",
                  "op_member_openid":"MEMBER_1",
                  "timestamp":"2026-09-02T12:00:00+08:00"}}""";

        QqEventDispatcher.GroupEvent event = dispatcher.parse(payload);

        assertThat(event).isNotNull();
        assertThat(event.getType()).isEqualTo("GROUP_ADD_ROBOT");
        assertThat(event.getGroupOpenId()).isEqualTo("GROUP_OPEN_ID_123");
    }

    /** 用例：机器人被移出群事件 → 解析出群 openid */
    @Test
    void groupDelRobotEvent_extractsGroupOpenId() {
        String payload = """
                {"op":0,"s":2,"t":"GROUP_DEL_ROBOT","d":{
                  "group_openid":"GROUP_OPEN_ID_456"}}""";

        QqEventDispatcher.GroupEvent event = dispatcher.parse(payload);

        assertThat(event).isNotNull();
        assertThat(event.getType()).isEqualTo("GROUP_DEL_ROBOT");
        assertThat(event.getGroupOpenId()).isEqualTo("GROUP_OPEN_ID_456");
    }

    /** 用例：与机器人无关的事件（群 @ 消息等）→ 返回 null（忽略，不打扰） */
    @Test
    void unrelatedEvent_returnsNull() {
        String payload = """
                {"op":0,"s":3,"t":"GROUP_AT_MESSAGE_CREATE","d":{
                  "group_openid":"G","author":{"member_openid":"M"}}}""";

        assertThat(dispatcher.parse(payload)).isNull();
    }

    /** 用例：心跳/握手帧（无 t）→ 返回 null */
    @Test
    void nonEventFrame_returnsNull() {
        assertThat(dispatcher.parse("{\"op\":11}")).isNull();
        assertThat(dispatcher.parse("{\"op\":10,\"d\":{\"heartbeat_interval\":41250}}")).isNull();
    }

    /** 用例：畸形 payload → 返回 null 不抛（连接稳定性优先） */
    @Test
    void malformedPayload_returnsNull() {
        assertThat(dispatcher.parse("not-json")).isNull();
        assertThat(dispatcher.parse("")).isNull();
        assertThat(dispatcher.parse(null)).isNull();
    }
}
