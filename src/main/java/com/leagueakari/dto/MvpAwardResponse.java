package com.leagueakari.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * MVP/ACE 称号响应体：称号持有者的玩家档案与得分
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

    /** 归一化总分（0-100，旧算法兼容，新算法保留） */
    private BigDecimal score;

    /** OP Score（0-10，一位小数） */
    private BigDecimal opScore;

    /** 文字等级（完美/卓越/优秀/良好/一般/偏低/较差/糟糕） */
    private String grade;
}