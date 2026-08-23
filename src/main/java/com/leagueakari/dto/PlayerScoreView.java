package com.leagueakari.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 全员评分视图：查询时实时计算的某玩家评分（不含称号，纯分数）
 * <p>详情接口 playerScores 字段按 puuid 索引本结构；口径与 match_mvp 落库的
 * MVP/SVP 评分完全一致（同一引擎同一权重）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerScoreView {

    /** 归一化总分（0-100） */
    private Double score;

    /**
     * 各维度评分明细：维度名（damage/kda/gold/tank/vision/support/cc）
     * → { raw: 原始值, score: 同队归一化得分 }
     */
    private Map<String, DimensionScore> dimensions;

    /**
     * 单维度评分明细
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