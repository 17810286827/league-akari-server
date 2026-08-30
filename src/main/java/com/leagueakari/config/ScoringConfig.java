package com.leagueakari.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 评分系统配置：权重表、多杀加分、基线阈值等
 * <p>从 application.yml 的 scoring.* 前缀加载，启动时绑定。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "scoring")
public class ScoringConfig {

    /** 评分算法版本号，用于落库标记（v3：MVP/ACE 评选改按 op_score 取，含多杀加分） */
    private int version = 3;

    /**
     * 职业差异化权重表
     * key: 职业名 (ADC/MAGE/TANK/ASSASSIN/FIGHTER/SUPPORT/UNKNOWN)
     * value: 维度名 → 权重（每行和为 1.0）
     */
    private Map<String, Map<String, Double>> weights = new HashMap<>();

    /** 多杀加分：double/triple/quadra/penta → 加分值 */
    private Map<String, Double> multiKillBonus = new HashMap<>();

    /** 基线样本量下限：低于此值纯局内比较 */
    private int baselineThresholdMin = 10;

    /** 基线样本量上限：达到此值后锁定混合比 */
    private int baselineThresholdMax = 30;

    /** 最大基线混合比（达到 thresholdMax 后固定为此值） */
    private double baselineMixMax = 0.5;

    /**
     * 按职业的冷启动基线默认值（每分钟期望值）
     * key: 职业名, value: 维度名 → 默认每分钟值
     */
    private Map<String, Map<String, Double>> baselineDefaults = new HashMap<>();
}