package com.leagueakari.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 评分引擎输出：全队评分结果，包含 MVP 与 SVP 信息
 */
@Data
@Builder
public class MvpScoringResult {

    /** 所有参与者的评分详情（按输入顺序） */
    private List<PlayerScore> playerScores;

    /** MVP：胜方得分最高者 */
    private PlayerScore mvp;

    /** SVP：负方得分最高者 */
    private PlayerScore svp;

    /**
     * 单个参与者的评分结果
     */
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

        /** 归一化总分（0-100） */
        private Double totalScore;

        /**
         * 各维度评分明细
         * key: 维度名（damage/kda/gold/tank/vision/support/cc/cs）
         * value: { raw: 原始值, score: 归一化得分 }
         */
        private Map<String, DimensionScore> dimensionScores;
    }

    /**
     * 单个维度的评分明细
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionScore {

        /** 原始值（与职业无关的原始统计值） */
        private Double raw;

        /** 同队归一化得分（0-100） */
        private Double score;
    }
}