package com.leagueakari.dto.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 车队周报响应：以"车队对局"（同局成员数达阈值的对局）为单位聚合一周数据
 * <p>周口径为自然周（周一 00:00 ~ 次周一 00:00，Asia/Shanghai）；
 * aiComment 为可选字段——AI 通道不可用时为 null，周报主体不受影响。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyReportResponse {

    /** 本周起始：周一 00:00（Asia/Shanghai）epoch 毫秒 */
    private Long weekStartMs;

    /** 本周结束：次周一 00:00（Asia/Shanghai）epoch 毫秒（开区间） */
    private Long weekEndMs;

    /** 周标签，如 "2026-08-24 ~ 2026-08-30" */
    private String weekLabel;

    /** 车队名（来自 team.name 配置，分享图/标题展示用） */
    private String teamName;

    /** 上周总览 */
    private Overview overview;

    /** MVP 榜（MVP+SVP 次数） */
    private List<BoardEntry> mvpBoard;

    /** 场均 op_score 排行（降序，与战犯榜同口径反向） */
    private List<BoardEntry> opScoreBoard;

    /** 战犯榜（场均 op_score 升序，越靠前越"战犯"） */
    private List<BoardEntry> criminalBoard;

    /** 送头王榜（场均死亡降序） */
    private List<BoardEntry> feederBoard;

    /** Carry 王榜（场均击杀参与率降序） */
    private List<BoardEntry> carryBoard;

    /** 绝活榜（成员×英雄，场次 ≥2，场均 op_score 降序） */
    private List<BoardEntry> signatureBoard;

    /** 出勤榜（参与场次数降序） */
    private List<BoardEntry> attendanceBoard;

    /** 名场面（时间线抽取，可能整体为 null——当周无任何可用时间线） */
    private Highlights highlights;

    /** AI 一句话锐评（AI 不可用时为 null） */
    private String aiComment;

    /**
     * 上周总览：场次按"车队对局"计；胜负按"成员人次"计
     * （两人分属敌我两队的极端局，按每人各自胜负统计，胜+负=人次）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Overview {

        /** 车队对局数 */
        private int gameCount;

        /** 成员参与人次（∑ 每局车队成员数） */
        private int memberGameCount;

        /** 成员人次胜场 */
        private int winCount;

        /** 成员人次败场 */
        private int lossCount;

        /** 车队对局总时长（秒，按对局去重求和） */
        private long totalDurationSeconds;

        /** 对局最密集的一天（yyyy-MM-dd），无对局时为 null */
        private String busiestDay;

        /** 最密集一天的局数 */
        private int busiestDayGames;

        /** 本周有出勤的成员（riotId，按配置顺序） */
        private List<String> activeMembers;
    }

    /**
     * 榜单条目：value 为主排序值（已四舍五入保留两位小数），detail 为人类可读的补充说明。
     * 绝活榜额外携带英雄结构化字段（championId/championName/games/wins），
     * 供前端按英雄分组展示；其余榜单这些字段为 null
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BoardEntry {

        /** 成员 puuid */
        private String puuid;

        /** 成员 riotId（"昵称#tag"） */
        private String riotId;

        /** 主值（含义随榜单不同：次数/场均值/场次数），保留两位小数 */
        private Double value;

        /** 补充说明，如 "MVP×1 SVP×1"、"12场 胜率58%"、"英雄×场次" */
        private String detail;

        /** 英雄 ID（仅绝活榜填充） */
        private Integer championId;

        /** 英雄中文名（仅绝活榜填充） */
        private String championName;

        /** 该英雄场次数（仅绝活榜填充） */
        private Integer games;

        /** 该英雄胜场数（仅绝活榜填充） */
        private Integer wins;
    }

    /**
     * 名场面集合：各条目独立，缺失的维度为 null（如当周没有五杀）；
     * missingTimelineCount 标注因时间线缺失而被跳过的对局数（名场面覆盖度提示）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Highlights {

        /** 最大翻盘局：落后最多的绝地翻盘 */
        private HighlightItem biggestComeback;

        /** 最惨连败：成员最长连续败场 */
        private HighlightItem worstStreak;

        /** 多杀时刻：当周最高连杀（五杀 > 四杀 > 三杀） */
        private HighlightItem multiKillMoment;

        /** 单局最高击杀（车队成员） */
        private HighlightItem mostKillsGame;

        /** 时间线缺失、被名场面抽取跳过的对局数 */
        private int missingTimelineCount;
    }

    /**
     * 单条名场面
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HighlightItem {

        /** 所属对局 gameId */
        private Long gameId;

        /** 标题，如 "五杀时刻"、"绝地翻盘" */
        private String title;

        /** 人类可读描述 */
        private String detail;

        /** 量化值（翻盘金币差/连败场数/连杀长度/击杀数） */
        private Double value;
    }
}
