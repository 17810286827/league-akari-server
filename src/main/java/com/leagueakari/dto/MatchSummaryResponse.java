package com.leagueakari.dto;

import lombok.Data;

import java.util.List;

/**
 * 对局列表项：分页查询返回的精简字段，
 * 不包含参赛者明细与 JSON 快照，控制列表接口的传输体积
 */
@Data
public class MatchSummaryResponse {

    /** LCU 对局 ID，与主表 game_id 对应 */
    private Long gameId;

    /** 对局创建时间戳（ms） */
    private Long gameCreation;

    /** 对局时长（秒） */
    private Integer gameDuration;

    /** 模式，如 CLASSIC / CHERRY */
    private String gameMode;

    /** 队列 ID */
    private Integer queueId;

    /** 地区，如 na1 */
    private String region;

    /** 获胜队伍 ID */
    private Integer winnerTeamId;

    /** 记录本局的玩家 puuid */
    private String selfPuuid;

    /** 本玩家（selfPuuid）在该局的个人数据，self 行缺失时返回全零占位 */
    private SelfSummary self;

    /** 本玩家所在队伍（teamId 相同的 5 人）的聚合数据，self 行缺失时返回全零占位 */
    private TeamTotals teamTotals;

    /** 同队其余 4 名队友摘要，用于最近队友聚合与卡片队友展示 */
    private List<Teammate> teammates;

    /**
     * 本玩家个人数据：身份与击杀/死亡/助攻来自参赛者直显列，
     * 伤害/经济/补刀/标记字段来自 stats_json 解析（缺失写 0/false）
     */
    @Data
    public static class SelfSummary {

        /** 英雄 ID */
        private Integer championId;

        /** 召唤师名 */
        private String summonerName;

        /** 击杀数 */
        private Integer kills;

        /** 死亡数 */
        private Integer deaths;

        /** 助攻数 */
        private Integer assists;

        /** 本玩家是否获胜 */
        private Boolean win;

        /** 对英雄总伤害（statsJson 的 totalDamageDealtToChampions） */
        private Integer totalDamage;

        /** 承受总伤害（statsJson 的 totalDamageTaken） */
        private Integer totalDamageTaken;

        /** 获得金币（statsJson 的 goldEarned） */
        private Integer goldEarned;

        /** 补刀数（statsJson 的 totalMinionsKilled） */
        private Integer cs;

        /** 最大连杀数，用于"四杀"标记 */
        private Integer largestMultiKill;

        /** 推塔数，用于"拆塔"标记 */
        private Integer turretKills;

        /** 该局是否以投降结束（statsJson 的 gameEndedInSurrender） */
        private Boolean gameEndedInSurrender;
    }

    /**
     * 本玩家所在队伍的五人聚合：击杀 / 经济 / 伤害 / 承伤
     */
    @Data
    public static class TeamTotals {

        /** 全队总击杀 */
        private Integer kills;

        /** 全队总经济（各参赛者直显 goldEarned 之和） */
        private Integer gold;

        /** 全队对英雄总伤害之和（totalDamageDealtToChampions） */
        private Integer damage;

        /** 全队承受总伤害之和（totalDamageTaken） */
        private Integer damageTaken;
    }

    /**
     * 队友摘要：同队其余 4 名玩家的精简信息
     */
    @Data
    public static class Teammate {

        /** 玩家 puuid */
        private String puuid;

        /** 召唤师名 */
        private String summonerName;

        /** 英雄 ID */
        private Integer championId;

        /** 是否获胜 */
        private Boolean win;
    }
}
