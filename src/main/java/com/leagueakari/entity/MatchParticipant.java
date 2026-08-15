package com.leagueakari.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 参赛者明细实体，与 V1__init.sql 的 match_participant 表一一对应
 */
@Data
@TableName("match_participant")
public class MatchParticipant {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属对局（match.id） */
    private Long matchId;

    /** 玩家 puuid */
    private String puuid;

    /** 召唤师名 */
    private String summonerName;

    /** 英雄 ID */
    private Integer championId;

    /** 队伍 ID */
    private Integer teamId;

    /** 分路 */
    private String position;

    /** 击杀（直显用） */
    private Integer kills;

    /** 死亡（直显用） */
    private Integer deaths;

    /** 助攻（直显用） */
    private Integer assists;

    /** 是否获胜 */
    private Boolean win;

    /** 获得金币（直显用） */
    private Integer goldEarned;

    /** 补刀数（直显用） */
    private Integer cs;

    /** 出装（JSON 字符串，直显用） */
    private String items;

    /** 召唤师技能（JSON 字符串，直显用） */
    private String summonerSpells;

    /** stats 全量快照（JSON 字符串） */
    private String statsJson;
}
