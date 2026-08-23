package com.leagueakari.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 英雄职业分类实体（champion_class 表）
 * <p>MVP/SVP 评分时按英雄职业差异化权重，数据由 Flyway 从 Data Dragon 初始化。</p>
 */
@Data
public class ChampionClass {

    /** 主键 */
    private Long id;

    /** 英雄 ID（幂等键） */
    private Integer championId;

    /** 英雄职业：ADC/MAGE/TANK/ASSASSIN/FIGHTER/SUPPORT */
    private String className;

    /** 记录创建时间 */
    private LocalDateTime createdAt;
}