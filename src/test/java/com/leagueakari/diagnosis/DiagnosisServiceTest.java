package com.leagueakari.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.dto.diagnosis.DiagnosisResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.mapper.ChampionClassMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * DiagnosisService 单元测试（工单 #36：对局诊断）：
 * 维度计算（原始值 + 队内位次 + 短板标记）、败局短板高亮、
 * 职业差异化（辅助视野/控制豁免短板判定）、与 OP Score 体系隔离（无评分写库）。
 * 维度原始值从夹具人工复算（黄金样本）。
 */
@ExtendWith(MockitoExtension.class)
class DiagnosisServiceTest {

    @Mock
    private com.leagueakari.mapper.MatchMapper matchMapper;

    @Mock
    private com.leagueakari.mapper.MatchParticipantMapper participantMapper;

    @Mock
    private ChampionClassMapper championClassMapper;

    @Mock
    private GameDataService gameDataService;

    @Spy
    private ParticipantStatsReader statsReader = new ParticipantStatsReader(new ObjectMapper());

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DiagnosisService service;

    /** 败局对局（100 队输） */
    private Match lostMatch;

    @BeforeEach
    void setUp() {
        lostMatch = new Match();
        lostMatch.setId(1L);
        lostMatch.setGameId(100L);
        lostMatch.setGameDuration(1800);
        lostMatch.setWinnerTeamId(200);
        lenient().when(gameDataService.championName(any(Integer.class))).thenReturn("英雄");
        // 职业表：全量返回空（默认职业走 FIGHTER 兜底路径）——按用例覆盖
        lenient().when(championClassMapper.selectList(any())).thenReturn(List.of());
    }

    /** 构造参赛者（局内序号 = 上报顺序，stats 携带诊断维度字段） */
    private MatchParticipant participant(long id, String puuid, String name, int champId, int teamId,
            String statsJson) {
        MatchParticipant p = new MatchParticipant();
        p.setId(id);
        p.setMatchId(1L);
        p.setPuuid(puuid);
        p.setSummonerName(name);
        p.setChampionId(champId);
        p.setTeamId(teamId);
        p.setStatsJson(statsJson);
        return p;
    }

    /** 100 队三人夹具：A（视野垫底）B（中庸）C（全队最高），败局 */
    private void stubLostTeam() {
        when(matchMapper.selectOne(any())).thenReturn(lostMatch);
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, "p1", "玩家一", 103, 100,
                        """
                        {"visionScore":8,"timeCCingOthers":25,"goldEarned":9000,
                         "damageDealtToObjectives":1500,"totalDamageDealtToChampions":12000}
                        """),
                participant(2, "p2", "玩家二", 117, 100,
                        """
                        {"visionScore":25,"timeCCingOthers":40,"goldEarned":12000,
                         "damageDealtToObjectives":3000,"totalDamageDealtToChampions":20000}
                        """),
                participant(3, "p3", "玩家三", 84, 100,
                        """
                        {"visionScore":40,"timeCCingOthers":60,"goldEarned":15000,
                         "damageDealtToObjectives":5000,"totalDamageDealtToChampions":30000}
                        """)));
    }

    /** 用例：维度计算——原始值 + 队内位次（黄金样本复算） */
    @Test
    void diagnose_computesDimensionsWithRank() {
        stubLostTeam();

        DiagnosisResponse response = service.diagnose(100L);

        assertThat(response.isWin()).isFalse();
        assertThat(response.getPlayers()).hasSize(3);
        DiagnosisResponse.PlayerDiagnosis player1 = response.getPlayers().get(0);
        // 视野 8：队内 3 人最低 → 位次 3（1 = 最高）
        DiagnosisResponse.Dimension vision = dimensionOf(player1, "vision");
        assertThat(vision.getRawValue()).isEqualTo(8.0);
        assertThat(vision.getTeamRank()).isEqualTo(3);
        // 伤害转化 = 伤害 / 经济：12000/9000 = 1.33
        DiagnosisResponse.Dimension conversion = dimensionOf(player1, "damageConversion");
        // 展示层保留两位小数
        assertThat(conversion.getRawValue()).isEqualTo(1.33);
        // 玩家三全维度最高：视野位次 1
        assertThat(dimensionOf(response.getPlayers().get(2), "vision").getTeamRank()).isEqualTo(1);
    }

    /** 用例：败局短板判定——败局且队内末位且显著低于队均 → weak=true 高亮 */
    @Test
    void diagnose_marksWeakDimensionInLoss() {
        stubLostTeam();

        DiagnosisResponse response = service.diagnose(100L);

        // 玩家一视野 8 远低于队均（8+25+40)/3≈24.3 → 败局短板高亮
        DiagnosisResponse.Dimension vision = dimensionOf(response.getPlayers().get(0), "vision");
        assertThat(vision.isWeak()).isTrue();
        // 玩家三视野队内最高 → 非短板
        assertThat(dimensionOf(response.getPlayers().get(2), "vision").isWeak()).isFalse();
    }

    /** 用例：胜局不高亮短板（诊断展示但不判"输在哪"） */
    @Test
    void diagnose_noWeakMarkInWin() {
        lostMatch.setWinnerTeamId(100);
        stubLostTeam();

        DiagnosisResponse response = service.diagnose(100L);

        assertThat(response.isWin()).isTrue();
        assertThat(dimensionOf(response.getPlayers().get(0), "vision").isWeak()).isFalse();
    }

    /** 用例：辅助职业视野/控制豁免短板判定（职业差异化——辅助视野天然低非短板） */
    @Test
    void diagnose_supportExemptFromVisionWeak() {
        when(matchMapper.selectOne(any())).thenReturn(lostMatch);
        // 玩家一为辅助（职业表返回 SUPPORT），视野 8 仍队内最低
        when(championClassMapper.selectList(any())).thenReturn(List.of(
                championClass(103, "SUPPORT")));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, "p1", "玩家一", 103, 100,
                        """
                        {"visionScore":8,"timeCCingOthers":25,"goldEarned":9000,
                         "damageDealtToObjectives":1500,"totalDamageDealtToChampions":12000}
                        """),
                participant(2, "p2", "玩家二", 117, 100,
                        """
                        {"visionScore":30,"timeCCingOthers":40,"goldEarned":12000,
                         "damageDealtToObjectives":3000,"totalDamageDealtToChampions":20000}
                        """)));

        DiagnosisResponse response = service.diagnose(100L);

        // 辅助的视野维度：仍展示（位次/raw），但不判短板
        DiagnosisResponse.Dimension vision = dimensionOf(response.getPlayers().get(0), "vision");
        assertThat(vision.getTeamRank()).isEqualTo(2);
        assertThat(vision.isWeak()).isFalse();
        // 玩家二（非辅助）视野队内最高，同样非短板
        assertThat(dimensionOf(response.getPlayers().get(1), "vision").isWeak()).isFalse();
    }

    /** 用例：对局不存在抛 2001 */
    @Test
    void diagnose_throwsWhenMatchMissing() {
        when(matchMapper.selectOne(any())).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.diagnose(999L))
                .isInstanceOf(com.leagueakari.common.exception.BizException.class)
                .hasMessageContaining("对局不存在");
    }

    /** 从玩家诊断中取指定维度的便捷断言 */
    private DiagnosisResponse.Dimension dimensionOf(DiagnosisResponse.PlayerDiagnosis player, String key) {
        return player.getDimensions().stream()
                .filter(d -> d.getKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    /** 构造职业表记录 */
    private com.leagueakari.entity.ChampionClass championClass(int championId, String className) {
        com.leagueakari.entity.ChampionClass c = new com.leagueakari.entity.ChampionClass();
        c.setChampionId(championId);
        c.setClassName(className);
        return c;
    }
}
