package com.leagueakari.dto.replay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 时间线复盘响应（工单 #35 / spec #29）：
 * 从时间线 frames 确定性计算的对局过程数据——经济差序列、击杀事件标记、
 * 关键转折点列表。视角（perspectiveTeamId）为"我方"队伍，goldDiff 正 = 我方领先。
 * <p>无时间线数据的对局 available=false（降级，前端显示提示而非空白曲线）；
 * 转折点由规则引擎确定性提取（可从 frames 复算验证），不依赖 AI。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayResponse {

    /** 时间线数据是否可用（无时间线/快照损坏时 false，其余字段为空集合） */
    private boolean available;

    /** 视角队伍 ID（"我方"）：self 参与者所在队；解析不出时由引擎回退 */
    private Integer perspectiveTeamId;

    /** 经济差序列（逐帧，正 = 我方领先） */
    private List<GoldDiffPoint> goldDiffSeries;

    /** 击杀事件标记（全部英雄击杀，供曲线标记与悬停） */
    private List<KillEvent> killEvents;

    /** 关键转折点列表（按时间排序，规则引擎确定性提取） */
    private List<TurningPoint> turningPoints;

    /** 经济差曲线单点 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GoldDiffPoint {

        /** 帧时间戳（毫秒，对局内时间） */
        private long timestampMs;

        /** 双方经济差（我方总金币 - 敌方总金币） */
        private double goldDiff;
    }

    /** 击杀事件（曲线标记用） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KillEvent {

        /** 事件时间戳（毫秒，对局内时间） */
        private long timestampMs;

        /** 击杀者召唤师名 */
        private String killerName;

        /** 击杀者英雄中文名 */
        private String killerChampion;

        /** 被击杀者召唤师名 */
        private String victimName;

        /** 被击杀者英雄中文名 */
        private String victimChampion;

        /** 击杀者是否属于我方（标记颜色区分敌我） */
        private boolean killerIsPerspective;
    }

    /**
     * 关键转折点：规则引擎从 frames 确定性提取的对局势能变化节点。
     * type 取 TurningPointEngine 的常量（FIRST_BLOOD/TEAM_WIPE/BARON/
     * GOLD_DIFF_EXTREME/GOLD_LEAD_CHANGE）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TurningPoint {

        /** 转折点类型（引擎常量） */
        private String type;

        /** 事件时间戳（毫秒，对局内时间） */
        private long timestampMs;

        /** 事件时刻的双方经济差 */
        private double goldDiff;

        /** 转折点标题（中文短语，如"一血"、"团灭"） */
        private String title;

        /** 人类可读描述（含涉及成员与敌我视角） */
        private String detail;

        /** 涉及成员（击杀者/被团灭者等，事件明细） */
        private List<InvolvedPlayer> involved;
    }

    /** 转折点涉及成员 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvolvedPlayer {

        /** 召唤师名 */
        private String name;

        /** 英雄中文名 */
        private String championName;

        /** 是否属于我方 */
        private boolean perspective;
    }
}
