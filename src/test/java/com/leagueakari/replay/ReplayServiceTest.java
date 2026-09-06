package com.leagueakari.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.config.ReplayProperties;
import com.leagueakari.dto.replay.ReplayResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.match.MatchTimelineService;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ReplayService 单元测试（工单 #35）：装载/视角解析/降级编排。
 * 转折点判定本身由 TurningPointEngineTest 覆盖（纯函数黄金样本），
 * 本测试聚焦 service 的装配口径：局内序号映射、self 视角解析、
 * 无时间线降级（available=false，不抛 2002）、对局不存在抛 2001。
 */
@ExtendWith(MockitoExtension.class)
class ReplayServiceTest {

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchParticipantMapper participantMapper;

    @Mock
    private MatchTimelineService timelineService;

    @Mock
    private GameDataService gameDataService;

    /** 真实引擎（纯函数，规则判定由其专属测试覆盖） */
    @Spy
    private TurningPointEngine engine = new TurningPointEngine(new ReplayProperties());

    /** 真实 ObjectMapper（JSON 转换无 mock 必要，@InjectMocks 不注入非 mock 字段故标 @Spy） */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ReplayService service;

    /** 最小对局：self（puuid-self，100 队）+ 一名敌人（200 队） */
    private Match match;

    @BeforeEach
    void setUp() {
        match = new Match();
        match.setId(1L);
        match.setGameId(100L);
        match.setGameDuration(600);
        match.setSelfPuuid("puuid-self");
        // 英雄名转换：全部回退真实 GameDataService mock（未打桩返回 null → 引擎容错）
        lenient().when(gameDataService.championName(any(Integer.class))).thenReturn("英雄");
    }

    /** 构造参赛者实体（按上报顺序 = 局内序号） */
    private MatchParticipant participant(long id, String puuid, String name, int champId, int teamId) {
        MatchParticipant p = new MatchParticipant();
        p.setId(id);
        p.setMatchId(1L);
        p.setPuuid(puuid);
        p.setSummonerName(name);
        p.setChampionId(champId);
        p.setTeamId(teamId);
        return p;
    }

    /** 构造两帧带一血事件的时间线 */
    private List<Map<String, Object>> frames() {
        Map<String, Object> pf1 = new LinkedHashMap<>();
        pf1.put("participantId", 1);
        pf1.put("totalGold", 6000);
        Map<String, Object> pf2 = new LinkedHashMap<>();
        pf2.put("participantId", 2);
        pf2.put("totalGold", 4000);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("timestamp", 60_000L);
        frame.put("participantFrames", Map.of("1", pf1, "2", pf2));
        frame.put("events", List.of(Map.of(
                "type", "CHAMPION_KILL", "timestamp", 65_000L, "killerId", 1, "victimId", 2)));
        return List.of(frame);
    }

    /** 用例：正常路径——self 视角（100 队）计算，经济差为一血时刻 +2000 */
    @Test
    void replay_computesFromSelfPerspective() {
        when(matchMapper.selectOne(any())).thenReturn(match);
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, "puuid-self", "玩家一", 103, 100),
                participant(2, "puuid-enemy", "玩家二", 84, 200)));
        when(timelineService.getTimeline(100L)).thenReturn(frames());

        ReplayResponse result = service.replay(100L);

        assertThat(result.isAvailable()).isTrue();
        assertThat(result.getPerspectiveTeamId()).isEqualTo(100);
        assertThat(result.getGoldDiffSeries()).hasSize(1);
        assertThat(result.getGoldDiffSeries().get(0).getGoldDiff()).isEqualTo(2000D);
        // 一血由我方（玩家一）拿下；夹具敌方仅 1 人 → 一血同时构成团灭；
        // 单帧 +2000 > 1500 门槛 → 极值转折点（同刻排序：极值先于一血加入）
        assertThat(result.getTurningPoints()).hasSize(3);
        assertThat(result.getTurningPoints()).extracting(ReplayResponse.TurningPoint::getType)
                .containsExactly(TurningPointEngine.TYPE_GOLD_DIFF_EXTREME,
                        TurningPointEngine.TYPE_FIRST_BLOOD, TurningPointEngine.TYPE_TEAM_WIPE);
        // 一血 detail 用英雄名，击杀者身份在 involved 里（玩家一，我方）
        assertThat(result.getTurningPoints().get(1).getDetail()).contains("我方");
        assertThat(result.getTurningPoints().get(1).getInvolved().get(0).getName()).isEqualTo("玩家一");
        assertThat(result.getTurningPoints().get(1).getInvolved().get(0).isPerspective()).isTrue();
        // 击杀事件带召唤师名（局内序号映射正确）
        assertThat(result.getKillEvents().get(0).getKillerName()).isEqualTo("玩家一");
        assertThat(result.getKillEvents().get(0).getVictimName()).isEqualTo("玩家二");
    }

    /** 用例：self 不在参与者中（异常数据）→ 视角回退 100，不抛异常 */
    @Test
    void replay_fallsBackWhenSelfMissing() {
        when(matchMapper.selectOne(any())).thenReturn(match);
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, "puuid-a", "玩家一", 103, 200),
                participant(2, "puuid-b", "玩家二", 84, 100)));
        when(timelineService.getTimeline(100L)).thenReturn(frames());

        ReplayResponse result = service.replay(100L);

        // self（puuid-self）不在参与者里 → 回退 100 队视角
        assertThat(result.getPerspectiveTeamId()).isEqualTo(100);
        assertThat(result.isAvailable()).isTrue();
    }

    /** 用例：无时间线 → available=false 优雅降级（不抛 2002，前端显示提示） */
    @Test
    void replay_degradesWhenTimelineMissing() {
        when(matchMapper.selectOne(any())).thenReturn(match);
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, "puuid-self", "玩家一", 103, 100)));
        when(timelineService.getTimeline(100L)).thenReturn(null);

        ReplayResponse result = service.replay(100L);

        assertThat(result.isAvailable()).isFalse();
        assertThat(result.getGoldDiffSeries()).isEmpty();
        assertThat(result.getTurningPoints()).isEmpty();
        assertThat(result.getKillEvents()).isEmpty();
    }

    /** 用例：对局不存在 → 抛 2001（全局处理器转信封） */
    @Test
    void replay_throwsWhenMatchMissing() {
        when(matchMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.replay(999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("对局不存在");
    }
}
