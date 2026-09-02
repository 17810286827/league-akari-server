package com.leagueakari.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.ParticipantSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.qqbot.QqBotClient;
import com.leagueakari.service.TeamRosterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 局后播报集成测试（T2 主 seam）：真实走 POST /api/matches → 落库 → 判定 → 发送 全链路。
 * <p>机器人客户端与车队名单解析为外部 I/O 用 @MockBean 隔离；
 * push.enabled=true 仅在本类生效。测试数据 gameCreation 取当前时刻（时间窗内），
 * 参与者含两名车队成员（≥ min-shared-members=2）构成车队对局。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "push.enabled=true",
        "push.group-open-id=GROUP-IT",
        "push.app-id=app-it",
        "push.client-secret=secret-it"
})
class PushBroadcastIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MatchMapper matchMapper;

    /** 机器人发送端口：mock 掉真实 HTTP 调用 */
    @MockBean
    private QqBotClient qqBotClient;

    /** 车队名单解析依赖 Riot API（网络），mock 返回两名测试成员 */
    @MockBean
    private TeamRosterService rosterService;

    /**
     * 构造刚结束的车队对局（gameCreation=now-30min、duration=1800 → 结束时刻≈now）：
     * 车队成员 puuid-fleet-1/2 与 3 名路人在蓝队 100 并获胜，红队 5 名对手
     */
    private MatchSyncRequest fleetRequest(long gameId) {
        MatchSyncRequest req = new MatchSyncRequest();
        req.setGameId(gameId);
        req.setGameCreation(System.currentTimeMillis() - 1_800_000L);
        req.setGameDuration(1800);
        req.setGameMode("CLASSIC");
        req.setGameType("MATCHED_GAME");
        req.setQueueId(440);
        req.setMapId(11);
        req.setGameVersion("25.4.1");
        req.setRegion("na1");
        req.setRsoPlatformId("");
        req.setDataSource("lcu");
        req.setWinnerTeamId(100);
        req.setSelfPuuid("puuid-fleet-1");

        List<ParticipantSyncRequest> participants = new ArrayList<>();
        participants.add(participant("puuid-fleet-1", "赌书消得泼茶香", 103, 12, 3, 7, true));
        participants.add(participant("puuid-fleet-2", "手裂鬼子", 11, 8, 4, 10, true));
        participants.add(participant("rand-1", "路人甲", 5, 4, 2, 14, true));
        participants.add(participant("rand-2", "路人乙", 22, 7, 5, 8, true));
        participants.add(participant("rand-3", "路人丙", 1, 1, 4, 17, true));
        // 红队 5 人（败方）
        for (int i = 0; i < 5; i++) {
            participants.add(participant("enemy-" + i, "敌方" + i, 57 + i, 2, 8, 3, false));
        }
        req.setParticipants(participants);
        return req;
    }

    private ParticipantSyncRequest participant(String puuid, String name, int championId,
                                               int kills, int deaths, int assists, boolean win) {
        ParticipantSyncRequest p = new ParticipantSyncRequest();
        p.setPuuid(puuid);
        p.setSummonerName(name);
        p.setChampionId(championId);
        p.setTeamId(win ? 100 : 200);
        p.setKills(kills);
        p.setDeaths(deaths);
        p.setAssists(assists);
        p.setWin(win);
        p.setGoldEarned(12000);
        p.setCs(200);
        p.setStats(java.util.Map.of("totalDamageDealtToChampions", 20000));
        return p;
    }

    /** 车队名单：两名成员分别命中 puuid-fleet-1 / puuid-fleet-2（≥ min-shared-members=2） */
    private void stubRoster() {
        TeamRosterService.RosterMember m1 = new TeamRosterService.RosterMember(
                "赌书消得泼茶香#iKun", new LinkedHashSet<>(Set.of("puuid-fleet-1")), null);
        TeamRosterService.RosterMember m2 = new TeamRosterService.RosterMember(
                "手裂鬼子#tw2", new LinkedHashSet<>(Set.of("puuid-fleet-2")), null);
        when(rosterService.requireMembers()).thenReturn(List.of(m1, m2));
    }

    /**
     * 用例：推入刚结束的车队对局 → 机器人收到 1 条文本战报（含胜负/比分/成员），
     * 落库状态推进为 SENT
     */
    @Test
    void postFleetMatch_broadcastsTextAndMarksSent() throws Exception {
        stubRoster();

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fleetRequest(9100000001L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 发送契约：一条文本消息发到配置的车队群，内容含胜负/比分/车队成员
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(qqBotClient).sendGroupTextMessage(eq("GROUP-IT"), content.capture());
        assertThat(content.getValue())
                .contains("胜利")
                .contains("32")
                .contains("赌书消得泼茶香");

        // 状态推进：PENDING → SENT（push_image_at 已写）
        Match saved = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", 9100000001L));
        assertThat(saved.getPushStatus()).isEqualTo("SENT");
        assertThat(saved.getPushImageAt()).isNotNull();
        assertThat(saved.getPushError()).isNull();
    }

    /**
     * 用例：重复推送同一局（已 SENT）→ 不再发送（幂等 + 状态门控双保险）
     */
    @Test
    void postSameFleetMatchTwice_broadcastsOnlyOnce() throws Exception {
        stubRoster();
        String body = objectMapper.writeValueAsString(fleetRequest(9100000002L));

        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // 只发送一次
        verify(qqBotClient, org.mockito.Mockito.times(1))
                .sendGroupTextMessage(eq("GROUP-IT"), any(String.class));
    }

    /**
     * 用例：同局仅 1 名车队成员（路人局）→ 不发送，状态置 SENT（避免反复判定）
     */
    @Test
    void postNonFleetMatch_skipsBroadcast() throws Exception {
        // 名单只含一名成员 → 命中数 1 < min-shared-members=2
        TeamRosterService.RosterMember only = new TeamRosterService.RosterMember(
                "赌书消得泼茶香#iKun", new LinkedHashSet<>(Set.of("puuid-fleet-1")), null);
        when(rosterService.requireMembers()).thenReturn(List.of(only));

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fleetRequest(9100000003L))))
                .andExpect(status().isOk());

        verify(qqBotClient, never()).sendGroupTextMessage(any(), any());
        Match saved = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", 9100000003L));
        assertThat(saved.getPushStatus()).isEqualTo("SENT");
    }

    /**
     * 用例：发送失败 → 同步接口仍返回 200（不阻塞对局入库），状态落 FAILED + 错误原因
     */
    @Test
    void postFleetMatch_sendFailureMarksFailedButSyncOk() throws Exception {
        stubRoster();
        org.mockito.Mockito.doThrow(new com.leagueakari.qqbot.QqPushException("QQ 群消息发送失败（HTTP 401）"))
                .when(qqBotClient).sendGroupTextMessage(any(), any());

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fleetRequest(9100000004L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        Match saved = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", 9100000004L));
        assertThat(saved.getPushStatus()).isEqualTo("FAILED");
        assertThat(saved.getPushError()).contains("401");
    }
}
