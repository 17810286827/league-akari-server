package com.leagueakari.riot;

import com.leagueakari.common.exception.ErrorCode;

import com.leagueakari.common.exception.BizException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.dto.match.MatchSyncRequest;
import com.leagueakari.entity.RiotAccount;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.RiotAccountMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.leagueakari.match.MatchIngestService;
import com.leagueakari.team.TeamRosterService;

/**
 * RiotMatchHistoryService 单元测试（外部 I/O 接缝）：
 * MATCH-V5 对局历史拉取 → MatchSyncRequest 转换 → saveMatch 幂等入库。
 * 覆盖：ID 列表分页终止、已入库对局跳过（省配额）、字段转换契约、
 * roster 未配置报错、 Riot 单成员失败不阻断整体。
 */
class RiotMatchHistoryServiceTest {

    /** MATCH-V5 单场对局详情 JSON（1 名参与者 + 1 支队伍，字段与 Riot 契约一致） */
    private static final String MATCH_DETAIL = """
            {"metadata":{"matchId":"TW2_1"},"info":{
              "gameId":111222333,"gameCreation":1788000000000,"gameDuration":1500,
              "gameMode":"KIWI","gameType":"MATCHED_GAME","queueId":2400,"mapId":12,
              "gameVersion":"16.15.802.4387",
              "participants":[{
                "puuid":"puuid-a","riotIdGameName":"赌书消得泼茶香","riotIdTagline":"iKun",
                "championId":103,"teamId":100,"teamPosition":"TOP",
                "kills":5,"deaths":2,"assists":5,"win":true,
                "goldEarned":13000,"totalMinionsKilled":150,"neutralMinionsKilled":30,
                "item0":6653,"item1":3078,"item2":0,"item3":0,"item4":0,"item5":0,"item6":0,
                "summoner1Id":4,"summoner2Id":12,"totalDamageDealtToChampions":25000}],
              "teams":[{"teamId":100,"win":true,
                "objectives":{"champion":{"first":true},"tower":{"kills":9,"first":false},
                  "inhibitor":{"kills":2},"baron":{"kills":1},"dragon":{"kills":2},"riftHerald":{"kills":1}}}]}}
            """;

    /** MATCH-V5 timeline JSON：frames 包裹在 info 下（与 LCU 裸数组格式的主要差异），帧内字段一致 */
    private static final String TIMELINE = """
            {"metadata":{"matchId":"TW2_111222333"},"info":{"gameId":111222333,"frames":[
              {"timestamp":60000,
               "participantFrames":{"1":{"participantId":1,"totalGold":6000,"position":{"x":1000,"y":2000}}},
               "events":[]},
              {"timestamp":120000,
               "participantFrames":{"1":{"participantId":1,"totalGold":7000,"position":{"x":1100,"y":2100}}},
               "events":[{"type":"CHAMPION_KILL","timestamp":65000,"killerId":1,"victimId":2,
                 "assistingParticipantIds":[],"position":{"x":3000,"y":4000}}]}]}}
            """;

    private CloseableHttpClient httpClient;
    private MatchIngestService matchIngestService;
    private MatchMapper matchMapper;
    private TeamRosterService rosterService;
    private com.leagueakari.match.MatchTimelineService timelineService;
    private RiotMatchHistoryService service;

    /** 模拟 Riot 接口响应（getContent 每次返回新流） */
    private CloseableHttpResponse mockResponse(int status, String body) throws Exception {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getCode()).thenReturn(status);
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenAnswer(inv ->
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);
        return response;
    }

    @BeforeEach
    void setUp() {
        httpClient = mock(CloseableHttpClient.class);
        matchIngestService = mock(MatchIngestService.class);
        matchMapper = mock(MatchMapper.class);
        rosterService = mock(TeamRosterService.class);
        // 统一出口（包 mock HttpClient；限流器不干扰——窗口内 1000 配额）
        com.leagueakari.riot.RiotHttpClient riotHttpClient = new com.leagueakari.riot.RiotHttpClient(
                "test-key", httpClient, new ObjectMapper(),
                new com.leagueakari.riot.RiotRateLimiter(1000, 120_000,
                        System::currentTimeMillis, ms -> { throw new AssertionError("不应触发限流"); }));
        timelineService = mock(com.leagueakari.match.MatchTimelineService.class);
        when(timelineService.getTimeline(any())).thenReturn(null);
        service = new RiotMatchHistoryService(
                "test-key", "https://sea.api.riotgames.com", "TW2",
                100, 200, new ObjectMapper(),
                matchIngestService, matchMapper, rosterService, timelineService, riotHttpClient,
                // 直通执行器：startBackfill 在当前线程同步执行，便于断言
                Runnable::run);
    }

    /** 用例：按 puuid 拉取 ID 列表（第二页为空即停止）→ 逐局取详情 → 转换入库 */
    @Test
    void backfillMember_fetchesAndConvertsMatches() throws Exception {
        // ID 列表第一页 1 条（Riot matchId 数字后缀即 gameId），不足一页即终止分页；
        // 依次返回：ID 列表页 → 对局详情
        CloseableHttpResponse idsPage1 = mockResponse(200, "[\"TW2_111222333\"]");
        CloseableHttpResponse detail = mockResponse(200, MATCH_DETAIL);
        when(httpClient.execute(any(HttpGet.class))).thenReturn(idsPage1, detail);
        // 库内均不存在（幂等预检查不命中）
        when(matchMapper.selectCount(any())).thenReturn(0L);

        int synced = service.backfillMember("puuid-a");

        assertThat(synced).isEqualTo(1);
        // 转换契约：MATCH-V5 字段 → MatchSyncRequest
        ArgumentCaptor<MatchSyncRequest> captor = ArgumentCaptor.forClass(MatchSyncRequest.class);
        verify(matchIngestService).saveMatch(captor.capture());
        MatchSyncRequest req = captor.getValue();
        assertThat(req.getGameId()).isEqualTo(111222333L);
        assertThat(req.getSelfPuuid()).isEqualTo("puuid-a");
        assertThat(req.getDataSource()).isEqualTo("riot-api");
        assertThat(req.getWinnerTeamId()).isEqualTo(100);
        assertThat(req.getParticipants()).hasSize(1);
        var p = req.getParticipants().get(0);
        assertThat(p.getSummonerName()).isEqualTo("赌书消得泼茶香#iKun");
        assertThat(p.getCs()).isEqualTo(180);
        assertThat(p.getItems()).containsExactly(6653, 3078);
        assertThat(p.getSummonerSpells()).containsExactly(4, 12);
        assertThat(req.getTeams().get(0).getTowerKills()).isEqualTo(9);
        assertThat(req.getTeams().get(0).getFirstBlood()).isTrue();
    }

    /** 用例：已入库的对局直接跳过详情拉取（省 Riot 配额），saveMatch 不被调用 */
    @Test
    void backfillMember_skipsMatchesAlreadyStored() throws Exception {
        CloseableHttpResponse idsPage1 = mockResponse(200, "[\"TW2_1\"]");
        CloseableHttpResponse idsPage2 = mockResponse(200, "[]");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(idsPage1, idsPage2);
        // 幂等预检查命中：该对局已在库
        when(matchMapper.selectCount(any())).thenReturn(1L);

        int synced = service.backfillMember("puuid-a");

        assertThat(synced).isZero();
        verify(matchIngestService, never()).saveMatch(any());
    }

    /** 用例：全量回填入口——逐成员回填；单个成员失败不阻断其他成员 */
    @Test
    void runBackfill_processesAllMembersDespiteIndividualFailure() throws Exception {
        when(rosterService.requireMembers()).thenReturn(List.of(
                new TeamRosterService.RosterMember("A#t",
                        new java.util.LinkedHashSet<>(List.of("puuid-a")), "puuid-a"),
                new TeamRosterService.RosterMember("B#t",
                        new java.util.LinkedHashSet<>(List.of("puuid-b")), "puuid-b")));
        // 成员 A：ID 列表接口直接 500（模拟 Riot 异常）；成员 B：一局正常
        CloseableHttpResponse broken = mockResponse(500, "boom");
        CloseableHttpResponse ids = mockResponse(200, "[\"TW2_9\"]");
        CloseableHttpResponse idsEmpty = mockResponse(200, "[]");
        CloseableHttpResponse detail = mockResponse(200, MATCH_DETAIL);
        when(httpClient.execute(any(HttpGet.class)))
                .thenReturn(broken, ids, idsEmpty, detail);
        when(matchMapper.selectCount(any())).thenReturn(0L);

        int synced = service.runBackfillSync();

        // A 失败被跳过，B 正常回填 1 局
        assertThat(synced).isEqualTo(1);
        verify(matchIngestService, times(1)).saveMatch(any());
    }

    /** 用例：roster 未配置时入口直接参数异常（400 语义） */
    @Test
    void runBackfill_throwsWhenRosterNotConfigured() {
        when(rosterService.requireMembers())
                .thenThrow(new BizException(ErrorCode.ROSTER_NOT_CONFIGURED));

        assertThatTeamNotConfigured();
    }

    private void assertThatTeamNotConfigured() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runBackfillSync())
                .isInstanceOf(BizException.class)
                .hasMessageContaining("车队名单未配置");
    }

    /** 用例（工单 #34）：新入库对局顺带拉取时间线——帧从 info.frames 解包后存储（与 LCU 裸数组格式统一） */
    @Test
    void backfillMember_fetchesTimelineForNewMatch() throws Exception {
        CloseableHttpResponse idsPage1 = mockResponse(200, "[\"TW2_111222333\"]");
        CloseableHttpResponse detail = mockResponse(200, MATCH_DETAIL);
        CloseableHttpResponse timeline = mockResponse(200, TIMELINE);
        when(httpClient.execute(any(HttpGet.class))).thenReturn(idsPage1, detail, timeline);
        when(matchMapper.selectCount(any())).thenReturn(0L);

        service.backfillMember("puuid-a");

        // 时间线解包 info.frames 存入（下游 replay 引擎按裸帧数组消费，与 LCU 路径格式统一）
        ArgumentCaptor<Object> framesCaptor = ArgumentCaptor.forClass(Object.class);
        verify(timelineService).saveTimeline(org.mockito.ArgumentMatchers.eq(111222333L), framesCaptor.capture());
        assertThat(framesCaptor.getValue().toString()).contains("participantFrames").doesNotContain("\"info\"");
        // 存的是 List（帧数组），长度 2
        assertThat((java.util.List<?>) framesCaptor.getValue()).hasSize(2);
    }

    /** 用例（工单 #34）：对局已入库但缺时间线 → 补拉 timeline（不重复拉详情，省配额） */
    @Test
    void backfillMember_backfillsTimelineForStoredMatchWithoutTimeline() throws Exception {
        CloseableHttpResponse idsPage1 = mockResponse(200, "[\"TW2_111222333\"]");
        CloseableHttpResponse timeline = mockResponse(200, TIMELINE);
        when(httpClient.execute(any(HttpGet.class))).thenReturn(idsPage1, timeline);
        // 对局已在库（幂等预检查命中），时间线缺失（getTimeline 返回 null）
        when(matchMapper.selectCount(any())).thenReturn(1L);

        service.backfillMember("puuid-a");

        // 不拉详情（省配额），只补拉时间线
        verify(matchIngestService, never()).saveMatch(any());
        verify(timelineService).saveTimeline(org.mockito.ArgumentMatchers.eq(111222333L), any());
    }

    /** 用例（工单 #34）：对局已入库且时间线已存在 → 零时间线调用（双条件幂等，跨成员不重复拉） */
    @Test
    void backfillMember_skipsTimelineWhenAlreadyStored() throws Exception {
        CloseableHttpResponse idsPage1 = mockResponse(200, "[\"TW2_111222333\"]");
        when(httpClient.execute(any(HttpGet.class))).thenReturn(idsPage1);
        when(matchMapper.selectCount(any())).thenReturn(1L);
        // 时间线已存在
        when(timelineService.getTimeline(111222333L)).thenReturn(java.util.List.of(java.util.Map.of()));

        service.backfillMember("puuid-a");

        verify(timelineService, never()).saveTimeline(any(), any());
    }
}
