package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.PushProperties;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.leagueakari.qqbot.QqBotClient;
import com.leagueakari.qqbot.QqPushException;
import com.leagueakari.reportimage.ReportImageData;
import com.leagueakari.reportimage.ReportImageRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BroadcastCoordinator 单元测试：局后播报的判定矩阵与状态机
 * （车队局/时间窗/开关/状态门控 → 发送 → SENT/FAILED 落库）
 */
@ExtendWith(MockitoExtension.class)
class BroadcastCoordinatorTest {

    /** 固定时钟：2026-09-02 20:00:00 +08（与生产 TimeConfig 同区） */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchParticipantMapper participantMapper;

    @Mock
    private MatchMvpMapper mvpMapper;

    @Mock
    private TeamRosterService rosterService;

    @Mock
    private GameDataService gameDataService;

    @Mock
    private QqBotClient qqBotClient;

    @Mock
    private PostGameCommentService postGameCommentService;

    @Mock
    private PostGameSummaryBuilder postGameSummaryBuilder;

    private PushProperties pushProperties;

    private TeamProperties teamProperties;

    private BroadcastCoordinator coordinator;

    @BeforeEach
    void setUp() {
        // 开启且配置齐备的默认推送配置
        pushProperties = new PushProperties();
        pushProperties.setEnabled(true);
        pushProperties.setGroupOpenId("GROUP-1");
        pushProperties.setAppId("app-1");
        pushProperties.setClientSecret("secret-1");
        pushProperties.setRecentWindowMinutes(30);

        teamProperties = new TeamProperties();
        teamProperties.setName("iKun");
        teamProperties.setMinSharedMembers(2);

        coordinator = new BroadcastCoordinator(matchMapper, participantMapper, mvpMapper,
                pushProperties, teamProperties, rosterService, gameDataService, qqBotClient,
                new ReportImageRenderer(), postGameCommentService, postGameSummaryBuilder,
                FIXED_CLOCK, new ObjectMapper());
    }

    /**
     * 车队成员 fixture：两名成员分别命中参与者 puuid-A / puuid-B
     */
    private List<TeamRosterService.RosterMember> rosterOfTwo() {
        TeamRosterService.RosterMember m1 = new TeamRosterService.RosterMember(
                "赌书消得泼茶香#iKun", new LinkedHashSet<>(Set.of("puuid-A")), null);
        TeamRosterService.RosterMember m2 = new TeamRosterService.RosterMember(
                "手裂鬼子#tw2", new LinkedHashSet<>(Set.of("puuid-B")), null);
        return List.of(m1, m2);
    }

    /**
     * 构造刚结束的车队对局（结束时刻 = 当前时钟）：
     * 蓝队 100 含两名车队成员，蓝队胜（winnerTeamId=100）
     */
    private Match freshFleetMatch() {
        Match match = new Match();
        match.setId(1L);
        match.setGameId(2000000001L);
        match.setGameCreation(FIXED_CLOCK.millis() - 1_800_000L); // 30 分钟前开局
        match.setGameDuration(1800);                              // 结束时刻 = 现在
        match.setGameMode("CLASSIC");
        match.setQueueId(440);
        match.setWinnerTeamId(100);
        match.setPushStatus("PENDING");
        return match;
    }

    /** 参赛者 fixture：两名车队成员（蓝队胜方）+ 三名路人同队 + 红队五人 */
    private List<MatchParticipant> fleetParticipants() {
        MatchParticipant a = participant(101L, "puuid-A", "赌书消得泼茶香", 103, 100, true, 12, 3, 7);
        MatchParticipant b = participant(102L, "puuid-B", "手裂鬼子", 11, 100, true, 8, 4, 10);
        MatchParticipant c1 = participant(103L, "rand-1", "路人甲", 5, 100, true, 4, 2, 14);
        MatchParticipant c2 = participant(104L, "rand-2", "路人乙", 22, 100, true, 7, 5, 8);
        MatchParticipant c3 = participant(105L, "rand-3", "路人丙", 1, 100, true, 1, 4, 17);
        MatchParticipant d1 = participant(106L, "enemy-1", "敌方一", 57, 200, false, 5, 7, 3);
        MatchParticipant d2 = participant(107L, "enemy-2", "敌方二", 59, 200, false, 2, 8, 4);
        return List.of(a, b, c1, c2, c3, d1, d2);
    }

    private MatchParticipant participant(Long id, String puuid, String name, int championId,
                                         int teamId, boolean win, int kills, int deaths, int assists) {
        MatchParticipant p = new MatchParticipant();
        p.setId(id);
        p.setPuuid(puuid);
        p.setSummonerName(name);
        p.setChampionId(championId);
        p.setTeamId(teamId);
        p.setWin(win);
        p.setKills(kills);
        p.setDeaths(deaths);
        p.setAssists(assists);
        return p;
    }

    /** 打桩通用数据：对局 + 参赛者 + 车队成员（评选记录由各用例自行 stub） */
    private void stubCommon() {
        when(matchMapper.selectOne(any(QueryWrapper.class))).thenReturn(freshFleetMatch());
        when(participantMapper.selectList(any(QueryWrapper.class))).thenReturn(fleetParticipants());
        when(rosterService.requireMembers()).thenReturn(rosterOfTwo());
        when(matchMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    /** 无评选记录的打桩 */
    private void stubNoAwards() {
        when(mvpMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
    }

    /**
     * 用例：刚结束的车队局（状态 PENDING）→ 渲染战报图发送 + 锐评文本补发，
     * 状态推进：CAS(PUSHING) + SENT + comment 送达共三次 update
     */
    @Test
    void maybeBroadcast_sendsImageAndCommentForFreshFleetGame() {
        stubCommon();
        stubNoAwards();
        when(gameDataService.championName(103)).thenReturn("阿狸");
        when(gameDataService.championName(11)).thenReturn("大师");
        when(postGameCommentService.generateComment(any())).thenReturn("这把养鱼人把对面野区当自己家");

        coordinator.maybeBroadcast(2000000001L);

        // 第一条：PNG 战报图（PNG magic 头校验）
        ArgumentCaptor<byte[]> png = ArgumentCaptor.forClass(byte[].class);
        verify(qqBotClient).sendGroupImageMessage(eq("GROUP-1"), png.capture());
        assertThat(png.getValue())
                .startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47)
                .isNotEmpty();
        // 第二条：锐评文本补发
        verify(qqBotClient).sendGroupMarkdownMessage(eq("GROUP-1"), eq("这把养鱼人把对面野区当自己家"));
        // 状态机：CAS(PUSHING) + SENT + comment 送达共三次更新
        verify(matchMapper, org.mockito.Mockito.times(3)).update(isNull(), any(Wrapper.class));
    }

    /**
     * 用例：战报图比分 = 双方击杀合计（蓝队 12+8+4+7+1=32，红队 5+2=7）。
     * 回归：此前 buildImageData 漏填 mainScore/otherScore，图上恒显 "0 : 0"
     */
    @Test
    void maybeBroadcast_setsScoreFromTeamKills() {
        stubCommon();
        stubNoAwards();
        when(gameDataService.championName(anyInt())).thenReturn("英雄");
        when(postGameCommentService.generateComment(any())).thenReturn("锐评");
        ReportImageRenderer mockRenderer = mock(ReportImageRenderer.class);
        when(mockRenderer.renderPng(any())).thenReturn(new byte[]{1});
        BroadcastCoordinator c = new BroadcastCoordinator(matchMapper, participantMapper, mvpMapper,
                pushProperties, teamProperties, rosterService, gameDataService, qqBotClient,
                mockRenderer, postGameCommentService, postGameSummaryBuilder, FIXED_CLOCK, new ObjectMapper());

        c.maybeBroadcast(2000000001L);

        ArgumentCaptor<ReportImageData> data = ArgumentCaptor.forClass(ReportImageData.class);
        verify(mockRenderer).renderPng(data.capture());
        assertThat(data.getValue().mainScore).isEqualTo(32);
        assertThat(data.getValue().otherScore).isEqualTo(7);
    }

    /**
     * 用例：同局只有 1 名车队成员（不够开黑局阈值）→ 不发送，置 SENT 避免重复检查
     */
    @Test
    void maybeBroadcast_skipsWhenNotFleetGame() {
        when(matchMapper.selectOne(any(QueryWrapper.class))).thenReturn(freshFleetMatch());
        // 参赛者中仅 puuid-A 一名车队成员
        when(participantMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(participant(101L, "puuid-A", "赌书消得泼茶香", 103, 100, true, 12, 3, 7)));
        when(rosterService.requireMembers()).thenReturn(rosterOfTwo());
        when(matchMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        coordinator.maybeBroadcast(2000000001L);

        // 不发送；为免补推反复检查置 SENT（一次 update）
        verify(qqBotClient, never()).sendGroupImageMessage(any(), any());
        verify(matchMapper, org.mockito.Mockito.times(1)).update(isNull(), any(Wrapper.class));
    }

    /**
     * 用例：明显旧局（结束时刻超出时间窗）→ 不发送，置 SENT
     */
    @Test
    void maybeBroadcast_skipsStaleGameOutsideWindow() {
        Match stale = freshFleetMatch();
        // 5 小时前结束：远超 30 分钟时间窗
        stale.setGameCreation(FIXED_CLOCK.millis() - 5 * 3600_000L);
        when(matchMapper.selectOne(any(QueryWrapper.class))).thenReturn(stale);
        when(matchMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        coordinator.maybeBroadcast(2000000001L);

        verify(qqBotClient, never()).sendGroupImageMessage(any(), any());
        verify(matchMapper, org.mockito.Mockito.times(1)).update(isNull(), any(Wrapper.class));
    }

    /**
     * 用例：总开关关闭 → 不发送也不落状态（零副作用，不影响同步主链路）
     */
    @Test
    void maybeBroadcast_noopWhenDisabled() {
        pushProperties.setEnabled(false);
        when(matchMapper.selectOne(any(QueryWrapper.class))).thenReturn(freshFleetMatch());

        coordinator.maybeBroadcast(2000000001L);

        verify(qqBotClient, never()).sendGroupImageMessage(any(), any());
        verify(matchMapper, never()).update(any(), any());
    }

    /**
     * 用例：状态已 SENT（重复补推/并发另一请求已处理）→ 不再发送
     */
    @Test
    void maybeBroadcast_skipsWhenAlreadySent() {
        Match sent = freshFleetMatch();
        sent.setPushStatus("SENT");
        when(matchMapper.selectOne(any(QueryWrapper.class))).thenReturn(sent);

        coordinator.maybeBroadcast(2000000001L);

        verify(qqBotClient, never()).sendGroupImageMessage(any(), any());
        verify(matchMapper, never()).update(any(), any());
    }

    /**
     * 用例：发送失败（QQ 接口异常）→ 状态置 FAILED 并记录错误原因，等待桌面端补推重试
     */
    @Test
    void maybeBroadcast_marksFailedWhenSendThrows() {
        stubCommon();
        stubNoAwards();
        org.mockito.Mockito.doThrow(new QqPushException("QQ 图片消息发送失败（HTTP 401）"))
                .when(qqBotClient).sendGroupImageMessage(any(), any());

        coordinator.maybeBroadcast(2000000001L);

        // 失败后第二次 update 落 FAILED + push_error（set 参数以占位键存储，按值断言）
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<UpdateWrapper<Match>> wrapperCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(matchMapper, org.mockito.Mockito.times(2)).update(isNull(), wrapperCaptor.capture());
        Map<String, Object> secondUpdate = wrapperCaptor.getAllValues().get(1).getParamNameValuePairs();
        assertThat(secondUpdate.values())
                .contains("FAILED")
                .anyMatch(v -> String.valueOf(v).contains("401"));
    }

    /**
     * 用例：AI 锐评生成失败（重试耗尽）→ 图已送达，改发"AI 缺席提示"，
     * 状态 AI_FAILED（用户诉求：AI 挂了群里也要有提示）
     */
    @Test
    void maybeBroadcast_aiFailureSendsAbsenceTip() {
        stubCommon();
        stubNoAwards();
        when(postGameCommentService.generateComment(any()))
                .thenThrow(new IllegalStateException("AI 返回内容为空"));

        coordinator.maybeBroadcast(2000000001L);

        // 图正常发送；文本改为缺席提示
        verify(qqBotClient).sendGroupImageMessage(eq("GROUP-1"), any(byte[].class));
        ArgumentCaptor<String> tip = ArgumentCaptor.forClass(String.class);
        verify(qqBotClient).sendGroupTextMessage(eq("GROUP-1"), tip.capture());
        assertThat(tip.getValue()).contains("缺席");
        // 状态：CAS + SENT + AI_FAILED 共三次更新
        verify(matchMapper, org.mockito.Mockito.times(3)).update(isNull(), any(Wrapper.class));
    }

    /**
     * 用例：锐评开关关闭 → 只发战报图，不触发 AI、不发缺席提示（SENT 即完成）
     */
    @Test
    void maybeBroadcast_commentDisabledSendsImageOnly() {
        pushProperties.setAiCommentEnabled(false);
        stubCommon();
        stubNoAwards();

        coordinator.maybeBroadcast(2000000001L);

        verify(qqBotClient).sendGroupImageMessage(eq("GROUP-1"), any(byte[].class));
        verify(qqBotClient, never()).sendGroupMarkdownMessage(any(), any());
        verify(qqBotClient, never()).sendGroupTextMessage(any(), any());
        verify(postGameCommentService, never()).generateComment(any());
        // 状态：CAS + SENT 共两次更新
        verify(matchMapper, org.mockito.Mockito.times(2)).update(isNull(), any(Wrapper.class));
    }

    /**
     * 用例：车队局含本队 MVP 评选记录时仍正常渲染发送（称号落在图内行标签）
     */
    @Test
    void maybeBroadcast_withMvpAwardStillSends() {
        stubCommon();
        // puuid-A（101）为胜方 MVP
        MatchMvp mvp = new MatchMvp();
        mvp.setMatchId(1L);
        mvp.setParticipantId(101L);
        mvp.setType("MVP");
        when(mvpMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(mvp));
        when(postGameCommentService.generateComment(any())).thenReturn("锐评正文");

        coordinator.maybeBroadcast(2000000001L);

        ArgumentCaptor<byte[]> png = ArgumentCaptor.forClass(byte[].class);
        verify(qqBotClient).sendGroupImageMessage(eq("GROUP-1"), png.capture());
        assertThat(png.getValue()).isNotEmpty();
        // 状态推进：CAS + SENT + comment 送达共三次 update
        verify(matchMapper, org.mockito.Mockito.times(3)).update(isNull(), any(Wrapper.class));
    }
}
