package com.leagueakari.dto;

import lombok.Data;

/**
 * 队伍级统计，原样存入 teams_json
 */
@Data
public class TeamSyncRequest {

    private Integer teamId;
    private Boolean win;
    private Integer towerKills;
    private Integer inhibitorKills;
    private Integer baronKills;
    private Integer dragonKills;
    private Integer riftHeraldKills;
    private Boolean firstBlood;
    private Boolean firstTower;
}
