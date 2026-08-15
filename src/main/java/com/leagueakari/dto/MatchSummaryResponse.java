package com.leagueakari.dto;

import lombok.Data;

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
}
