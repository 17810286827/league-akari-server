package com.leagueakari.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MVP/ACE 评选结果实体（match_mvp 表）
 * <p>对局同步时由评分引擎计算，每场对局最多两条（MVP + ACE）。</p>
 */
@Data
public class MatchMvp {

    /** 主键 */
    private Long id;

    /** 所属对局（match.id） */
    private Long matchId;

    /** 获得称号的参与者（match_participant.id） */
    private Long participantId;

    /** 称号类型：MVP / ACE */
    private String type;

    /** 评分算法版本号（1=旧队内归一化，2=OpScore） */
    private Integer scoringVersion;

    /** 归一化总分（0-100，兼容旧算法） */
    private BigDecimal score;

    /** OP Score（0-10，一位小数） */
    private BigDecimal opScore;

    /** 文字等级（完美/卓越/优秀/良好/一般/偏低/较差/糟糕） */
    private String grade;

    /** 评分明细 JSON：{ "维度名": { "perMinute": ..., "teamRank": ..., "baselineScore": ..., "mix": ..., "finalScore": ... } } */
    private String scoreDetailJson;

    /** 记录创建时间 */
    private LocalDateTime createdAt;
}