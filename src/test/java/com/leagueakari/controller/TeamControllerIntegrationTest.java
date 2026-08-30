package com.leagueakari.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.ParticipantSyncRequest;
import com.leagueakari.dto.TeamSyncRequest;
import com.leagueakari.service.RiotMatchHistoryService;
import com.leagueakari.service.TeamRosterService;
import com.leagueakari.service.WeeklyAiCommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TeamController 集成测试：真实写入虚拟机 MySQL，覆盖周报/榜单/成员/成员卡的
 * HTTP 契约与时间范围 SQL 过滤（真实 WHERE 语义）。
 * <p>外部 I/O（roster 解析、Riot 回填）用 MockBean 隔离——集成测试只验证
 * HTTP 契约与查询口径，不依赖 Riot 网络。测试用 gameId 取 9000000201+ 区间，
 * 与真实数据及既有测试夹具隔离。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeamControllerIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** roster 解析依赖 Riot 网络：集成测试中固定两名成员 */
    @MockBean
    private TeamRosterService teamRosterService;

    /** 回填触发 Riot 网络：集成测试只验证触发契约 */
    @MockBean
    private RiotMatchHistoryService backfillService;

    /**
     * AI 周锐评是外部 I/O：集成测试固定返回值，保证断言确定性
     * （本机若配了 AI_API_KEY，真实调用会产生非确定内容并拖慢测试）
     */
    @MockBean
    private WeeklyAiCommentService aiCommentService;

    @BeforeEach
    void setUpRoster() {
        when(teamRosterService.requireMembers()).thenReturn(List.of(
                new TeamRosterService.RosterMember("成员甲#tw2",
                        new java.util.LinkedHashSet<>(List.of("fleet-puuid-1"))),
                new TeamRosterService.RosterMember("成员乙#tw2",
                        new java.util.LinkedHashSet<>(List.of("fleet-puuid-2")))));
    }

    /** 本周内的一天（2026-08-26 14:00 +08:00）的 epoch 毫秒 */
    private long inWeekCreation() {
        return ZonedDateTime.of(2026, 8, 26, 14, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    /** 时间范围之外的局（2026-09-10，不同周）的 epoch 毫秒 */
    private long outOfWeekCreation() {
        return ZonedDateTime.of(2026, 9, 10, 14, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    /**
     * 构造一场车队对局同步请求：两名 roster 成员同队（构成车队对局），走真实同步入库
     */
    private MatchSyncRequest buildFleetRequest(long gameId, long creation, boolean win) {
        MatchSyncRequest req = new MatchSyncRequest();
        req.setGameId(gameId);
        req.setGameCreation(creation);
        req.setGameDuration(1200);
        req.setGameMode("KIWI");
        req.setGameType("MATCHED_GAME");
        req.setQueueId(2400);
        req.setMapId(12);
        req.setGameVersion("16.15.802.4387");
        req.setRegion("TW2");
        req.setRsoPlatformId("");
        req.setDataSource("lcu");
        req.setWinnerTeamId(win ? 100 : 200);
        req.setSelfPuuid("fleet-puuid-1");
        req.setTeams(new ArrayList<>());
        TeamSyncRequest team = new TeamSyncRequest();
        team.setTeamId(100);
        team.setWin(win);
        req.setTeams(List.of(team));

        List<ParticipantSyncRequest> participants = new ArrayList<>();
        participants.add(participant("fleet-puuid-1", "成员甲", 103, 100, 5, 2, 5, win, 20000));
        participants.add(participant("fleet-puuid-2", "成员乙", 117, 100, 3, 4, 4, win, 15000));
        participants.add(participant("stranger-1", "路人甲", 266, 200, 2, 8, 1, !win, 9000));
        req.setParticipants(participants);
        return req;
    }

    /** 构造参赛者：直显字段齐全 + stats 快照 */
    private ParticipantSyncRequest participant(String puuid, String name, int champId, int teamId,
            int k, int d, int a, boolean win, int damage) {
        ParticipantSyncRequest p = new ParticipantSyncRequest();
        p.setPuuid(puuid);
        p.setSummonerName(name);
        p.setChampionId(champId);
        p.setTeamId(teamId);
        p.setKills(k);
        p.setDeaths(d);
        p.setAssists(a);
        p.setWin(win);
        p.setGoldEarned(12000);
        p.setCs(180);
        p.setItems(List.of(6653, 3078));
        p.setSummonerSpells(List.of(4, 12));
        p.setStats(Map.of("totalDamageDealtToChampions", damage, "totalDamageTaken", 20000));
        return p;
    }

    /**
     * 用例：周报只统计 date 所在周的车队对局（真实 SQL 范围过滤）——
     * 入库本周 2 局 + 下周 1 局，周报 gameCount=2；响应契约 {data:{weekLabel, overview…}}
     */
    @Test
    void weekly_onlyIncludesRequestedWeek() throws Exception {
        // 周报主体 + AI 锐评透传（AI 服务已被 mock，返回值固定）
        when(aiCommentService.generateComment(org.mockito.ArgumentMatchers.any()))
                .thenReturn("测试锐评");
        // 本周两局（甲乙同队全胜）+ 下周一局（不应计入）
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildFleetRequest(9000000201L, inWeekCreation(), true))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildFleetRequest(9000000202L, inWeekCreation() + 7_200_000L, true))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildFleetRequest(9000000203L, outOfWeekCreation(), false))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/team/weekly").param("date", "2026-08-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weekLabel").value("2026-08-24 ~ 2026-08-30"))
                .andExpect(jsonPath("$.data.overview.gameCount").value(2))
                .andExpect(jsonPath("$.data.overview.winCount").value(4))
                .andExpect(jsonPath("$.data.attendanceBoard[0].value").value(2))
                // AI 锐评经 TeamStatsService 透传（mock 固定值，验证装配与降级链路之外的正常路径）
                .andExpect(jsonPath("$.data.aiComment").value("测试锐评"));
    }

    /** 用例：roster 未配置时周报接口返回 400 与明确提示（全局异常处理器转换） */
    @Test
    void weekly_returns400WhenRosterMissing() throws Exception {
        when(teamRosterService.requireMembers())
                .thenThrow(new IllegalArgumentException("车队名单未配置：请先在服务端配置 team.roster 成员名单"));

        mockMvc.perform(get("/api/team/weekly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("车队名单未配置")));
    }

    /** 用例：榜单接口按维度返回；未知维度 400 */
    @Test
    void leaderboards_contractAndUnknownDimension() throws Exception {
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildFleetRequest(9000000211L, inWeekCreation(), true))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/team/leaderboards").param("dimension", "attendance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dimension").value("attendance"))
                .andExpect(jsonPath("$.data.entries[0].value").value(1));

        mockMvc.perform(get("/api/team/leaderboards").param("dimension", "no-such"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 用例：成员列表返回 roster 两名成员及车队对局出勤；成员卡对陌生 puuid 返回 400 */
    @Test
    void members_andMemberCard_contract() throws Exception {
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                buildFleetRequest(9000000221L, inWeekCreation(), true))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/team/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members.length()").value(2))
                .andExpect(jsonPath("$.data.members[0].games").value(1))
                .andExpect(jsonPath("$.data.members[0].winRate").value(1.0));

        mockMvc.perform(get("/api/team/members/fleet-puuid-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.riotId").value("成员甲#tw2"))
                .andExpect(jsonPath("$.data.trend.length()").value(8));

        mockMvc.perform(get("/api/team/members/stranger-puuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 用例：回填触发契约——POST 返回 code=0 且 started=true（服务被调用） */
    @Test
    void backfill_triggersAndReturnsContract() throws Exception {
        when(backfillService.startBackfill()).thenReturn(true);

        mockMvc.perform(post("/api/team/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.started").value(true));
    }
}
