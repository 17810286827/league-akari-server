package com.leagueakari.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * OpScore 评分引擎输出：全队评分结果，含 OP Score 和文字等级
 */
@Data
@Builder
public class OpScoreResult {

    /** 所有参与者的评分详情（按输入顺序） */
    private List<PlayerScore> playerScores;

    /** MVP：胜方得分最高者 */
    private PlayerScore mvp;

    /** ACE：败方得分最高者 */
    private PlayerScore ace;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlayerScore {

        /** match_participant.id */
        private Long participantId;

        /** 英雄 ID */
        private Integer championId;

        /** 队伍 ID */
        private Integer teamId;

        /** 是否获胜 */
        private Boolean win;

        /** OP Score（0-10，一位小数） */
        private Double opScore;

        /** 文字等级（完美/卓越/...） */
        private String grade;

        /** 原始加权总分（0-100，除以 10 即为 opScore 的基础分） */
        private Double totalScore;

        /** 各维度评分明细 */
        private Map<String, DimensionScore> dimensionScores;

        /** 多杀加分 */
        private Double multiKillBonus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionScore {

        /** 每分钟值 */
        private Double perMinute;

        /** 队内位次分（0-100） */
        private Double teamRank;

        /** 基线分（0-100，截断） */
        private Double baselineScore;

        /** 混合比（0-1，0=纯局内） */
        private Double mix;

        /** 最终维度分（0-100） */
        private Double finalScore;
    }
}