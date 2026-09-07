package com.leagueakari.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 敌方情报推送（Pre-Game Intel）配置：从 application.yml 的 intel.* 前缀加载。
 * <p>本配置类目前仅承载开黑判定阈值；推送开关/目标群复用 push.*
 * （敌方情报推送与局后播报共用车队群通道，见 ADR 0011）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "intel")
public class IntelProperties {

    /**
     * 开黑判定阈值：历史对局中同一组合共同出场 ≥ 该次数即判定为开黑组。
     * 口径与桌面端 ongoing-game 的 pre-made 推断一致（team-up-calc），
     * 阈值越大判定越保守（只认"长期固定搭档"），越小越敏感（两局同队即视为开黑）。
     */
    private int premadeDetectThreshold = 10;
}
