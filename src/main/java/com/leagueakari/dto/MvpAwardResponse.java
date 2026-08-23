package com.leagueakari.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * MVP/SVP 称号响应体：称号持有者的玩家档案与得分
 */
@Data
public class MvpAwardResponse {

    /** match_participant.id */
    private Long participantId;

    /** 玩家 puuid */
    private String puuid;

    /** 召唤师名 */
    private String summonerName;

    /** 英雄 ID */
    private Integer championId;

    /** 归一化总分（0-100） */
    private BigDecimal score;
}