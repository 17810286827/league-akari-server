package com.leagueakari.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MVP/SVP 评选结果实体（match_mvp 表）
 * <p>对局同步时由评分引擎计算，每场对局最多两条（MVP + SVP）。</p>
 */
@Data
public class MatchMvp {

    /** 主键 */
    private Long id;

    /** 所属对局（match.id） */
    private Long matchId;

    /** 获得称号的参与者（match_participant.id） */
    private Long participantId;

    /** 称号类型：MVP / SVP */
    private String type;

    /** 归一化总分（0-100） */
    private BigDecimal score;

    /** 评分明细 JSON：{ "维度名": { "raw": 原始值, "score": 归一化得分 } } */
    private String scoreDetailJson;

    /** 记录创建时间 */
    private LocalDateTime createdAt;
}