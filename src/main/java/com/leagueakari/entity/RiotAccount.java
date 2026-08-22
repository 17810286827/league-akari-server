package com.leagueakari.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Riot 账号缓存实体：按名搜索结果的持久化（对应 riot_account 表）。
 * puuid 终身不变作为一人一行的幂等键；玩家改名后按 puuid 更新 game_name/tag_line
 */
@Data
@TableName("riot_account")
public class RiotAccount {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 拳头账号唯一标识（终身不变） */
    private String puuid;

    /** 昵称（# 前部分，改名后会被更新） */
    private String gameName;

    /** 尾号（# 后部分，如 iKun） */
    private String tagLine;

    /** 召唤师等级快照（Summoner-V4，低频变化允许略旧） */
    private Integer summonerLevel;

    /** 召唤师头像 ID 快照（用于拼头像 URL） */
    private Integer profileIconId;

    /** 首次入库时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
