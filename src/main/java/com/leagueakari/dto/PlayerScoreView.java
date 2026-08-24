package com.leagueakari.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 全员评分视图：查询时实时计算的某玩家评分（OpScore 版本）
 * <p>详情接口 playerScores 字段按 puuid 索引本结构。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerScoreView {

    /** OP Score（0-10，一位小数） */
    private Double opScore;

    /** 文字等级（完美/卓越/优秀/良好/一般/偏低/较差/糟糕） */
    private String grade;

    /**
     * 各维度评分明细：维度名（damage/kda/gold/tank/vision/healShield/cc/turret）
     * → { perMinute: 每分钟值, finalScore: 最终维度分 }
     */
    private Map<String, DimensionScore> dimensions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionScore {

        /** 每分钟值 */
        private Double raw;

        /** 最终维度分（0-100） */
        private Double score;
    }
}