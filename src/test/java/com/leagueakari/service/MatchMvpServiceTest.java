package com.leagueakari.service;

import com.leagueakari.entity.ChampionClass;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.ChampionClassMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MatchMvpService 单元测试
 * <p>验证核心契约：
 * 1. 评选后写入 MVP + SVP 两条记录且 participant_id 正确；
 * 2. 职业映射缺失时回退均衡权重不报错；
 * 3. 一方无人时跳过对应称号写入；
 * 4. 并发重复写入由 DuplicateKeyException 兜底吞掉。</p>
 */
@ExtendWith(MockitoExtension.class)
class MatchMvpServiceTest {

    @Mock
    private MatchMvpMapper matchMvpMapper;

    @Mock
    private ChampionClassMapper championClassMapper;

    /** 真实 Jackson 实例（spy），验证 scoreDetailJson 序列化路径 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    /** 真实评分引擎（spy）：端到端验证 statsJson → 引擎 → 落库 全链路 */
    @Spy
    private MvpScoringEngine mvpScoringEngine = new MvpScoringEngine();

    @InjectMocks
    private MatchMvpService matchMvpService;

    /**
     * 构造对局主表记录（match.id 回填后）
     */
    private Match buildMatch(Long id, String gameMode, Integer winnerTeamId) {
        Match m = new Match();
        m.setId(id);
        m.setGameId(1000000000L + id);
        m.setGameMode(gameMode);
        m.setWinnerTeamId(winnerTeamId);
        m.setGameDuration(1800);
        return m;
    }

    /**
     * 构造一名已落库的参与者（id 已回填），statsJson 携带评分维度字段
     */
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
        // statsJson：评分引擎所需的维度字段（与 LCU/SGP 字段名一致）
        p.setStatsJson("""
                {"totalDamageDealtToChampions":%d,"totalDamageTaken":%d,"visionScore":%d,\
                "totalHeal":%d,"totalDamageShieldedOnTeammates":%d,"timeCCingOthers":%d}\
                """.formatted(damage, taken, vision, heal, shield, cc));
        return p;
    }

    /** 胜方两人：ADC 高伤害（预期 MVP）、坦克高承伤 */
    private MatchParticipant winnerAdc() {
        return participant(101L, 22, 100, true, 30000, 10, 2, 5, 15000, 12000, 20, 0, 0, 0);
    }

    private MatchParticipant winnerTank() {
        return participant(102L, 57, 100, true, 8000, 2, 5, 10, 9000, 45000, 15, 0, 0, 35);
    }

    /** 负方两人：ADC 中伤害（预期 SVP）、辅助高视野治疗 */
    private MatchParticipant loserAdc() {
        return participant(103L, 81, 200, false, 25000, 12, 4, 6, 13000, 13000, 10, 0, 0, 0);
    }

    private MatchParticipant loserSupport() {
        return participant(104L, 16, 200, false, 6000, 1, 6, 14, 8000, 14000, 55, 12000, 3000, 30);
    }

    /** mock 职业映射表数据 */
    private void mockClassMap() {
        when(championClassMapper.selectList(any())).thenReturn(List.of(
                cc(22, "ADC"), cc(57, "TANK"), cc(81, "ADC"), cc(16, "SUPPORT")));
    }

    private ChampionClass cc(int championId, String className) {
        ChampionClass c = new ChampionClass();
        c.setChampionId(championId);
        c.setClassName(className);
        return c;
    }

    /** mock insert 回填主键 */
    private void mockInsert() {
        doAnswer(inv -> {
            MatchMvp m = inv.getArgument(0);
            m.setId(System.nanoTime());
            return 1;
        }).when(matchMvpMapper).insert(any(MatchMvp.class));
    }

    /**
     * 用例：评选后写入 MVP + SVP 两条记录，participant_id 指向对应最佳选手
     */
    @Test
    @DisplayName("评选落库：MVP=胜方最佳 SVP=负方最佳")
    void evaluateAndSave_writesMvpAndSvp() {
        mockClassMap();
        mockInsert();

        matchMvpService.evaluateAndSave(
                buildMatch(1L, "CLASSIC", 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport()));

        ArgumentCaptor<MatchMvp> captor = ArgumentCaptor.forClass(MatchMvp.class);
        verify(matchMvpMapper, times(2)).insert(captor.capture());
        List<MatchMvp> saved = captor.getAllValues();

        // MVP：胜方 ADC（伤害碾压）→ participantId 101
        MatchMvp mvp = saved.stream().filter(m -> "MVP".equals(m.getType())).findFirst().orElseThrow();
        assertThat(mvp.getParticipantId()).isEqualTo(101L);
        assertThat(mvp.getMatchId()).isEqualTo(1L);
        assertThat(mvp.getScore()).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(mvp.getScoreDetailJson()).isNotBlank();

        // SVP：负方 ADC（伤害高于辅助）→ participantId 103
        MatchMvp svp = saved.stream().filter(m -> "SVP".equals(m.getType())).findFirst().orElseThrow();
        assertThat(svp.getParticipantId()).isEqualTo(103L);
        assertThat(svp.getScoreDetailJson()).isNotBlank();
    }

    /**
     * 用例：职业映射表为空时回退均衡权重，评选不报错
     */
    @Test
    @DisplayName("职业映射缺失回退均衡权重")
    void evaluateAndSave_fallsBackWhenClassMapEmpty() {
        when(championClassMapper.selectList(any())).thenReturn(List.of());
        mockInsert();

        assertThatCode(() -> matchMvpService.evaluateAndSave(
                buildMatch(1L, "CLASSIC", 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport())))
                .doesNotThrowAnyException();

        verify(matchMvpMapper, times(2)).insert(any(MatchMvp.class));
    }

    /**
     * 用例：仅胜方数据（异常输入）时只写 MVP，SVP 为空跳过
     */
    @Test
    @DisplayName("一方无人时跳过对应称号")
    void evaluateAndSave_skipsWhenOneSideEmpty() {
        mockClassMap();
        mockInsert();

        matchMvpService.evaluateAndSave(
                buildMatch(1L, "CLASSIC", 100),
                List.of(winnerAdc(), winnerTank()));

        verify(matchMvpMapper, times(1)).insert(any(MatchMvp.class));
    }

    /**
     * 用例：并发重复推送时唯一约束冲突被吞掉（幂等兜底），不影响主流程
     */
    @Test
    @DisplayName("DuplicateKeyException 幂等兜底")
    void evaluateAndSave_swallowsDuplicateKey() {
        mockClassMap();
        doThrow(new DuplicateKeyException("uk_match_mvp"))
                .when(matchMvpMapper).insert(any(MatchMvp.class));

        assertThatCode(() -> matchMvpService.evaluateAndSave(
                buildMatch(1L, "CLASSIC", 100),
                List.of(winnerAdc(), winnerTank(), loserAdc(), loserSupport())))
                .doesNotThrowAnyException();
    }

    /**
     * 用例：参与者 statsJson 缺失（null）时按 0 兜底参与评分，不抛异常
     */
    @Test
    @DisplayName("statsJson 缺失按 0 兜底")
    void evaluateAndSave_handlesNullStatsJson() {
        mockClassMap();
        mockInsert();

        MatchParticipant broken = winnerAdc();
        broken.setStatsJson(null);

        assertThatCode(() -> matchMvpService.evaluateAndSave(
                buildMatch(1L, "CLASSIC", 100),
                List.of(broken, winnerTank(), loserAdc(), loserSupport())))
                .doesNotThrowAnyException();

        verify(matchMvpMapper, times(2)).insert(any(MatchMvp.class));
    }
}