package com.leagueakari.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敌方情报推送去重与状态实体（对应 intel_game_start 表）：
 * 游戏开始信号以 gameId 为幂等键，保证同一局只推一次情报卡。
 * 与局后播报（match.push_status）分属两条链路，互不依赖。
 */
@Data
@TableName("intel_game_start")
public class IntelGameStart {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对局标识（游戏开始后的 gameId，去重幂等键） */
    private Long gameId;

    /** 蓝色方（teamId 100）玩家 puuid 数组 JSON */
    private String bluePuids;

    /** 红色方（teamId 200）玩家 puuid 数组 JSON */
    private String redPuids;

    /** 上报开始信号的桌面端玩家 puuid（参考信号，非锚定依据） */
    private String reporterPuuid;

    /** 我方所在队伍（roster 多数派判定结果：100 蓝 / 200 红；未命中为 null） */
    private Integer friendlyTeamId;

    /** 情报卡推送状态：PENDING 待推 / SENT 已送达 / FAILED 失败 */
    private String pushStatus;

    /** 最近一次失败原因 */
    private String pushError;

    /** 首次收到信号时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
