package com.leagueakari.service;

import com.leagueakari.entity.ScoringBaseline;
import com.leagueakari.mapper.ScoringBaselineMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BaselineService 单元测试（Mock Mapper，不依赖数据库）
 */
@ExtendWith(MockitoExtension.class)
class BaselineServiceTest {

    @Mock
    private ScoringBaselineMapper baselineMapper;

    private BaselineService service;

    @BeforeEach
    void setUp() {
        service = new BaselineService(baselineMapper);
    }

    @Test
    @DisplayName("championId 为 null 时跳过更新")
    void nullChampionSkip() {
        service.updateBaseline(null, Map.of("damage", 100.0), 1200);
        verify(baselineMapper, never()).insert(any(ScoringBaseline.class));
        verify(baselineMapper, never()).updateById(any(ScoringBaseline.class));
    }

    @Test
    @DisplayName("首次更新：新建记录 sampleCount=1，各维度累计=传入值")
    void firstUpdateCreatesRecord() {
        when(baselineMapper.selectById(1)).thenReturn(null);
        service.updateBaseline(1, Map.of("damage", 300.0, "kda", 5.0, "gold", 400.0,
                "tank", 500.0, "healShield", 0.0, "cc", 2.0, "turret", 30.0), 1200);

        ArgumentCaptor<ScoringBaseline> captor = ArgumentCaptor.forClass(ScoringBaseline.class);
        verify(baselineMapper).insert(captor.capture());
        ScoringBaseline saved = captor.getValue();
        assertThat(saved.getChampionId()).isEqualTo(1);
        assertThat(saved.getSampleCount()).isEqualTo(1);
        assertThat(saved.getSumDamage()).isEqualTo(300.0);
        assertThat(saved.getSumKda()).isEqualTo(5.0);
        assertThat(saved.getSumGold()).isEqualTo(400.0);
        assertThat(saved.getSumTank()).isEqualTo(500.0);
        assertThat(saved.getSumCc()).isEqualTo(2.0);
        assertThat(saved.getSumTurret()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("已有记录：sampleCount 累加，各维度累计")
    void existingRecordAccumulates() {
        ScoringBaseline existing = new ScoringBaseline();
        existing.setChampionId(1);
        existing.setSampleCount(9);
        existing.setSumDamage(900.0);
        existing.setSumKda(45.0);
        existing.setSumGold(3600.0);
        existing.setSumTank(4500.0);
        existing.setSumHealShield(0.0);
        existing.setSumCc(18.0);
        existing.setSumTurret(270.0);
        when(baselineMapper.selectById(1)).thenReturn(existing);

        service.updateBaseline(1, Map.of("damage", 300.0, "kda", 5.0, "gold", 400.0,
                "tank", 500.0, "healShield", 0.0, "cc", 2.0, "turret", 30.0), 1200);

        ArgumentCaptor<ScoringBaseline> captor = ArgumentCaptor.forClass(ScoringBaseline.class);
        verify(baselineMapper).updateById(captor.capture());
        ScoringBaseline saved = captor.getValue();
        assertThat(saved.getSampleCount()).isEqualTo(10);
        assertThat(saved.getSumDamage()).isEqualTo(1200.0);
        assertThat(saved.getSumKda()).isEqualTo(50.0);
        assertThat(saved.getSumGold()).isEqualTo(4000.0);
    }

    @Test
    @DisplayName("loadBaselineByChampion：返回均值与样本量，无记录返回 null")
    void loadBaselineByChampion() {
        when(baselineMapper.selectById(1)).thenReturn(null);
        assertThat(service.loadBaselineByChampion(1)).isNull();

        ScoringBaseline rec = new ScoringBaseline();
        rec.setChampionId(1);
        rec.setSampleCount(10);
        rec.setSumDamage(3000.0);
        rec.setSumKda(50.0);
        rec.setSumGold(4000.0);
        rec.setSumTank(5000.0);
        rec.setSumHealShield(0.0);
        rec.setSumCc(20.0);
        rec.setSumTurret(300.0);
        when(baselineMapper.selectById(2)).thenReturn(rec);

        Map<String, Double> baseline = service.loadBaselineByChampion(2);
        assertThat(baseline.get("sampleCount")).isEqualTo(10.0);
        assertThat(baseline.get("damage")).isEqualTo(300.0);
        assertThat(baseline.get("kda")).isEqualTo(5.0);
        assertThat(baseline.get("gold")).isEqualTo(400.0);
        assertThat(baseline.get("tank")).isEqualTo(500.0);
        assertThat(baseline.get("cc")).isEqualTo(2.0);
        assertThat(baseline.get("turret")).isEqualTo(30.0);
    }

    @Test
    @DisplayName("loadBaseline：全量加载为 championId → dim map")
    void loadBaseline() {
        ScoringBaseline rec = new ScoringBaseline();
        rec.setChampionId(1);
        rec.setSampleCount(5);
        rec.setSumDamage(1500.0);
        rec.setSumKda(25.0);
        rec.setSumGold(2000.0);
        rec.setSumTank(2500.0);
        rec.setSumHealShield(0.0);
        rec.setSumCc(10.0);
        rec.setSumTurret(150.0);
        when(baselineMapper.selectList(null)).thenReturn(List.of(rec));

        Map<Integer, Map<String, Double>> all = service.loadBaseline();
        assertThat(all).containsKey(1);
        assertThat(all.get(1).get("sampleCount")).isEqualTo(5.0);
        assertThat(all.get(1).get("damage")).isEqualTo(300.0);
    }
}