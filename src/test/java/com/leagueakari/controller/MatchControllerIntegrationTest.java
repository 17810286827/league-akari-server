package com.leagueakari.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.match.MatchSyncRequest;
import com.leagueakari.dto.match.ParticipantSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.mapper.MatchMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired
    private MatchMapper matchMapper;

    /**
     * 构造合法的对局同步请求：1 名本玩家（puuid 与 selfPuuid 一致）+ 4 名同队队友，
     * 字段与 MatchSyncRequest 契约一致。测试专用 gameId 取 9000000001~9000000003 区间，
     * 避免与真实数据冲突。
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

        // 参赛者明细：本玩家 + 4 名同队队友，直显统计字段齐全，带原始 stats 快照
        List<ParticipantSyncRequest> participants = new ArrayList<>();
        // 本玩家：puuid 与 selfPuuid 一致，列表接口据此定位 self 数据
        participants.add(buildParticipant("self-puuid-1", "PlayerOne", 103, 5, 3, 8, 212, 14800));
        // 同队 4 名队友：用于验证 teammates 摘要与队伍聚合
        participants.add(buildParticipant("teammate-1", "TeammateOne", 266, 4, 5, 6, 180, 11000));
        participants.add(buildParticipant("teammate-2", "TeammateTwo", 117, 3, 4, 7, 190, 10500));
        participants.add(buildParticipant("teammate-3", "TeammateThree", 7, 2, 6, 10, 60, 9500));
        participants.add(buildParticipant("teammate-4", "TeammateFour", 84, 1, 7, 12, 30, 8200));
        req.setParticipants(participants);
        return req;
    }

    /**
     * 构造单名参赛者：统一放入 teamId=100 队伍（winnerTeamId 同侧），
     * stats 快照含伤害/经济/补刀/标记等列表接口所需字段
     */
    private ParticipantSyncRequest buildParticipant(String puuid, String name, int championId,
                                                    int kills, int deaths, int assists,
                                                    int cs, int gold) {
        ParticipantSyncRequest p = new ParticipantSyncRequest();
        // 身份字段：puuid/召唤师名/英雄/队伍
        p.setPuuid(puuid);
        p.setSummonerName(name);
        p.setChampionId(championId);
        p.setTeamId(100);
        p.setPosition("TOP");
        // 直显统计字段
        p.setKills(kills);
        p.setDeaths(deaths);
        p.setAssists(assists);
        p.setWin(true);
        p.setGoldEarned(gold);
        p.setCs(cs);
        p.setItems(List.of(6653, 3078));
        p.setSummonerSpells(List.of(4, 12));
        // stats 全量快照：与 LCU 字段名一致，验证 stats_json 解析路径
        p.setStats(Map.of(
                "totalDamageDealtToChampions", 25430,
                "totalDamageTaken", 33200,
                "goldEarned", 14800,
                "totalMinionsKilled", 212,
                "largestMultiKill", 4,
                "turretKills", 1,
                "gameEndedInSurrender", false,
                "visionScore", 42));
        return p;
    }

    /**
     * 用例：按 summonerName 查询时 self 卡片必须填充<b>该召唤师</b>的数据
     * <p>回归场景：视角 puuid 取自按玩家过滤的投影查询——若投影只 select match_id
     * 而缺 puuid 列，viewPuuid 恒为 null，所有对局的 self 卡片退化为全零占位，
     * 前端页面查不到任何数据（Self participant missing 刷屏）。
     * 用集成测试走真实 SQL 投影锁住该契约。</p>
     */
    @Test
    void queryMatches_bySummonerName_fillsSelfWithQueriedPlayer() throws Exception {
        // 组装对局：推送者 self-puuid-1（PlayerOne）+ 红队对手"手裂鬼子"（enemy-puuid-1）
        MatchSyncRequest req = buildRequest(9000000003L);
        ParticipantSyncRequest enemy = buildParticipant(
                "enemy-puuid-1", "手裂鬼子", 57, 8, 4, 2, 160, 12000);
        // 对手在 200 队且失败（与胜方 winnerTeamId=100 相对）
        enemy.setTeamId(200);
        enemy.setWin(false);
        List<ParticipantSyncRequest> all = new ArrayList<>(req.getParticipants());
        all.add(enemy);
        req.setParticipants(all);

        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // 只按 summonerName 查询（不传 puuid）：self 卡片必须是"手裂鬼子"的数据
        mockMvc.perform(get("/api/matches")
                        .param("page", "1").param("pageSize", "10")
                        .param("summonerName", "手裂鬼子")
                        .param("startTime", "1719999999999")
                        .param("endTime", "1720000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                // self 卡片：召唤师名/英雄/胜负都是查询者（红队败方）的，而非推送者
                .andExpect(jsonPath("$.data.items[0].self.summonerName").value("手裂鬼子"))
                .andExpect(jsonPath("$.data.items[0].self.championId").value(57))
                .andExpect(jsonPath("$.data.items[0].self.kills").value(8))
                .andExpect(jsonPath("$.data.items[0].self.win").value(false))
                // 视角归属：响应的 selfPuuid 是查询者，而非对局推送者
                .andExpect(jsonPath("$.data.items[0].selfPuuid").value("enemy-puuid-1"));
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
     * 用例：新对局首次入库后 push_status 初始为 PENDING（局后播报待触发）；
     * 重复推送幂等跳过，不会重置状态列
     */
    @Test
    void postMatch_newMatchInitializesPushStatusPending() throws Exception {
        long gameId = 9000000004L;
        String body = objectMapper.writeValueAsString(buildRequest(gameId));

        // 首次推送落库
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // 状态列契约：新局初始 PENDING，其余播报字段为空
        Match saved = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", gameId));
        assertThat(saved).isNotNull();
        assertThat(saved.getPushStatus()).isEqualTo("PENDING");
        assertThat(saved.getPushImageAt()).isNull();
        assertThat(saved.getPushCommentAt()).isNull();

        // 重复推送后状态不被重置（幂等跳过路径不触碰状态列）
        mockMvc.perform(post("/api/matches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        Match afterDup = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", gameId));
        assertThat(afterDup.getPushStatus()).isEqualTo("PENDING");
    }

    /**
     * 用例：先入库一局，再分页查询应能命中，统一信封 data 内为 { items, page, pageSize, total }
     */
    @Test
    void queryMatches_returnsPagedList() throws Exception {
        // 先入库一局测试数据，供分页查询命中
        mockMvc.perform(post("/api/matches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRequest(9000000002L))))
                .andExpect(status().isOk());

        // 分页查询：契约返回 { data, page, pageSize, total }。
        // 列表接口要求玩家过滤（puuid/summonerName 二选一，缺失返回空页），
        // 这里按测试数据的 selfPuuid 过滤；用 startTime/endTime 过滤到测试数据的时间戳（1720000000000），
        // 避免真实对局按 game_creation 倒序排在 data[0]
        mockMvc.perform(get("/api/matches")
                        .param("page", "1").param("pageSize", "10")
                        .param("puuid", "self-puuid-1")
                        .param("startTime", "1719999999999")
                        .param("endTime", "1720000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.items").isArray())
                // 本玩家数据：self 由 puuid 与 selfPuuid 匹配的行填充
                .andExpect(jsonPath("$.data.items[0].self.championId").value(103))
                .andExpect(jsonPath("$.data.items[0].self.kills").value(5))
                .andExpect(jsonPath("$.data.items[0].self.win").value(true))
                // stats_json 解析路径：伤害/补刀/标记字段应取自 stats 快照
                .andExpect(jsonPath("$.data.items[0].self.totalDamage").value(25430))
                .andExpect(jsonPath("$.data.items[0].self.cs").value(212))
                .andExpect(jsonPath("$.data.items[0].self.largestMultiKill").value(4))
                .andExpect(jsonPath("$.data.items[0].self.gameEndedInSurrender").value(false))
                // 队伍聚合：kills 为 5 人直显击杀之和（5+4+3+2+1=15），非负
                .andExpect(jsonPath("$.data.items[0].teamTotals.kills").value(15))
                .andExpect(jsonPath("$.data.items[0].teamTotals.damage").value(25430 * 5))
                // 队友摘要：同队除 self 外应有 4 人
                .andExpect(jsonPath("$.data.items[0].teammates.length()").value(4))
                .andExpect(jsonPath("$.data.items[0].teammates[0].puuid").value("teammate-1"));
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

        // 详情查询：data 下含 gameId 与 participants[0].puuid（本玩家排首位）
        mockMvc.perform(get("/api/matches/{gameId}", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gameId").value(gameId))
                .andExpect(jsonPath("$.data.participants[0].puuid").value("self-puuid-1"));
    }

    /**
     * 用例：不存在的 gameId 返回统一响应（HTTP 200 + 业务码 2001，全局异常处理器契约）
     */
    @Test
    void getMatchDetail_notFound() throws Exception {
        mockMvc.perform(get("/api/matches/{gameId}", 9999999999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("对局不存在")));
    }

    /**
     * 防御用例：payload 中 teams[0].win 传字符串（LCU 归一化前的原始 'Win'/'Fail'）
     * 属于字段类型错误，应返回业务码 1001 参数错误（HTTP 200），
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }
}
