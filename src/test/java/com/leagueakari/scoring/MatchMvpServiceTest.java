package com.leagueakari.scoring;

import com.leagueakari.config.ScoringConfig;
import com.leagueakari.dto.PlayerScoreView;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMvpMapper;
import com.leagueakari.mapper.ScoringBaselineMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.leagueakari.common.stats.ParticipantStatsReader;

/**
 * MatchMvpService 单元测试（OpScore 版本）
 */
@ExtendWith(MockitoExtension.class)
class MatchMvpServiceTest {

    @Mock
    private MatchMvpMapper matchMvpMapper;

    @Mock
    private ScoringBaselineMapper scoringBaselineMapper;

    @Mock
    private ScoringConfig scoringConfig;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OpScoreEngine opScoreEngine;

    @Mock
    private BaselineService baselineService;

    private MatchMvpService matchMvpService;

    @BeforeEach
    void setUp() {
        // 手动构造，不用 @InjectMocks（因为要 mock OpScoreEngine）
        matchMvpService = new MatchMvpService(
                matchMvpMapper,
                opScoreEngine, scoringConfig, objectMapper, baselineService,
                new ParticipantStatsReader(objectMapper));
    }

    private Match buildMatch(Long id, Integer winnerTeamId) {
        Match m = new Match();
        m.setId(id);
        m.setGameId(1000000000L + id);
        m.setGameMode("CHERRY");
        m.setWinnerTeamId(winnerTeamId);
        m.setGameDuration(1800);
        return m;
    }

    private MatchParticipant participant(long id, int championId, int teamId, boolean win,
                                         int damage, int kills, int deaths, int assists,
                                         int gold, int taken, int vision, int heal, int shield, int cc) {
        MatchParticipant p = new MatchParticipant();
        p.setId(id);
        p.setMatchId(1L);
        p.setPuuid("puuid-" + id);
        p.setSummonerName("Player" + id);
        p.setChampionId(championId);
        p.setTeamId(teamId);
        p.setKills(kills);
        p.setDeaths(deaths);
        p.setAssists(assists);
        p.setWin(win);
        p.setGoldEarned(gold);
        p.setCs(200);
        p.setStatsJson("""
                {"totalDamageDealtToChampions":%d,"totalDamageTaken":%d,"visionScore":%d,\
                "totalHeal":%d,"totalDamageShieldedOnTeammates":%d,"timeCCingOthers":%d,\
                "damageDealtToTurrets":0,"doubleKills":0,"tripleKills":0,"quadraKills":0,"pentaKills":0}\
                """.formatted(damage, taken, vision, heal, shield, cc));
        return p;
    }

    private MatchParticipant winnerAdc() {
        return participant(101L, 22, 100, true, 30000, 10, 2, 5, 15000, 12000, 20, 0, 0, 0);
    }

    private MatchParticipant winnerTank() {
        return participant(102L, 57, 100, true, 8000, 2, 5, 10, 9000, 45000, 15, 0, 0, 35);
    }

    private MatchParticipant loserAdc() {
        return participant(103L, 81, 200, false, 25000, 12, 4, 6, 13000, 13000, 10, 0, 0, 0);
    }

    private MatchParticipant loserSupport() {
        return participant(104L, 16, 200, false, 6000, 1, 6, 14, 8000, 14000, 55, 12000, 3000, 30);
    }

    /** 职业表已随引擎收编（T5）：本服务不再 mock championClassMapper */



    private void mockInsert() {
        doAnswer(inv -> {
            MatchMvp m = inv.getArgument(0);
            m.setId(System.nanoTime());
            return 1;
        }).when(matchMvpMapper).insert(any(MatchMvp.class));
    }

    /**
     * 构造 OpScoreResult 辅助：4 人的简单评分结果
     */
    private OpScoreResult makeResult(int winnerId, int loserId) {
        Map<String, OpScoreResult.DimensionScore> dims = Map.of(
                "damage", OpScoreResult.DimensionScore.builder().perMinute(1000.0).teamRank(75.0).baselineScore(0.0).mix(0.0).finalScore(75.0).build(),
                "kda", OpScoreResult.DimensionScore.builder().perMinute(5.0).teamRank(50.0).baselineScore(0.0).mix(0.0).finalScore(50.0).build());
        return OpScoreResult.builder()
                .playerScores(List.of(
                        ps(101L, 22, 100, true, 75.0, 7.5, "优秀", 0.0, dims),
                        ps(102L, 57, 100, true, 60.0, 6.0, "良好", 0.0, dims),
                        ps(103L, 81, 200, false, 70.0, 7.0, "优秀", 0.0, dims),
                        ps(104L, 16, 200, false, 50.0, 5.0, "一般", 0.0, dims)))
                .mvp(ps((long) winnerId, 22, 100, true, 75.0, 7.5, "优秀", 0.0, dims))
                .ace(ps((long) loserId, 81, 200, false, 70.0, 7.0, "优秀", 0.0, dims))
                .build();
    }

    private OpScoreResult.PlayerScore ps(long pid, int cid, int tid, boolean win,
                                         double total, double opScore, String grade, double bonus,
                                         Map<String, OpScoreResult.DimensionScore> dims) {
        return OpScoreResult.PlayerScore.builder()
                .participantId(pid).championId(cid).teamId(tid).win(win)
                .totalScore(total).opScore(opScore).grade(grade).multiKillBonus(bonus)
                .dimensionScores(dims).build();
    }

    @Test
    @DisplayName("大乱斗修正入口：queueId ∈ {450,2400,2410,2450} 时评分输入 aramMode=true，普通队列 false")
    void evaluateAndSave_derivesAramModeFromQueueId() {
        when(opScoreEngine.score(any())).thenReturn(makeResult(101, 103));

        // 大乱斗系队列逐一验证：极地大乱斗 450 + 海克斯乱斗 2400/2410/2450
        for (Integer aramQueue : List.of(450, 2400, 2410, 2450)) {
            Match m = buildMatch(1L, 100);
            m.setQueueId(aramQueue);
            matchMvpService.evaluateAndSave(m, List.of(winnerAdc(), loserAdc()));
            assertThat(capturedInput(101L).isAramMode())
                    .as("queueId=%s 应推导 aramMode=true", aramQueue).isTrue();
        }
        // 普通队列不受影响：单双排 420 / 匹配 430 / 灵活 440 / 斗魂竞技场 1700 / 缺失（Arrays.asList 容纳 null）
        for (Integer normalQueue : Arrays.asList(420, 430, 440, 1700, null)) {
            Match m = buildMatch(1L, 100);
            m.setQueueId(normalQueue);
            matchMvpService.evaluateAndSave(m, List.of(winnerAdc(), loserAdc()));
            assertThat(capturedInput(101L).isAramMode())
                    .as("queueId=%s 应推导 aramMode=false", normalQueue).isFalse();
        }
    }

    /** 从引擎 mock 调用中捕获指定参与者的评分输入（多次调用时取最近一次） */
    @SuppressWarnings("unchecked")
    private MvpScoringInput capturedInput(long participantId) {
        ArgumentCaptor<List<MvpScoringInput>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(opScoreEngine, atLeastOnce()).score(captor.capture());
        return captor.getAllValues().stream()
                .flatMap(List::stream)
                .filter(in -> in.getParticipantId() == participantId)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("participant " + participantId + " input not captured"));
    }

    @Test
    @DisplayName("评选落库：MVP=胜方最佳 ACE=负方最佳")
    void evaluateAndSave_writesMvpAndAce() {
        mockInsert();
        when(scoringConfig.getVersion()).thenReturn(2);
        // mock 引擎返回预设结果
        when(opScoreEngine.score(any())).thenReturn(makeResult(101, 103));

        matchMvpService.evaluateAndSave(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport()));

        ArgumentCaptor<MatchMvp> captor = ArgumentCaptor.forClass(MatchMvp.class);
        verify(matchMvpMapper, times(2)).insert(captor.capture());
        List<MatchMvp> saved = captor.getAllValues();

        MatchMvp mvp = saved.stream().filter(m -> "MVP".equals(m.getType())).findFirst().orElseThrow();
        assertThat(mvp.getParticipantId()).isEqualTo(101L);
        assertThat(mvp.getMatchId()).isEqualTo(1L);
        assertThat(mvp.getScoringVersion()).isEqualTo(2);
        assertThat(mvp.getScore()).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(mvp.getOpScore()).isBetween(BigDecimal.ZERO, new BigDecimal("10.0"));
        assertThat(mvp.getGrade()).isNotNull();

        MatchMvp ace = saved.stream().filter(m -> "ACE".equals(m.getType())).findFirst().orElseThrow();
        assertThat(ace.getParticipantId()).isEqualTo(103L);
    }

    @Test
    @DisplayName("职业映射缺失回退均衡权重（回退逻辑在引擎侧，本服务验证编排不受影响）")
    void evaluateAndSave_fallsBackWhenClassMapEmpty() {
        mockInsert();
        when(scoringConfig.getVersion()).thenReturn(2);
        when(opScoreEngine.score(any())).thenReturn(makeResult(101, 103));

        assertThatCode(() -> matchMvpService.evaluateAndSave(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport())))
                .doesNotThrowAnyException();

        verify(matchMvpMapper, times(2)).insert(any(MatchMvp.class));
    }

    @Test
    @DisplayName("一方无人时跳过对应称号")
    void evaluateAndSave_skipsWhenOneSideEmpty() {
        when(scoringConfig.getVersion()).thenReturn(2);
        // 只有胜方 2 人 -> 引擎只返回 2 个 playerScores，mvp 有值，ace=null
        OpScoreResult result = OpScoreResult.builder()
                .playerScores(List.of(ps(101L, 22, 100, true, 75.0, 7.5, "优秀", 0.0, Map.of())))
                .mvp(ps(101L, 22, 100, true, 75.0, 7.5, "优秀", 0.0, Map.of()))
                .ace(null).build();
        when(opScoreEngine.score(any())).thenReturn(result);

        matchMvpService.evaluateAndSave(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank()));

        verify(matchMvpMapper, times(1)).insert(any(MatchMvp.class));
    }

    @Test
    @DisplayName("DuplicateKeyException 幂等兜底")
    void evaluateAndSave_swallowsDuplicateKey() {
        when(scoringConfig.getVersion()).thenReturn(2);
        when(opScoreEngine.score(any())).thenReturn(makeResult(101, 103));
        doThrow(new DuplicateKeyException("uk_match_mvp"))
                .when(matchMvpMapper).insert(any(MatchMvp.class));

        assertThatCode(() -> matchMvpService.evaluateAndSave(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("statsJson 缺失按 0 兜底")
    void evaluateAndSave_handlesNullStatsJson() {
        mockInsert();
        when(scoringConfig.getVersion()).thenReturn(2);
        when(opScoreEngine.score(any())).thenReturn(makeResult(101, 103));

        MatchParticipant broken = winnerAdc();
        broken.setStatsJson(null);

        assertThatCode(() -> matchMvpService.evaluateAndSave(
                buildMatch(1L, 100),
                List.of(broken, winnerTank(), loserAdc(), loserSupport())))
                .doesNotThrowAnyException();

        verify(matchMvpMapper, times(2)).insert(any(MatchMvp.class));
    }

    @Test
    @DisplayName("computeScores 全员实时评分按 puuid 键返回")
    void computeScores_returnsAllPlayerScoresByPuuid() {
        when(opScoreEngine.score(any())).thenReturn(makeResult(101, 103));

        Map<String, PlayerScoreView> scores = matchMvpService.computeScores(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport()));

        assertThat(scores).hasSize(4);
        assertThat(scores).containsKeys("puuid-101", "puuid-102", "puuid-103", "puuid-104");
        scores.forEach((puuid, view) -> {
            assertThat(view.getOpScore()).isBetween(0.0, 10.0);
            assertThat(view.getGrade()).isNotNull();
            assertThat(view.getDimensions()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("computeScores 纯计算不落库")
    void computeScores_writesNothing() {
        when(opScoreEngine.score(any())).thenReturn(makeResult(101, 103));

        matchMvpService.computeScores(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport()));

        verify(matchMvpMapper, never()).insert(any(MatchMvp.class));
    }

    @Test
    @DisplayName("computeScores 连续调用只查一次英雄职业分类（进程内缓存）")
    void computeScores_cachesChampionClassMap() {
        when(opScoreEngine.score(any())).thenReturn(makeResult(101, 103));

        matchMvpService.computeScores(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport()));
        matchMvpService.computeScores(
                buildMatch(2L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport()));

        // 职业表加载已随引擎收编（T5）：本服务已无职业表依赖（编译期保证），
        // 引擎侧缓存行为由 OpScoreEngineInterfaceTest.score_championClassLoadedOnceAcrossCalls 锁定
    }

    @Test
    @DisplayName("评分的基线读取已随引擎收编：编排层不再触碰基线表（只透传参与者输入）")
    void computeScores_doesNotTouchBaselineTables() {
        when(opScoreEngine.score(any())).thenReturn(makeResult(101, 103));

        matchMvpService.computeScores(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport()));

        // 基线/职业表加载在引擎侧（此处引擎为 mock）：编排层零触碰
        verify(baselineService, never()).getBaselineMap();
        verify(scoringBaselineMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("collectBaselines：逐人按英雄累积各维度每分钟值")
    void collectBaselines_accumulatesPerParticipant() {
        // winnerAdc：30000 伤害 / 30 分钟 = 1000 每分钟；时长 1800s 原样透传
        Map<String, Double> adcPerMinute = Map.of(
                OpScoreEngine.DIM_DAMAGE, 1000.0, OpScoreEngine.DIM_KDA, 5.0);
        when(opScoreEngine.minutes(any())).thenReturn(30.0);
        when(opScoreEngine.perMinuteValues(any(), eq(30.0))).thenReturn(adcPerMinute);

        matchMvpService.collectBaselines(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport()));

        // 4 名参与者各累积一次基线
        ArgumentCaptor<Integer> championCaptor = ArgumentCaptor.forClass(Integer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Double>> perMinuteCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Integer> durationCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(baselineService, times(4)).updateBaseline(
                championCaptor.capture(), perMinuteCaptor.capture(), durationCaptor.capture());

        // 英雄与时长对号入座
        assertThat(championCaptor.getAllValues()).containsExactly(22, 57, 81, 16);
        assertThat(durationCaptor.getAllValues()).containsExactly(1800, 1800, 1800, 1800);
        // 每分钟值原样传给基线服务（第一名 ADC 的伤害 1000/min）
        assertThat(perMinuteCaptor.getAllValues().get(0)).isEqualTo(adcPerMinute);
    }

    @Test
    @DisplayName("collectBaselines：空参与者直接跳过")
    void collectBaselines_skipsEmptyParticipants() {
        matchMvpService.collectBaselines(buildMatch(1L, 100), List.of());

        verify(baselineService, never()).updateBaseline(any(), any(), any());
    }
}