package com.leagueakari.intel;

import lombok.Value;

import java.util.List;

/**
 * 敌方情报（聚合后的发卡数据）：情报卡渲染的输入，纯值对象。
 * <p>由 T3 编排从侦察缓存聚合产出（我方红蓝方 + 敌方玩家行 + 开黑分组），
 * T4 渲染器只依赖本结构画图；与数据库/实体解耦。</p>
 */
@Value
public class EnemyIntel {

    /** 车队名（情报卡标题） */
    String teamName;

    /** 我方队伍：100=蓝色方 / 200=红色方 */
    Integer friendlyTeamId;

    /** 本局队列名（情报卡副标题，如"单双排位"；无数据为 null） */
    String queueName;

    /** 敌方玩家情报行（5 人） */
    List<EnemyPlayer> enemies;

    /** 敌方开黑分组（每组 ≥2 人，按组内玩家 puuid 引用） */
    List<PremadeDetector.PremadeGroup> premadeGroups;

    /** 开黑分组的搭档胜率（与 premadeGroups 顺序对应，size 相同） */
    List<PremadeWinRate> premadeWinRates;

    /**
     * 敌方单个玩家的情报行
     *
     * @param puuid        玩家唯一标识
     * @param summonerName 召唤师名（缺失为 null，渲染"未知"）
     * @param tier         大段位（缺失为 null，渲染"未知"）
     * @param rank         小段
     * @param winRate      窗口胜率（窗口内无对局为 null，渲染"无数据"）
     */
    @Value
    public static class EnemyPlayer {
        String puuid;
        String summonerName;
        String tier;
        String rank;
        WindowWinRate winRate;
    }

    /**
     * 一个开黑分组的搭档胜率（该组共现对局中的胜率）
     *
     * @param wins    共现对局中的胜场数
     * @param games   共现对局总局数（= PremadeGroup.times）
     * @param winRate 搭档胜率（wins/games，0~1；games=0 时为 null 表示无数据）
     */
    @Value
    public static class PremadeWinRate {
        int wins;
        int games;
        Double winRate;

        public PremadeWinRate(int wins, int games) {
            this.wins = wins;
            this.games = games;
            this.winRate = games == 0 ? null : (double) wins / games;
        }
    }
}
