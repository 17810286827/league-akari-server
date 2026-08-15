package com.leagueakari.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.ParticipantSyncRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MatchController 集成测试：真实写入虚拟机 MySQL，覆盖幂等同步、分页查询、
 * 详情查询与 404 异常四类核心契约。
 * <p>说明：@Transactional 使每个用例结束后回滚，不污染数据库；
 * 幂等用例依赖同事务内对插入数据的可见性，Spring 事务默认传播下成立。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MatchControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 构造合法的对局同步请求：单名参赛者，字段与 MatchSyncRequest 契约一致。
     * 测试专用 gameId 取 9000000001~9000000003 区间，避免与真实数据冲突。
     */
    private MatchSyncRequest buildRequest(long gameId) {
        MatchSyncRequest req = new MatchSyncRequest();
        // 主表直显字段：gameId 为幂等键，其余与同步 DTO 契约一一对应
        req.setGameId(gameId);
        req.setGameCreation(1720000000000L);
        req.setGameDuration(1830);
        req.setGameMode("CLASSIC");
        req.setGameType("MATCHED_GAME");
        req.setQueueId(420);
        req.setMapId(11);
        req.setGameVersion("25.4.1");
        req.setRegion("na1");
        req.setRsoPlatformId("");
        req.setDataSource("lcu");
        req.setWinnerTeamId(100);
        req.setSelfPuuid("self-puuid-1");

        // 参赛者明细：直显统计字段齐全，带原始 stats 快照
        ParticipantSyncRequest p = new ParticipantSyncRequest();
        p.setPuuid("player-1");
        p.setSummonerName("PlayerOne");
        p.setChampionId(103);
        p.setTeamId(100);
        p.setPosition("TOP");
        p.setKills(5);
        p.setDeaths(3);
        p.setAssists(8);
        p.setWin(true);
        p.setGoldEarned(12800);
        p.setCs(210);
        p.setItems(List.of(6653, 3078));
        p.setSummonerSpells(List.of(4, 12));
        p.setStats(Map.of("totalDamageDealtToChampions", 25430, "visionScore", 42));
        req.setParticipants(List.of(p));
        return req;
    }

    /**
     * 用例：重复推送同一 gameId 均返回 200 且 code=0，验证服务端幂等跳过
     */
    @Test
    void postMatch_isIdempotent() throws Exception {
        String body = objectMapper.writeValueAsString(buildRequest(9000000001L));

        // 首次推送：应成功落库
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 重复推送同样返回 200，不报错
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * 用例：先入库一局，再分页查询应能命中，且响应带 total 与 data 数组
     */
    @Test
    void queryMatches_returnsPagedList() throws Exception {
        // 先入库一局测试数据，供分页查询命中
        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(9000000002L))))
                .andExpect(status().isOk());

        // 分页查询：契约返回 { data, page, pageSize, total }
        mockMvc.perform(get("/api/matches").param("page", "1").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * 用例：详情查询应返回主表字段与参赛者列表（含 puuid）
     */
    @Test
    void getMatchDetail_returnsParticipants() throws Exception {
        long gameId = 9000000003L;
        // 先入库一局测试数据，供详情查询命中
        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(gameId))))
                .andExpect(status().isOk());

        // 详情查询：data 下含 gameId 与 participants[0].puuid
        mockMvc.perform(get("/api/matches/{gameId}", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gameId").value(gameId))
                .andExpect(jsonPath("$.data.participants[0].puuid").value("player-1"));
    }

    /**
     * 用例：不存在的 gameId 应返回 404，且响应体 code=404（全局异常处理器契约）
     */
    @Test
    void getMatchDetail_notFound() throws Exception {
        mockMvc.perform(get("/api/matches/{gameId}", 9999999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 防御用例：payload 中 teams[0].win 传字符串（LCU 归一化前的原始 'Win'/'Fail'）
     * 属于字段类型错误，应返回 400 参数错误而非 500，
     * 防止跨端类型断链（Electron 侧未归一化时）导致默认 LCU 路径同步必败
     */
    @Test
    void postMatch_withStringWin_returns400() throws Exception {
        // 手工拼接 JSON 字符串：win 字段故意传 "Win" 而非布尔值
        String body = """
                {"gameId":9000000009,"gameCreation":1720000000000,"gameDuration":1830,"gameMode":"CLASSIC",\
                "gameType":"MATCHED_GAME","queueId":420,"mapId":11,"gameVersion":"25.4.1","region":"na1",\
                "rsoPlatformId":"","dataSource":"lcu","winnerTeamId":100,"selfPuuid":"self-puuid-1",\
                "teams":[{"teamId":100,"win":"Win","towerKills":11,"inhibitorKills":2,"baronKills":1,\
                "dragonKills":3,"riftHeraldKills":1,"firstBlood":true,"firstTower":true}],\
                "participants":[{"puuid":"player-1","summonerName":"PlayerOne","championId":103,"teamId":100,\
                "position":"TOP","kills":5,"deaths":3,"assists":8,"win":true,"goldEarned":12800,"cs":210,\
                "items":[6653,3078],"summonerSpells":[4,12],\
                "stats":{"totalDamageDealtToChampions":25430,"visionScore":42}}]}
                """;
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
