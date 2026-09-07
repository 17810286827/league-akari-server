package com.leagueakari.dto.intel;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 游戏开始信号入参：桌面端 Gameflow 进入 InProgress 时 POST，
 * 触发敌方情报推送编排（锚定我方 → 算开黑/胜率 → 渲染 → 发群）。
 */
@Data
public class GameStartSignalRequest {

    /** 对局标识（游戏开始后的 gameId，去重幂等键） */
    @NotNull(message = "gameId 不能为空")
    private Long gameId;

    /** 蓝色方（teamId 100）玩家 puuid 列表 */
    private List<String> bluePuids;

    /** 红色方（teamId 200）玩家 puuid 列表 */
    private List<String> redPuids;

    /** 上报开始信号的桌面端玩家 puuid（参考信号，我方锚定以 roster 多数派为准） */
    private String reporterPuuid;

    /** 本局队列 ID（情报卡标题展示用） */
    private Integer queueId;
}
