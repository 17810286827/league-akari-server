package com.leagueakari.service;

import com.leagueakari.entity.ScoringBaseline;
import com.leagueakari.mapper.ScoringBaselineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评分基线服务：累积各英雄的每分钟维度均值，用于 OpScore 基线比较
 * <p>每同步一局即调用 {@link #updateBaseline} 累加数据；查询时通过
 * {@link #loadBaseline} 返回全量基线映射。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaselineService {

    private final ScoringBaselineMapper baselineMapper;

    /**
     * 更新一名参与者的基线数据（INSERT OR UPDATE 累加）
     *
     * @param championId  英雄 ID
     * @param perMinute   各维度每分钟值（维度名 → 每分钟值）
     * @param gameDurationSeconds 游戏时长秒
     */
    public void updateBaseline(Integer championId, Map<String, Double> perMinute, Integer gameDurationSeconds) {
        if (championId == null || perMinute == null || perMinute.isEmpty()) {
            return;
        }
        // 从数据库查找已有记录
        ScoringBaseline record = baselineMapper.selectById(championId);
        if (record == null) {
            record = new ScoringBaseline();
            record.setChampionId(championId);
            record.setSampleCount(0);
            record.setSumDamage(0.0);
            record.setSumKda(0.0);
            record.setSumGold(0.0);
            record.setSumTank(0.0);
            record.setSumHealShield(0.0);
            record.setSumCc(0.0);
            record.setSumTurret(0.0);
        }
        // 累加
        record.setSampleCount(record.getSampleCount() + 1);
        record.setSumDamage(record.getSumDamage() + perMinute.getOrDefault(OpScoreEngine.DIM_DAMAGE, 0.0));
        record.setSumKda(record.getSumKda() + perMinute.getOrDefault(OpScoreEngine.DIM_KDA, 0.0));
        record.setSumGold(record.getSumGold() + perMinute.getOrDefault(OpScoreEngine.DIM_GOLD, 0.0));
        record.setSumTank(record.getSumTank() + perMinute.getOrDefault(OpScoreEngine.DIM_TANK, 0.0));
        record.setSumHealShield(record.getSumHealShield() + perMinute.getOrDefault(OpScoreEngine.DIM_HEAL, 0.0));
        record.setSumCc(record.getSumCc() + perMinute.getOrDefault(OpScoreEngine.DIM_CC, 0.0));
        record.setSumTurret(record.getSumTurret() + perMinute.getOrDefault(OpScoreEngine.DIM_TURRET, 0.0));

        if (record.getSampleCount() == 1) {
            // 第一次更新，记录是新建的
            baselineMapper.insert(record);
        } else {
            baselineMapper.updateById(record);
        }
        log.debug("基线更新：championId={}, sampleCount={}", championId, record.getSampleCount());
    }

    /**
     * 加载全量基线数据
     *
     * @return championId → { dim → 均值, "sampleCount" → 样本量 }
     */
    public Map<Integer, Map<String, Double>> loadBaseline() {
        return baselineMapper.selectList(null).stream()
                .collect(Collectors.toMap(
                        ScoringBaseline::getChampionId,
                        this::toDimMap,
                        (a, b) -> a));
    }

    /**
     * 加载单个英雄的基线
     *
     * @param championId 英雄 ID
     * @return { dim → 均值, "sampleCount" → 样本量 }，无基线时返回 null
     */
    public Map<String, Double> loadBaselineByChampion(Integer championId) {
        if (championId == null) {
            return null;
        }
        ScoringBaseline record = baselineMapper.selectById(championId);
        if (record == null) {
            return null;
        }
        return toDimMap(record);
    }

    private Map<String, Double> toDimMap(ScoringBaseline b) {
        Map<String, Double> m = new HashMap<>();
        int n = b.getSampleCount();
        if (n <= 0) {
            return Map.of("sampleCount", 0.0);
        }
        m.put("sampleCount", (double) n);
        m.put(OpScoreEngine.DIM_DAMAGE, b.getSumDamage() / n);
        m.put(OpScoreEngine.DIM_KDA, b.getSumKda() / n);
        m.put(OpScoreEngine.DIM_GOLD, b.getSumGold() / n);
        m.put(OpScoreEngine.DIM_TANK, b.getSumTank() / n);
        m.put(OpScoreEngine.DIM_HEAL, b.getSumHealShield() / n);
        m.put(OpScoreEngine.DIM_CC, b.getSumCc() / n);
        m.put(OpScoreEngine.DIM_TURRET, b.getSumTurret() / n);
        return m;
    }
}