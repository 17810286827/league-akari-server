package com.leagueakari.dto.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 组合扩展统计响应（工单 #41 / spec #31）：时段胜率 + 常用阵容。
 * 与搭档矩阵同口径——只统计车队对局，胜负按成员人次计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DuoExtendedResponse {

    /** 时段胜率列表（固定五个时段桶，按语义顺序） */
    private List<TimeSlotStats> timeSlots;

    /** 常用阵容列表（按局数降序；≥2 人同局的成员组合） */
    private List<LineupStats> lineups;

    /**
     * 时段统计：分桶键固定五档——
     * morning（06-12 上午）/ afternoon（12-18 下午）/ evening（18-24 晚间）/
     * lateNight（00-03 深夜）/ weeHours（03-06 凌晨）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSlotStats {

        /** 时段键（morning/afternoon/evening/lateNight/weeHours） */
        private String key;

        /** 时段中文名（如"晚间开黑"） */
        private String label;

        /** 局数 */
        private int games;

        /** 人次胜场 */
        private int wins;

        /** 人次负场 */
        private int losses;

        /** 胜率（0-1）；0 局时为 null */
        private Double winRate;
    }

    /** 常用阵容：同局出战的成员组合（≥2 人） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineupStats {

        /** 阵容成员（riotId，按 roster 顺序） */
        private List<String> members;

        /** 同局局数 */
        private int games;

        /** 人次胜场 */
        private int wins;

        /** 人次负场 */
        private int losses;

        /** 胜率（0-1）；局数为 0 不出现 */
        private Double winRate;
    }
}
