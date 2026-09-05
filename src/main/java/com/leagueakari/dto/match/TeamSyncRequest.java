package com.leagueakari.dto.match;

import lombok.Data;

/**
 * 队伍级统计，原样存入 teams_json
 */
@Data
public class TeamSyncRequest {

    /** 队伍 ID（100 蓝方 / 200 红方） */
    private Integer teamId;

    /** 该队是否获胜 */
    private Boolean win;

    private Integer towerKills;
    private Integer inhibitorKills;
    private Integer baronKills;
    private Integer dragonKills;
    private Integer riftHeraldKills;
    private Boolean firstBlood;
    private Boolean firstTower;
}
