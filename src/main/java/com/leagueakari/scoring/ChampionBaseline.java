package com.leagueakari.scoring;

import java.util.Map;

/**
 * 英雄评分基线值对象：一名英雄（按职业差异化评分的基线维度）的可信基线。
 * <p>取代基线 Map 里的魔法键 "sampleCount"——样本量是一个有名字的类型字段，
 * 混合比（基线分 vs 局内位次分的置信权重）由它驱动。
 * 维度均值为"每分钟值"，由 BaselineService 从累计值除以样本量得出。</p>
 *
 * @param championId  英雄 ID
 * @param dimMeans    维度名 → 每分钟均值（仅含有样本的维度）
 * @param sampleCount 样本量（对局数；0 = 无有效基线，混合比恒 0）
 */
public record ChampionBaseline(
        Integer championId,
        Map<String, Double> dimMeans,
        int sampleCount) {

    /** 无基线哨兵：样本量 0（调用方视为"该英雄无基线"） */
    public static ChampionBaseline empty(Integer championId) {
        return new ChampionBaseline(championId, Map.of(), 0);
    }

    /** 是否有有效样本（样本量 > 0） */
    public boolean hasSamples() {
        return sampleCount > 0 && dimMeans != null && !dimMeans.isEmpty();
    }

    /** 某维度的每分钟均值；无样本或维度缺失返回 null（调用方按无基线处理） */
    public Double meanOf(String dim) {
        if (!hasSamples()) {
            return null;
        }
        return dimMeans.get(dim);
    }
}
