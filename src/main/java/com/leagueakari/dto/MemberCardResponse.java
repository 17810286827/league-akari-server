package com.leagueakari.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 车队成员卡响应：个人视角的成长数据
 * <p>与周报/榜单的"车队对局"口径不同——成员卡是个人视角，
 * 统计该成员参与的全部对局（含单人局，历史回填的数据在这里体现价值）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberCardResponse {

    /** 成员 puuid */
    private String puuid;

    /** 成员 riotId（"昵称#tag"） */
    private String riotId;

    /** 成长曲线：近 N 周逐周统计（周口径与周报一致，最早周在前） */
    private List<TrendPoint> trend;

    /** 英雄统计与基线对比（按场次降序） */
    private List<ChampionStat> champions;

    /**
     * 逐周趋势点
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendPoint {

        /** 周标签（该周周一日期，yyyy-MM-dd） */
        private String weekLabel;

        /** 该周对局数 */
        private int games;

        /** 该周胜率（0-1），无对局时为 null */
        private Double winRate;

        /** 该周场均 op_score，无对局时为 null */
        private Double avgOpScore;
    }

    /**
     * 英雄统计：本人数据 vs 全库基线（scoring_baseline）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChampionStat {

        /** 英雄 ID */
        private int championId;

        /** 英雄中文名 */
        private String championName;

        /** 场次 */
        private int games;

        /** 胜场 */
        private int wins;

        /** 场均 op_score（无评分数据时为 null） */
        private Double avgOpScore;

        /** 本人分均伤害（对英雄） */
        private Double avgDamagePerMin;

        /** 全库同英雄分均伤害基线（基线无样本时为 null） */
        private Double baselineDamagePerMin;
    }
}
