package com.leagueakari.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对局主表实体，与 V1__init.sql 的 match 表一一对应
 * 注意：match 是 MySQL 保留字，@TableName 中必须带反引号
 */
@Data
@TableName("`match`")
public class Match {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** LCU 对局 ID，幂等键 */
    private Long gameId;

    /** 对局创建时间戳（ms） */
    private Long gameCreation;

    /** 对局时长（秒） */
    private Integer gameDuration;

    /** 模式，如 CLASSIC / CHERRY */
    private String gameMode;

    /** 类型，如 MATCHED_GAME */
    private String gameType;

    /** 队列 ID */
    private Integer queueId;

    /** 地图 ID */
    private Integer mapId;

    /** 游戏版本 */
    private String gameVersion;

    /** 地区，如 na1 */
    private String region;

    /** 区服，腾讯服如 SG2 */
    private String rsoPlatformId;

    /** 数据源：lcu / sgp */
    private String dataSource;

    /** 获胜队伍 ID */
    private Integer winnerTeamId;

    /** 记录本局的玩家 puuid */
    private String selfPuuid;

    /** 队伍级统计全量快照（JSON 字符串） */
    private String teamsJson;

    /** 落库时间 */
    private LocalDateTime createdAt;
}
