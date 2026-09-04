package com.leagueakari.service;

import com.leagueakari.config.ScoringConfig;
import com.leagueakari.dto.MvpScoringInput;
import com.leagueakari.dto.OpScoreResult;
import com.leagueakari.entity.ChampionClass;
import com.leagueakari.mapper.ChampionClassMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * OpScoreEngine 接口收拢后的调用方契约测试（架构清理 T5）：
 * 红测锚点——"调用方只传参与者输入即可完成评分"，职业表/基线表由引擎侧自加载。
 * 引擎纯函数行为（权重/归一化/基线混合/大乱斗修正）仍由 OpScoreEngineTest 锁定，
 * 本测试只验证接口形态与表加载归属的转移。
 */
@ExtendWith(MockitoExtension.class)
class OpScoreEngineInterfaceTest {

    @Mock
    private ChampionClassMapper championClassMapper;

    @Mock
    private BaselineService baselineService;

    private OpScoreEngine engine;

    @BeforeEach
    void setUp() {
        ScoringConfig config = new ScoringConfig();
        // 最小权重表：UNKNOWN 均衡（kda+damage 有权重即可验证评分可完成）
        config.setWeights(Map.of("UNKNOWN",
                Map.of("kda", 0.5, "damage", 0.5, "gold", 0.0, "tank", 0.0, "vision", 0.0,
                        "healShield", 0.0, "cc", 0.0, "turret", 0.0)));
        config.setMultiKillBonus(Map.of("double", 0.2, "triple", 0.5, "quadra", 1.0, "penta", 2.0));
        engine = new OpScoreEngine(config, championClassMapper, baselineService);
    }

    private MvpScoringInput player(long id, int team, boolean win, int championId) {
        return MvpScoringInput.builder()
                .participantId(id).teamId(team).win(win).championId(championId)
                .gameDurationSeconds(1200)
                .build();
    }

    @Test
    @DisplayName("单参数评分：只传参与者输入即可完成（职业表/基线表由引擎自加载）")
    void score_singleParam_loadsTablesInsideEngine() {
        // 引擎侧自加载：职业表（懒加载缓存）与基线表（BaselineService 缓存）
        when(championClassMapper.selectList(any())).thenReturn(List.of(
                championClass(1, "UNKNOWN"), championClass(2, "UNKNOWN")));
        when(baselineService.getBaselineMap()).thenReturn(Map.of(
                1, new ChampionBaseline(1,
                        Map.of(OpScoreEngine.DIM_DAMAGE, 1000.0, OpScoreEngine.DIM_KDA, 5.0), 30)));

        // 调用方只传参与者输入——不再需要准备 championClassMap / baseline 两张表
        OpScoreResult result = engine.score(List.of(
                player(1, 100, true, 1), player(2, 100, true, 2)));

        assertThat(result).isNotNull();
        assertThat(result.getPlayerScores()).hasSize(2);
        assertThat(result.getMvp()).isNotNull();
    }

    @Test
    @DisplayName("引擎内职业表懒加载缓存：连续两次评分只查一次库")
    void score_championClassLoadedOnceAcrossCalls() {
        when(championClassMapper.selectList(any())).thenReturn(List.of(
                championClass(1, "UNKNOWN"), championClass(2, "UNKNOWN")));
        when(baselineService.getBaselineMap()).thenReturn(Map.of());

        engine.score(List.of(player(1, 100, true, 1), player(2, 100, true, 2)));
        engine.score(List.of(player(1, 100, true, 1), player(2, 100, true, 2)));

        // 职业表缓存随引擎：第二次评分命中进程内缓存（表无写入口，缓存不过期）
        org.mockito.Mockito.verify(championClassMapper, org.mockito.Mockito.times(1)).selectList(any());
    }

    @Test
    @DisplayName("基线带类型样本量：ChampionBaseline.sampleCount 驱动混合比（魔法键废弃）")
    void score_baselineTypedSampleCount() {
        when(championClassMapper.selectList(any())).thenReturn(List.of(championClass(1, "UNKNOWN")));
        // 样本量 30（≥ thresholdMax）→ 混合比锁定 baselineMixMax（默认 0.5）
        when(baselineService.getBaselineMap()).thenReturn(Map.of(
                1, new ChampionBaseline(1,
                        Map.of(OpScoreEngine.DIM_DAMAGE, 1000.0, OpScoreEngine.DIM_KDA, 5.0), 30)));

        OpScoreResult result = engine.score(List.of(
                player(1, 100, true, 1), player(2, 100, true, 1)));

        // 两名同英雄玩家全零数据：damage 基线分 0（0/1000），位次分 50（全员同值），
        // 混合比 0.5 → 最终 damage 维度分 = 50*0.5 + 0*0.5 = 25
        var score = result.getPlayerScores().get(0);
        assertThat(score.getDimensionScores().get("damage").getMix()).isEqualTo(0.5);
        assertThat(score.getDimensionScores().get("damage").getFinalScore()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("少于 2 人仍抛 IllegalArgumentException（入口校验保留）")
    void score_fewerThan2Throws() {
        assertThatThrownBy(() -> engine.score(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ChampionClass championClass(int championId, String className) {
        ChampionClass c = new ChampionClass();
        c.setChampionId(championId);
        c.setClassName(className);
        return c;
    }
}
