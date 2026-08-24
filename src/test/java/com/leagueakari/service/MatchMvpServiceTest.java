package com.leagueakari.service;

import com.leagueakari.config.ScoringConfig;
import com.leagueakari.dto.OpScoreResult;
import com.leagueakari.dto.PlayerScoreView;
import com.leagueakari.entity.ChampionClass;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.ChampionClassMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MatchMvpService 单元测试（OpScore 版本）
 */
@ExtendWith(MockitoExtension.class)
class MatchMvpServiceTest {

    @Mock
    private MatchMvpMapper matchMvpMapper;

    @Mock
    private ChampionClassMapper championClassMapper;

    @Mock
    private ScoringBaselineMapper scoringBaselineMapper;

    @Mock
    private ScoringConfig scoringConfig;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OpScoreEngine opScoreEngine;

    private MatchMvpService matchMvpService;

    @BeforeEach
    void setUp() {
        // 手动构造，不用 @InjectMocks（因为要 mock OpScoreEngine）
        matchMvpService = new MatchMvpService(
                matchMvpMapper, championClassMapper, scoringBaselineMapper,
                opScoreEngine, scoringConfig, objectMapper);
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

    private void mockClassMap() {
        when(championClassMapper.selectList(any())).thenReturn(List.of(
                cc(22, "ADC"), cc(57, "TANK"), cc(81, "ADC"), cc(16, "SUPPORT")));
    }

    private void mockEmptyBaseline() {
        when(scoringBaselineMapper.selectList(any())).thenReturn(List.of());
    }

    private ChampionClass cc(int championId, String className) {
        ChampionClass c = new ChampionClass();
        c.setChampionId(championId);
        c.setClassName(className);
        return c;
    }

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
    @DisplayName("评选落库：MVP=胜方最佳 ACE=负方最佳")
    void evaluateAndSave_writesMvpAndAce() {
        mockClassMap();
        mockEmptyBaseline();
        mockInsert();
        when(scoringConfig.getVersion()).thenReturn(2);
        // mock 引擎返回预设结果
        when(opScoreEngine.score(any(), any(), any())).thenReturn(makeResult(101, 103));

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
    @DisplayName("职业映射缺失回退均衡权重")
    void evaluateAndSave_fallsBackWhenClassMapEmpty() {
        when(championClassMapper.selectList(any())).thenReturn(List.of());
        mockEmptyBaseline();
        mockInsert();
        when(scoringConfig.getVersion()).thenReturn(2);
        when(opScoreEngine.score(any(), any(), any())).thenReturn(makeResult(101, 103));

        assertThatCode(() -> matchMvpService.evaluateAndSave(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport())))
                .doesNotThrowAnyException();

        verify(matchMvpMapper, times(2)).insert(any(MatchMvp.class));
    }

    @Test
    @DisplayName("一方无人时跳过对应称号")
    void evaluateAndSave_skipsWhenOneSideEmpty() {
        mockClassMap();
        mockEmptyBaseline();
        when(scoringConfig.getVersion()).thenReturn(2);
        // 只有胜方 2 人 -> 引擎只返回 2 个 playerScores，mvp 有值，ace=null
        OpScoreResult result = OpScoreResult.builder()
                .playerScores(List.of(ps(101L, 22, 100, true, 75.0, 7.5, "优秀", 0.0, Map.of())))
                .mvp(ps(101L, 22, 100, true, 75.0, 7.5, "优秀", 0.0, Map.of()))
                .ace(null).build();
        when(opScoreEngine.score(any(), any(), any())).thenReturn(result);

        matchMvpService.evaluateAndSave(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank()));

        verify(matchMvpMapper, times(1)).insert(any(MatchMvp.class));
    }

    @Test
    @DisplayName("DuplicateKeyException 幂等兜底")
    void evaluateAndSave_swallowsDuplicateKey() {
        mockClassMap();
        mockEmptyBaseline();
        when(scoringConfig.getVersion()).thenReturn(2);
        when(opScoreEngine.score(any(), any(), any())).thenReturn(makeResult(101, 103));
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
        mockClassMap();
        mockEmptyBaseline();
        mockInsert();
        when(scoringConfig.getVersion()).thenReturn(2);
        when(opScoreEngine.score(any(), any(), any())).thenReturn(makeResult(101, 103));

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
        mockClassMap();
        mockEmptyBaseline();
        when(opScoreEngine.score(any(), any(), any())).thenReturn(makeResult(101, 103));

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
        mockClassMap();
        mockEmptyBaseline();
        when(opScoreEngine.score(any(), any(), any())).thenReturn(makeResult(101, 103));

        matchMvpService.computeScores(
                buildMatch(1L, 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport()));

        verify(matchMvpMapper, never()).insert(any(MatchMvp.class));
    }
}