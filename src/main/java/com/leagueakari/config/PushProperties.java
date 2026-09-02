package com.leagueakari.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 局后播报（QQ 群推送）配置：从 application.yml 的 push.* 前缀加载，启动时绑定。
 * <p>机器人凭证与车队群 openid 属部署机密，生产环境用环境变量覆盖
 * （PUSH_ENABLED / PUSH_GROUP_OPEN_ID / QQ_BOT_APP_ID / QQ_BOT_CLIENT_SECRET）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "push")
public class PushProperties {

    /**
     * 总开关：false 时局后播报判定直接跳过，不影响对局同步主链路。
     * 上线顺序建议：先 false 部署观察 → 开图关锐评 → 全开
     */
    private boolean enabled = false;

    /**
     * 车队群 openid（QQ 官方开放平台下发的群标识，非群号）：
     * 机器人被拉入群后服务端收到 GROUP_ADD_ROBOT 事件可得，填入此处
     */
    private String groupOpenId = "";

    /** 机器人 appId（q.qq.com 应用管理） */
    private String appId = "";

    /** 机器人 clientSecret（q.qq.com 应用管理） */
    private String clientSecret = "";

    /**
     * 判定时间窗（分钟）：落库时刻距"估算的对局结束时刻"
     * （game_creation + game_duration*1000）超过该窗口视为旧局，不播报
     */
    private int recentWindowMinutes = 30;

    /**
     * 局后锐评开关：false 时战报图发送后直接完成（SENT），
     * 不触发 AI 生成、不发缺席提示（AI 不可用时的降级体验由 true 提供）
     */
    private boolean aiCommentEnabled = true;

    /** 配置是否齐备：开关之外还需要群与凭证都已填写 */
    public boolean isConfigured() {
        return groupOpenId != null && !groupOpenId.isBlank()
                && appId != null && !appId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
