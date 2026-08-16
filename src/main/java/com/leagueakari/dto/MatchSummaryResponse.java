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

    /** 地图 ID（真实值，与详情接口一致；折叠卡塔杀标签等按地图口径计算） */
    private Integer mapId;

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

    /** 双方 10 人轻量档案（含 self，前端以 puuid 区分），供列表页折叠卡展示 */
    private List<ParticipantLight> participants;

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

        /** 出装（statsJson 的 item0-6，按槽位顺序） */
        private List<Integer> items;

        /** 召唤师技能（statsJson 的 spell1Id/spell2Id，按槽位顺序） */
        private List<Integer> summonerSpells;

        /** 海克斯强化（statsJson 的 playerAugment1-6，按槽位顺序） */
        private List<Integer> augments;

        /** 符文配置（perks：LCU 平铺字段或 SGP 嵌套对象） */
        private ParticipantPerks perks;

        /** 双杀次数（statsJson 的 doubleKills） */
        private Integer doubleKills;

        /** 三杀次数（statsJson 的 tripleKills） */
        private Integer tripleKills;

        /** 四杀次数（statsJson 的 quadraKills） */
        private Integer quadraKills;

        /** 五杀次数（statsJson 的 pentaKills） */
        private Integer pentaKills;
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

    /**
     * 参赛者符文配置：perkIds 为主系+副系共 6 颗符文，
     * 兼容 LCU 平铺（perk0-5 + perkPrimaryStyle + perkSubStyle）与 SGP 嵌套（perks 对象）两种来源
     */
    @Data
    public static class ParticipantPerks {

        /** 符文 ID 列表（perk0-5，或 SGP 嵌套 perks.perkIds） */
        private List<Integer> perkIds;

        /** 主系符文页样式 ID（perkPrimaryStyle，或 SGP 嵌套 perks.perkStyle） */
        private Integer perkStyle;

        /** 副系符文页样式 ID（perkSubStyle，或 SGP 嵌套 perks.perkSubStyle） */
        private Integer perkSubStyle;
    }

    /**
     * 参赛者轻量档案：双方 10 人全量（含 self，前端以 puuid 区分），
     * 供列表页折叠卡展示装备/技能/海克斯/符文
     */
    @Data
    public static class ParticipantLight {

        /** 玩家 puuid，前端以此区分 self */
        private String puuid;

        /** 召唤师名 */
        private String summonerName;

        /** 英雄 ID */
        private Integer championId;

        /** 队伍 ID（100 蓝方 / 200 红方） */
        private Integer teamId;

        /** 分路，如 TOP / JUNGLE / MIDDLE / BOTTOM / UTILITY */
        private String position;

        /** 是否获胜 */
        private Boolean win;

        /** 击杀数 */
        private Integer kills;

        /** 死亡数 */
        private Integer deaths;

        /** 助攻数 */
        private Integer assists;

        /** 出装（statsJson 的 item0-6，按槽位顺序） */
        private List<Integer> items;

        /** 召唤师技能（statsJson 的 spell1Id/spell2Id，按槽位顺序） */
        private List<Integer> summonerSpells;

        /** 海克斯强化（statsJson 的 playerAugment1-6，按槽位顺序） */
        private List<Integer> augments;

        /** 符文配置（perks：LCU 平铺或 SGP 嵌套） */
        private ParticipantPerks perks;

        /** 对英雄造成的总伤害（statsJson 的 totalDamageDealtToChampions，折叠卡雷达图/伤害占比使用） */
        private Integer totalDamageDealtToChampions;

        /** 承受总伤害（statsJson 的 totalDamageTaken，折叠卡统计行使用） */
        private Integer totalDamageTaken;

        /** 治疗量（statsJson 的 totalHeal） */
        private Integer totalHeal;

        /** 视野得分（statsJson 的 visionScore） */
        private Integer visionScore;

        /** 获得金币（statsJson 的 goldEarned） */
        private Integer goldEarned;

        /** 补刀数（statsJson 的 totalMinionsKilled） */
        private Integer cs;

        /** 推塔数（statsJson 的 turretKills） */
        private Integer turretKills;

        /** 插眼数（statsJson 的 wardsPlaced） */
        private Integer wardsPlaced;

        /** 对塔伤害（statsJson 的 damageDealtToTurrets，折叠卡拆塔标签使用） */
        private Integer totalDamageToTowers;

        /** 双杀数（statsJson 的 doubleKills，折叠卡多杀标签使用） */
        private Integer doubleKills;

        /** 三杀数（statsJson 的 tripleKills） */
        private Integer tripleKills;

        /** 四杀数（statsJson 的 quadraKills） */
        private Integer quadraKills;

        /** 五杀数（statsJson 的 pentaKills） */
        private Integer pentaKills;

        /** 对友军总护盾量（statsJson 的 totalDamageShieldedOnTeammates，折叠卡护盾标签使用） */
        private Integer totalDamageShieldedOnTeammates;

        /** 控制他人时长（statsJson 的 timeCCingOthers，折叠卡控制标签使用） */
        private Integer timeCCingOthers;

        /** 单杀数（statsJson 的 challenges.soloKills，折叠卡单杀标签使用；SGP 独有，LCU 为 0） */
        private Integer soloKills;

        /** 敌方塔附近击杀数（challenges.killsNearEnemyTurret，折叠卡塔杀标签使用） */
        private Integer killsNearEnemyTurret;

        /** 己方塔下击杀数（challenges.killsUnderOwnTurret，折叠卡反杀标签使用） */
        private Integer killsUnderOwnTurret;

        /** 对线最大补刀差（challenges.maxCsAdvantageOnLaneOpponent，折叠卡补刀压制标签使用） */
        private Integer maxCsAdvantageOnLaneOpponent;

        /** 击飞击杀数（challenges.knockEnemyIntoTeamAndKill，折叠卡击飞标签使用） */
        private Integer knockEnemyIntoTeamAndKill;

        /** 召唤师账号等级（statsJson 的 summonerLevel，顶部玩家信息展示） */
        private Integer summonerLevel;

        /** 召唤师头像 ID（statsJson 的 profileIcon，顶部玩家头像展示） */
        private Integer profileIcon;
    }

    /**
     * 最近对手聚合项：本页对局中非 self 队玩家按 puuid 归并，
     * 胜负数按各局 win 累加，昵称/英雄取最后一次出现（与前端侧栏"最近对手"口径一致）
     */
    @Data
    public static class RecentOpponent {

        /** 玩家 puuid */
        private String puuid;

        /** 召唤师名（含 #tag，如 "ZZXOOV#qyq"） */
        private String summonerName;

        /** 使用的英雄 ID（取最后一次出现） */
        private Integer championId;

        /** 胜场数 */
        private Integer wins;

        /** 负场数 */
        private Integer losses;
    }
}
