package com.leagueakari.config;

import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 时间线复盘规则参数（prefix = "replay"，工单 #35）：
 * 转折点规则引擎的判定阈值全部收敛在此，yml 可调（改参数无需改代码）。
 * 配置键：replay.wipe-window-ms（团灭判定时间窗）、
 * replay.extreme-min-gold-diff（经济差极值的最小显著门槛）
 */
@Data
@Component
@ConfigurationProperties(prefix = "replay")
public class ReplayProperties {

    /**
     * 团灭判定时间窗（毫秒）：该窗口内一支队伍的全部成员各自阵亡一次即判定团灭。
     * LCU/SGP 时间线粒度为帧（约 1 分钟），窗口默认放宽到 12 秒
     */
    private long wipeWindowMs = 12_000L;

    /**
     * 经济差极值的最小显著门槛（金币）：最大经济差绝对值低于该值不算"极值"转折点
     * （避免前期几百金的微弱领先被标为胜负手）
     */
    private double extremeMinGoldDiff = 1500D;
}
