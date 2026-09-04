package com.leagueakari.scoring;

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
     * 全量基线缓存快照：写路径 {@link #updateBaseline} 成功后置空失效，
     * 下次读取重新加载（volatile 保证多线程可见性）
     */
    private volatile Map<Integer, ChampionBaseline> baselineCache;

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
        // 基线已变更：置空全量缓存，下次读取重新加载（关键失效点，避免读到旧基线）
        baselineCache = null;
        log.debug("基线更新：championId={}, sampleCount={}", championId, record.getSampleCount());
    }

    /**
     * 加载全量基线数据
     *
     * @return championId → 基线值对象（维度均值 + 类型化样本量）
     */
    public Map<Integer, ChampionBaseline> loadBaseline() {
        return baselineMapper.selectList(null).stream()
                .collect(Collectors.toMap(
                        ScoringBaseline::getChampionId,
                        this::toBaseline,
                        (a, b) -> a));
    }

    /**
     * 全量基线缓存读取：首次查询全表并缓存快照，之后复用；
     * 写路径 {@link #updateBaseline} 会置空缓存，保证读取始终是最新基线。
     * 评分/榜单/成员卡共用此入口，避免每次请求重复全表查询。
     * <p>返回的 Map 为内部缓存快照，调用方必须只读，不得修改。</p>
     *
     * @return championId → 基线值对象（维度均值 + 类型化样本量）
     */
    public Map<Integer, ChampionBaseline> getBaselineMap() {
        Map<Integer, ChampionBaseline> cached = baselineCache;
        if (cached == null) {
            cached = loadBaseline();
            baselineCache = cached;
        }
        return cached;
    }

    /**
     * 加载单个英雄的基线
     *
     * @param championId 英雄 ID
     * @return 基线值对象；无记录时返回 null
     */
    public ChampionBaseline loadBaselineByChampion(Integer championId) {
        if (championId == null) {
            return null;
        }
        ScoringBaseline record = baselineMapper.selectById(championId);
        if (record == null) {
            return null;
        }
        return toBaseline(record);
    }

    /** 实体 → 值对象：样本量类型化，维度均值为累计值 ÷ 样本量 */
    private ChampionBaseline toBaseline(ScoringBaseline b) {
        // 脏数据容错：样本数或累计值缺失时按"无基线"返回（样本量 0），
        // 调用方据此跳过该英雄，避免整表读取时拆箱 NPE
        Integer sampleCount = b.getSampleCount();
        if (sampleCount == null || sampleCount <= 0 || b.getSumDamage() == null) {
            return ChampionBaseline.empty(b.getChampionId());
        }
        int n = sampleCount;
        Map<String, Double> means = new HashMap<>();
        means.put(OpScoreEngine.DIM_DAMAGE, b.getSumDamage() / n);
        means.put(OpScoreEngine.DIM_KDA, b.getSumKda() / n);
        means.put(OpScoreEngine.DIM_GOLD, b.getSumGold() / n);
        means.put(OpScoreEngine.DIM_TANK, b.getSumTank() / n);
        means.put(OpScoreEngine.DIM_HEAL, b.getSumHealShield() / n);
        means.put(OpScoreEngine.DIM_CC, b.getSumCc() / n);
        means.put(OpScoreEngine.DIM_TURRET, b.getSumTurret() / n);
        return new ChampionBaseline(b.getChampionId(), means, n);
    }
}