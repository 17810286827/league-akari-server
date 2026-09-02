package com.leagueakari.qqbot;

import com.leagueakari.config.PushProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QqEventWsClient 连接前置判定测试：
 * WS 通道只需开关与机器人凭证（appId/secret）——群 openid 由入群事件获取，
 * 不应作为连接前置（鸡生蛋问题回归：曾因要求 group-open-id 导致 WS 永远无法启动）
 */
class QqEventWsClientTest {

    private QqEventWsClient client(boolean enabled, String appId, String secret) {
        PushProperties props = new PushProperties();
        props.setAppId(appId);
        props.setClientSecret(secret);
        props.setGroupOpenId("");
        // canConnect 不涉及 OpenAPI 客户端，传 null 即可
        return new QqEventWsClient(props, new QqEventDispatcher(), null, enabled,
                "wss://api.bot.qq.com/websocket");
    }

    /** 用例：开关开 + 凭证齐 → 可连接 */
    @Test
    void canConnect_whenEnabledWithCredentials() {
        assertThat(client(true, "app-1", "secret-1").canConnect()).isTrue();
    }

    /** 用例：开关关 → 不连接（即使凭证齐） */
    @Test
    void canConnect_falseWhenDisabled() {
        assertThat(client(false, "app-1", "secret-1").canConnect()).isFalse();
    }

    /** 用例：群 openid 为空不影响连接（openid 正是要靠事件拿的） */
    @Test
    void canConnect_ignoresGroupOpenId() {
        QqEventWsClient c = client(true, "app-1", "secret-1");
        assertThat(c.canConnect()).isTrue();
    }

    /** 用例：凭证缺失 → 不连接 */
    @Test
    void canConnect_falseWhenCredentialsMissing() {
        assertThat(client(true, "", "secret-1").canConnect()).isFalse();
        assertThat(client(true, "app-1", "").canConnect()).isFalse();
    }
}
