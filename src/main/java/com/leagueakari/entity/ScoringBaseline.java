package com.leagueakari.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 评分基线统计实体（scoring_baseline 表）
 * <p>按英雄累积的每分钟维度均值，用于 OpScore 基线比较。主键为 champion_id。</p>
 */
@Data
@TableName("scoring_baseline")
public class ScoringBaseline {

    /** 英雄 ID（表主键） */
    @TableId(type = IdType.INPUT)
    private Integer championId;

    /** 样本量（该英雄出现的对局数） */
    private Integer sampleCount;

    /** damage 每分钟值累计和 */
    private Double sumDamage;

    /** kda 累计和 */
    private Double sumKda;

    /** gold 每分钟值累计和 */
    private Double sumGold;

    /** tank 每分钟值累计和 */
    private Double sumTank;

    /** healShield 每分钟值累计和 */
    private Double sumHealShield;

    /** cc 每分钟值累计和 */
    private Double sumCc;

    /** turret 每分钟值累计和 */
    private Double sumTurret;

    /** 最后更新时间（由 MySQL ON UPDATE CURRENT_TIMESTAMP 自动维护） */
    // private LocalDateTime updatedAt;
}