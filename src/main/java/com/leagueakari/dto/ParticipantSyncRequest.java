package com.leagueakari.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 参赛者明细；stats 为原始统计对象全量透传，整体存入 stats_json
 */
@Data
public class ParticipantSyncRequest {

    /** 玩家唯一标识（Riot puuid） */
    private String puuid;

    /** 召唤师名 */
    private String summonerName;

    private Integer championId;
    private Integer teamId;
    private String position;

    /** 直显统计：击杀 / 死亡 / 助攻，服务端缺失时写 0 */
    private Integer kills;
    private Integer deaths;
    private Integer assists;

    /** 是否获胜（用于胜率统计） */
    private Boolean win;
    private Integer goldEarned;
    private Integer cs;
    private List<Integer> items;
    private List<Integer> summonerSpells;

    /** 原始 stats 全量对象（LCU/SGP 字段名一致），不允许为空 */
    private Map<String, Object> stats;
}
