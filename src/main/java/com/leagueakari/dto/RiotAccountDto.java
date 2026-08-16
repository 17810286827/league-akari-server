package com.leagueakari.dto;

import lombok.Data;

/**
 * Riot 召唤师信息：搜索接口的响应体
 * 账号信息来自 Account-V1（by-riot-id），等级/头像 ID 来自 Summoner-V4（by-puuid）
 */
@Data
public class RiotAccountDto {

    /** 拳头账号唯一标识（用于按玩家过滤对局） */
    private String puuid;

    /** 昵称（# 之前部分） */
    private String gameName;

    /** 尾号（# 之后部分，如 iKun） */
    private String tagLine;

    /** 召唤师等级（Summoner-V4 的 summonerLevel；查询失败时为 null） */
    private Integer summonerLevel;

    /** 召唤师头像 ID（Summoner-V4 的 profileIconId，用于拼头像 URL；失败时为 null） */
    private Integer profileIconId;
}
