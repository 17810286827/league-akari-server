package com.leagueakari.dto.diagnosis;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对局诊断响应（工单 #36 / spec #30）：
 * 用 stats_json 中未被评分体系使用的字段回答"这局短板在哪"——
 * 每维度原始值 + 队内位次 + 短板标记（败局末位高亮）。
 * 纯数据不依赖 AI；与 OP Score 评分体系完全隔离（评分版本冻结在 v4）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisResponse {

    /** 视角队伍是否获胜（败局才做短板高亮） */
    private boolean win;

    /** 视角队伍 ID */
    private Integer perspectiveTeamId;

    /** 全员诊断（按上报顺序；前端按队伍过滤展示） */
    private List<PlayerDiagnosis> players;

    /** 单名玩家的诊断（维度列表） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlayerDiagnosis {

        /** 召唤师名 */
        private String name;

        /** 英雄中文名 */
        private String championName;

        /** 英雄职业（SUPPORT 等六职业，决定维度豁免） */
        private String championClass;

        /** 队伍 ID */
        private Integer teamId;

        /** 诊断维度列表（键见 DiagnosisService 常量） */
        private List<Dimension> dimensions;
    }

    /**
     * 单个诊断维度：原始值 + 队内位次 + 短板标记。
     * 维度键（key）：vision（视野得分）/ ccTime（控制时长）/ damageConversion（伤害转化）/
     * objectiveDamage（资源伤害）/ goldShare（团队经济占比）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Dimension {

        /** 维度键（引擎常量，前端据此取文案） */
        private String key;

        /** 维度中文名 */
        private String label;

        /** 原始值（含义随维度：视野分/秒/比值/伤害/占比） */
        private double rawValue;

        /** 队内位次（1 = 本队最高） */
        private int teamRank;

        /** 队内均值（对比参考） */
        private double teamAverage;

        /** 是否短板：败局 + 队内末位 + 显著低于队均（阈值见 service）；职业豁免维度恒 false */
        private boolean weak;
    }
}
