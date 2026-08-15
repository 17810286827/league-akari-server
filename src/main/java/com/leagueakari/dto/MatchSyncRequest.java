package com.leagueakari.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 对局同步入参，字段与规格第 6 节契约对齐
 */
@Data
public class MatchSyncRequest {

    @NotNull(message = "gameId 不能为空")
    private Long gameId;

    /** 对局创建时间戳（ms） */
    @NotNull
    private Long gameCreation;

    /** 对局时长（秒） */
    @NotNull
    private Integer gameDuration;

    /** 模式，如 CLASSIC / CHERRY */
    private String gameMode;

    /** 类型，如 MATCHED_GAME */
    private String gameType;

    /** 队列 ID，如 420（排位） */
    private Integer queueId;

    /** 地图 ID，如 11（召唤师峡谷） */
    private Integer mapId;

    private String gameVersion;
    private String region;
    private String rsoPlatformId;
    private String dataSource;
    private Integer winnerTeamId;
    private String selfPuuid;

    /** 队伍级统计数组，整体存入 match.teams_json */
    private List<TeamSyncRequest> teams;

    /** 参赛者明细，至少 1 人 */
    @NotEmpty(message = "participants 不能为空")
    @Valid
    private List<ParticipantSyncRequest> participants;
}
