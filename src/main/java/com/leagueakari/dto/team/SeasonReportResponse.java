package com.leagueakari.dto.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 赛季报告响应（工单 #42 / spec #32）：
 * 赛季维度的车队总结——版本胜率曲线、成员英雄池随版本漂移、高光时刻。
 * 赛季起止由人工指定（不做自动判定/定时任务）；版本归一复用
 * GameVersionNormalizer 口径（T9 #38）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeasonReportResponse {

    /** 赛季内车队对局总数 */
    private int totalGames;

    /** 总胜率（人次口径，0-1） */
    private double totalWinRate;

    /** 版本胜率序列（主版本升序——版本胜率曲线数据源） */
    private List<VersionStat> versionStats;

    /** 成员英雄池漂移（roster 顺序） */
    private List<MemberDrift> memberDrifts;

    /** 高光时刻：单局最高击杀（复用名场面口径） */
    private Highlight mostKills;

    /** 版本胜率（曲线单点） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VersionStat {

        /** 主版本（"16.15"，归一口径） */
        private String version;

        /** 该版本的车队对局数 */
        private int games;

        /** 人次胜场 */
        private int wins;

        /** 人次负场 */
        private int losses;

        /** 胜率（0-1） */
        private double winRate;
    }

    /** 成员英雄池漂移：该成员在各版本玩什么英雄、几局 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberDrift {

        /** 成员 riotId */
        private String riotId;

        /** 各版本的英雄分布（主版本升序） */
        private List<VersionChampions> versions;
    }

    /** 单版本的英雄分布 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VersionChampions {

        /** 主版本 */
        private String version;

        /** 英雄中文名 → 局数（按局数降序） */
        private List<Map.Entry<String, Integer>> champions;
    }

    /** 高光时刻（与周报名场面 HighlightItem 同构） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Highlight {

        /** 所属对局 gameId */
        private Long gameId;

        /** 标题（如"单局最高击杀"） */
        private String title;

        /** 人类可读描述 */
        private String detail;
    }
}
