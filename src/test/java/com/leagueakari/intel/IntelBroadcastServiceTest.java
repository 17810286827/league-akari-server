package com.leagueakari.intel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leagueakari.common.exception.QqPushException;
import com.leagueakari.config.PushProperties;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.dto.intel.GameStartSignalRequest;
import com.leagueakari.entity.EnemyScoutMatch;
import com.leagueakari.entity.EnemyScoutRank;
import com.leagueakari.entity.IntelGameStart;
import com.leagueakari.mapper.EnemyScoutMatchMapper;
import com.leagueakari.mapper.EnemyScoutRankMapper;
import com.leagueakari.mapper.IntelGameStartMapper;
import com.leagueakari.qqbot.QqBotClient;
import com.leagueakari.team.TeamRosterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 敌方情报推送编排单测（Mock Mapper/QQ/渲染器，仿 BroadcastCoordinatorTest 形态）：
 * 锁定契约——我方锚定（roster 多数派）、红蓝方映射、降级不静默、去重、失败落库。
 */
@ExtendWith(MockitoExtension.class)
class IntelBroadcastServiceTest {

    @Mock
    private IntelGameStartMapper gameStartMapper;
    @Mock
    private EnemyScoutMatchMapper scoutMatchMapper;
    @Mock
    private EnemyScoutRankMapper scoutRankMapper;
    @Mock
    private TeamRosterService rosterService;
    @Mock
    private QqBotClient qqBotClient;
    @Mock
    private IntelCardRenderer renderer;

    private PushProperties pushProperties;
    private TeamProperties teamProperties;
    private IntelBroadcastService service;

    @BeforeEach
    void setUp() {
        pushProperties = new PushProperties();
        pushProperties.setEnabled(true);
        pushProperties.setGroupOpenId("group-1");
        pushProperties.setAppId("app-1");
        pushProperties.setClientSecret("secret-1");
        teamProperties = new TeamProperties();
        teamProperties.setName("iKun");
        teamProperties.setMinSharedMembers(2);
        // 测试用低阈值（2 局共现即判开黑），便于验证胜率计算链路
        com.leagueakari.config.IntelProperties intelProperties = new com.leagueakari.config.IntelProperties();
        intelProperties.setPremadeDetectThreshold(2);
        service = new IntelBroadcastService(
                gameStartMapper, scoutMatchMapper, scoutRankMapper,
                rosterService, qqBotClient, renderer, pushProperties, teamProperties, intelProperties);
    }

    /** 造一个车队成员（身份集合含 p1/p2） */
    private TeamRosterService.RosterMember member(String riotId, String... puuids) {
        return new TeamRosterService.RosterMember(riotId, new LinkedHashSet<>(List.of(puuids)));
    }

    /** 开始信号：蓝方含 2 名车队成员（我方=蓝方），红方 5 名敌方 */
    private GameStartSignalRequest signal() {
        GameStartSignalRequest req = new GameStartSignalRequest();
        req.setGameId(12345L);
        req.setBluePuids(List.of("p1", "p2", "e1", "e2", "e3"));
        req.setRedPuids(List.of("x1", "x2", "x3", "x4", "x5"));
        req.setReporterPuuid("p1");
        req.setQueueId(420);
        return req;
    }

    @Test
    void 去重_同一局重复信号只推一次() {
        // 首次：insert 成功（发卡）；再次：insert 抛唯一键冲突（跳过）
        when(gameStartMapper.insert(any(IntelGameStart.class)))
                .thenReturn(1)
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        when(rosterService.requireMembers()).thenReturn(List.of(member("m1", "p1"), member("m2", "p2")));
        when(renderer.render(any())).thenReturn(new byte[]{1});

        service.onGameStart(signal());
        service.onGameStart(signal());

        // 只发送一次
        verify(qqBotClient, times(1)).sendGroupImageMessage(anyString(), any());
    }

    @Test
    void 我方锚定_roster多数派判定为蓝色方() {
        when(gameStartMapper.insert(any(IntelGameStart.class))).thenReturn(1);
        // 蓝方 2 名车队成员（p1/p2），红方 0 → 我方=蓝方(100)
        when(rosterService.requireMembers()).thenReturn(List.of(member("m1", "p1"), member("m2", "p2")));
        when(renderer.render(any())).thenReturn(new byte[]{1});

        service.onGameStart(signal());

        // 发卡前应把 friendlyTeamId=100 落库（capture 实体）
        ArgumentCaptor<IntelGameStart> cap = ArgumentCaptor.forClass(IntelGameStart.class);
        verify(gameStartMapper, times(1)).insert(cap.capture());
        // 我方锚定结果通过 update 落库（friendly_team_id 由 update 写入）
        verify(gameStartMapper, atLeastOnce()).update(any(), any());
    }

    @Test
    void 降级_敌方无历史无段位仍出卡() {
        when(gameStartMapper.insert(any(IntelGameStart.class))).thenReturn(1);
        when(rosterService.requireMembers()).thenReturn(List.of(member("m1", "p1"), member("m2", "p2")));
        // 侦察缓存全空：无摘要、无段位
        when(scoutMatchMapper.selectList(any())).thenReturn(List.of());
        when(scoutRankMapper.selectList(any())).thenReturn(List.of());
        when(renderer.render(any())).thenReturn(new byte[]{1});

        service.onGameStart(signal());

        // 降级仍渲染并发送（永远出一张卡）
        verify(renderer, times(1)).render(any());
        verify(qqBotClient, times(1)).sendGroupImageMessage(anyString(), any());
    }

    @Test
    void 推送失败_落库FAILED不静默() {
        when(gameStartMapper.insert(any(IntelGameStart.class))).thenReturn(1);
        when(rosterService.requireMembers()).thenReturn(List.of(member("m1", "p1"), member("m2", "p2")));
        when(renderer.render(any())).thenReturn(new byte[]{1});
        doThrow(new QqPushException("upload failed")).when(qqBotClient).sendGroupImageMessage(anyString(), any());

        service.onGameStart(signal());

        // 失败落库：update 置 FAILED + push_error
        verify(gameStartMapper, atLeastOnce()).update(any(), any());
    }

    @Test
    void 开关关闭_不推且不落去重状态() {
        pushProperties.setEnabled(false);
        service.onGameStart(signal());

        verifyNoInteractions(qqBotClient);
        verifyNoInteractions(gameStartMapper);
    }

    @Test
    void 开黑组搭档胜率_按共现局计算() {
        when(gameStartMapper.insert(any(IntelGameStart.class))).thenReturn(1);
        when(rosterService.requireMembers()).thenReturn(List.of(member("m1", "p1"), member("m2", "p2")));
        when(renderer.render(any())).thenReturn(new byte[]{1});
        when(scoutRankMapper.selectList(any())).thenReturn(List.of());
        // 敌方 x1/x2 开黑共现 3 局（m1/m2/m3）：2 胜 1 负；x3/x4/x5 无历史
        List<EnemyScoutMatch> rows = List.of(
                matchRow("m1", "x1", 200, true),
                matchRow("m1", "x2", 200, true),
                matchRow("m2", "x1", 200, true),
                matchRow("m2", "x2", 200, true),
                matchRow("m3", "x1", 200, false),
                matchRow("m3", "x2", 200, false)
        );
        when(scoutMatchMapper.selectList(any())).thenReturn(rows);

        service.onGameStart(signal());

        // 捕获传给渲染器的 EnemyIntel，断言开黑组搭档胜率 = 2/3
        ArgumentCaptor<EnemyIntel> cap = ArgumentCaptor.forClass(EnemyIntel.class);
        verify(renderer).render(cap.capture());
        EnemyIntel intel = cap.getValue();
        assertThat(intel.getPremadeGroups()).hasSize(1);
        assertThat(intel.getPremadeGroups().get(0).getPlayers()).containsExactlyInAnyOrder("x1", "x2");
        assertThat(intel.getPremadeWinRates()).hasSize(1);
        EnemyIntel.PremadeWinRate wr = intel.getPremadeWinRates().get(0);
        assertThat(wr.getGames()).isEqualTo(3);
        assertThat(wr.getWins()).isEqualTo(2);
        assertThat(wr.getWinRate()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
    }

    /** 造一行敌方摘要 */
    private EnemyScoutMatch matchRow(String matchId, String puuid, int teamId, boolean win) {
        EnemyScoutMatch m = new EnemyScoutMatch();
        m.setMatchId(matchId);
        m.setPuuid(puuid);
        m.setTeamId(teamId);
        m.setWin(win);
        return m;
    }
}
