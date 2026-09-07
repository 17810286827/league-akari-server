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
}
