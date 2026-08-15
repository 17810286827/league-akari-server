package com.leagueakari.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 参赛者明细；stats 为原始统计对象全量透传，整体存入 stats_json
 */
@Data
public class ParticipantSyncRequest {

    private String puuid;
    private String summonerName;
    private Integer championId;
    private Integer teamId;
    private String position;
    private Integer kills;
    private Integer deaths;
    private Integer assists;
    private Boolean win;
    private Integer goldEarned;
    private Integer cs;
    private List<Integer> items;
    private List<Integer> summonerSpells;

    /** 原始 stats 全量对象（LCU/SGP 字段名一致），不允许为空 */
    private Map<String, Object> stats;
}
