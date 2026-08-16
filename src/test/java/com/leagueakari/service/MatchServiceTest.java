package com.leagueakari.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leagueakari.dto.MatchSummaryResponse;
import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.PageResponse;
import com.leagueakari.dto.ParticipantSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MatchService 幂等保存单元测试
 * <p>验证两条核心契约：
 * 1. game_id 不存在时插入 match 与参赛者；
 * 2. game_id 已存在时跳过，不产生任何写入。
 */
@ExtendWith(MockitoExtension.class)
class MatchServiceTest {

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchParticipantMapper matchParticipantMapper;

    /** 真实 Jackson 实例（spy），验证 teamsJson/statsJson 序列化路径 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MatchService matchService;

    /**
     * 构造一个合法的对局同步请求：单名参赛者，带原始 stats 对象
     */
    private MatchSyncRequest buildRequest(long gameId) {
        MatchSyncRequest req = new MatchSyncRequest();
        // 幂等键：LCU 对局 ID，服务端据此查重
        req.setGameId(gameId);
        // 主表直显字段（模式/时长/队列等），与 V1__init.sql 列一一对应
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

        // 参赛者：kills/deaths/assists 等直显字段齐全
        ParticipantSyncRequest p = new ParticipantSyncRequest();
        p.setPuuid("player-1");
        p.setSummonerName("PlayerOne");
        p.setChampionId(103);
        p.setTeamId(100);
        p.setKills(5);
        p.setDeaths(3);
        p.setAssists(8);
        p.setWin(true);
        p.setGoldEarned(12800);
        p.setCs(210);
        // 原始 stats 全量对象（与 LCU/SGP 字段名一致），整体存入 stats_json
        p.setStats(Map.of("totalDamageDealtToChampions", 25430));
        req.setParticipants(List.of(p));
        return req;
    }

    /**
     * 用例：game_id 不存在时，应插入 match 主表与参赛者各一次
     */
    @Test
    void saveMatch_insertsWhenGameIdAbsent() {
        // 模拟查重结果：0 表示该对局不存在
        when(matchMapper.selectCount(any())).thenReturn(0L);

        // 执行被测方法：幂等保存
        matchService.saveMatch(buildRequest(1000000001L));

        // 断言 match 与参赛者均只插入一次
        verify(matchMapper, times(1)).insert(any(Match.class));
        verify(matchParticipantMapper, times(1)).insert(any(MatchParticipant.class));
    }

    /**
     * 用例：game_id 已存在时，幂等跳过，任何 mapper 都不应产生写入
     */
    @Test
    void saveMatch_skipsWhenGameIdExists() {
        // 模拟查重结果：1 表示该对局已入库
        when(matchMapper.selectCount(any())).thenReturn(1L);

        // 执行被测方法：幂等保存
        matchService.saveMatch(buildRequest(1000000002L));

        // 断言幂等跳过：match 与参赛者均未插入
        verify(matchMapper, never()).insert(any(Match.class));
        verify(matchParticipantMapper, never()).insert(any(MatchParticipant.class));
    }

    /**
     * 用例：列表响应包含 10 人轻量参与者档案与 self 增强字段（折叠卡数据）。
     * <p>覆盖双数据源：0 号位（self）为 LCU 平铺 perks（perk0-5 在 stats 顶层），
     * 5 号位为 SGP 嵌套 perks（perks 对象含 perkIds/perkStyle/perkSubStyle）。</p>
     */
    @Test
    void 列表响应包含轻量参与者与self增强字段() {
        // 构造分页结果：单条对局主表记录，self_puuid 指向 0 号位参赛者
        Match match = new Match();
        match.setId(1L);
        match.setGameId(1000000003L);
        match.setGameCreation(1720000000000L);
        match.setGameDuration(1830);
        match.setGameMode("CLASSIC");
        match.setMapId(11);
        match.setQueueId(420);
        match.setRegion("na1");
        match.setWinnerTeamId(100);
        match.setSelfPuuid("self-puuid-1");
        Page<Match> page = new Page<>(1, 10);
        page.setRecords(List.of(match));
        page.setTotal(1);
        when(matchMapper.selectPage(any(), any())).thenReturn(page);

        // 10 名参赛者 fixture：0 号位 self（LCU 平铺）、5 号位 SGP 嵌套 perks
        List<MatchParticipant> participants = buildParticipantsFixture();
        when(matchParticipantMapper.selectList(any())).thenReturn(participants);

        // 执行分页查询，取唯一一条列表项
        PageResponse<MatchSummaryResponse> resp = matchService.pageMatches(1, 10, null, "self-puuid-1", null, null, null);
        MatchSummaryResponse item = resp.getData().get(0);

        // self 增强字段：出装 7 槽、海克斯 6 槽、召唤师技能 2 槽、三杀 1 次
        assertThat(item.getSelf().getItems()).hasSize(7);
        assertThat(item.getSelf().getAugments()).hasSize(6);
        assertThat(item.getSelf().getSummonerSpells()).hasSize(2);
        assertThat(item.getSelf().getTripleKills()).isEqualTo(1);
        // mapId 透传主表真实值（折叠卡塔杀标签按地图口径计算）
        assertThat(item.getMapId()).isEqualTo(11);
        // 10 人轻量档案全量返回（含 self，前端以 puuid 区分）
        assertThat(item.getParticipants()).hasSize(10);
        // LCU 平铺 perks：perkStyle 8100、perkIds 6 颗
        assertThat(item.getParticipants().get(0).getPerks().getPerkStyle()).isEqualTo(8100);
        assertThat(item.getParticipants().get(0).getPerks().getPerkIds()).hasSize(6);
        // SGP 嵌套 perks：同样解析出 perkStyle 8100 与 6 颗 perkIds
        assertThat(item.getParticipants().get(5).getPerks().getPerkStyle()).isEqualTo(8100);
        assertThat(item.getParticipants().get(5).getPerks().getPerkIds()).hasSize(6);
        // 折叠卡统计/雷达图字段：从 statsJson 提取（fixture 0 号位含伤害/承伤/治疗等）
        assertThat(item.getParticipants().get(0).getTotalDamageDealtToChampions()).isEqualTo(45000);
        assertThat(item.getParticipants().get(0).getTotalDamageTaken()).isEqualTo(33200);
        assertThat(item.getParticipants().get(0).getTotalHeal()).isEqualTo(9200);
        assertThat(item.getParticipants().get(0).getGoldEarned()).isEqualTo(12800);
        assertThat(item.getParticipants().get(0).getCs()).isEqualTo(210);
        assertThat(item.getParticipants().get(0).getWardsPlaced()).isEqualTo(16);
        // 折叠卡成就标签字段：多杀/拆塔读 stats 顶层，单杀/塔杀等读 challenges
        assertThat(item.getParticipants().get(0).getDoubleKills()).isEqualTo(5);
        assertThat(item.getParticipants().get(0).getTripleKills()).isEqualTo(1);
        assertThat(item.getParticipants().get(0).getQuadraKills()).isEqualTo(1);
        assertThat(item.getParticipants().get(0).getPentaKills()).isZero();
        assertThat(item.getParticipants().get(0).getTotalDamageToTowers()).isEqualTo(4600);
        assertThat(item.getParticipants().get(0).getTotalDamageShieldedOnTeammates()).isEqualTo(1200);
        assertThat(item.getParticipants().get(0).getTimeCCingOthers()).isEqualTo(30);
        assertThat(item.getParticipants().get(0).getSoloKills()).isEqualTo(3);
        assertThat(item.getParticipants().get(0).getKillsNearEnemyTurret()).isEqualTo(5);
        assertThat(item.getParticipants().get(0).getKillsUnderOwnTurret()).isEqualTo(2);
        assertThat(item.getParticipants().get(0).getMaxCsAdvantageOnLaneOpponent()).isEqualTo(42);
        assertThat(item.getParticipants().get(0).getKnockEnemyIntoTeamAndKill()).isEqualTo(7);
        // 最近对手：列表查询时聚合（红队 5 人，self 蓝队之外的玩家）
        assertThat(resp.getRecentOpponents()).hasSize(5);
        assertThat(resp.getRecentOpponents().get(0).getSummonerName()).isEqualTo("Player5");
        assertThat(resp.getRecentOpponents().get(0).getWins()).isZero();
        assertThat(resp.getRecentOpponents().get(0).getLosses()).isEqualTo(1);
    }

    /**
     * 用例：未提供 puuid 时列表接口返回空页（只允许查询指定玩家的对局），
     * 不触发任何对局查询，避免暴露全量数据
     */
    @Test
    void 未提供puuid时返回空页() {
        // 不 mock 任何 Mapper：若实现误查库会因 mock 默认返回 null/空而暴露，此处直接断言空页
        PageResponse<MatchSummaryResponse> resp =
                matchService.pageMatches(1, 10, null, null, null, null, null);

        // 空页契约：data 空列表、total 0，且不抛错
        assertThat(resp.getData()).isEmpty();
        assertThat(resp.getTotal()).isZero();
        assertThat(resp.getRecentOpponents()).isNull();
    }

    /**
     * 用例：参赛者 statsJson 缺失（null）时，self 增强字段与 participants 轻量档案
     * 的 perks 应按"缺失写空列表/0"契约兜底，输出空 perkIds + 样式 0，而非 null
     */
    @Test
    void statsJson缺失时perks与列表字段兜底为空() {
        // 构造分页结果：单条对局主表记录，self_puuid 指向唯一参赛者
        Match match = new Match();
        match.setId(1L);
        match.setGameId(1000000004L);
        match.setGameCreation(1720000000000L);
        match.setGameDuration(1830);
        match.setGameMode("CLASSIC");
        match.setQueueId(420);
        match.setRegion("na1");
        match.setWinnerTeamId(100);
        match.setSelfPuuid("self-puuid-1");
        Page<Match> page = new Page<>(1, 10);
        page.setRecords(List.of(match));
        page.setTotal(1);
        when(matchMapper.selectPage(any(), any())).thenReturn(page);

        // 单名参赛者：statsJson 为 null，模拟 stats 快照缺失的数据
        MatchParticipant p = new MatchParticipant();
        p.setMatchId(1L);
        p.setPuuid("self-puuid-1");
        p.setSummonerName("PlayerOne");
        p.setChampionId(103);
        p.setTeamId(100);
        p.setKills(1);
        p.setDeaths(0);
        p.setAssists(0);
        p.setWin(true);
        p.setStatsJson(null); // stats 快照缺失
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of(p));

        // 执行分页查询，取唯一一条列表项
        PageResponse<MatchSummaryResponse> resp = matchService.pageMatches(1, 10, null, "self-puuid-1", null, null, null);
        MatchSummaryResponse item = resp.getData().get(0);

        // self 兜底契约：出装/技能/海克斯为空列表，perks 为空 perkIds + 样式 0
        assertThat(item.getSelf().getItems()).isEmpty();
        assertThat(item.getSelf().getSummonerSpells()).isEmpty();
        assertThat(item.getSelf().getAugments()).isEmpty();
        assertThat(item.getSelf().getPerks().getPerkIds()).isEmpty();
        assertThat(item.getSelf().getPerks().getPerkStyle()).isZero();
        assertThat(item.getSelf().getPerks().getPerkSubStyle()).isZero();
        // participants 轻量档案同样兜底：perks 非 null 且为空结构
        assertThat(item.getParticipants()).hasSize(1);
        assertThat(item.getParticipants().get(0).getPerks().getPerkIds()).isEmpty();
        assertThat(item.getParticipants().get(0).getPerks().getPerkStyle()).isZero();
        assertThat(item.getParticipants().get(0).getPerks().getPerkSubStyle()).isZero();
    }

    /**
     * 构造 10 名参赛者 fixture：0 号位为 self（LCU 平铺 statsJson），
     * 5 号位使用 SGP 嵌套 perks 结构，其余参赛者仅带最简 stats
     */
    private List<MatchParticipant> buildParticipantsFixture() {
        List<MatchParticipant> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MatchParticipant p = new MatchParticipant();
            p.setMatchId(1L);
            p.setPuuid("player-" + i);
            p.setSummonerName("Player" + i);
            p.setChampionId(103 + i);
            p.setTeamId(i < 5 ? 100 : 200);
            p.setPosition(i < 5 ? "TOP" : "BOTTOM");
            p.setKills(1);
            p.setDeaths(2);
            p.setAssists(3);
            p.setWin(i < 5);
            if (i == 0) {
                // self 行：puuid 与主表 self_puuid 一致，statsJson 为 LCU 平铺结构
                p.setPuuid("self-puuid-1");
                p.setStatsJson(lcuFlatStatsJson());
            } else if (i == 5) {
                // SGP 透传：perks 为嵌套对象（perkIds/perkStyle/perkSubStyle）
                p.setStatsJson(sgpNestedPerksJson());
            } else {
                // 其余参赛者：最简 stats，仅出装一个槽位
                p.setStatsJson("{\"item0\":1055}");
            }
            list.add(p);
        }
        return list;
    }

    /**
     * LCU 平铺 statsJson fixture：item0-6 / spell1Id / spell2Id / playerAugment1-6 /
     * perk0-5 / perkPrimaryStyle / perkSubStyle / 多杀字段均在顶层
     */
    private String lcuFlatStatsJson() {
        return "{\"item0\":3157,\"item1\":3089,\"item2\":3020,\"item3\":3135,\"item4\":3152,\"item5\":3340,\"item6\":3364,"
                + "\"spell1Id\":4,\"spell2Id\":12,"
                + "\"playerAugment1\":1,\"playerAugment2\":2,\"playerAugment3\":3,\"playerAugment4\":4,\"playerAugment5\":5,\"playerAugment6\":6,"
                + "\"perk0\":8112,\"perk1\":8128,\"perk2\":8009,\"perk3\":8138,\"perk4\":8304,\"perk5\":8316,"
                + "\"perkPrimaryStyle\":8100,\"perkSubStyle\":8300,"
                + "\"doubleKills\":2,\"tripleKills\":1,\"quadraKills\":0,\"pentaKills\":0,"
                // 折叠卡统计/雷达图字段：伤害/承伤/治疗/视野/金币/补刀/推塔/插眼（缺失按 0）
                + "\"totalDamageDealtToChampions\":45000,\"totalDamageTaken\":33200,\"totalHeal\":9200,\"visionScore\":42,"
                + "\"goldEarned\":12800,\"totalMinionsKilled\":210,\"turretKills\":3,\"wardsPlaced\":16,"
                // 折叠卡成就标签字段：多杀/拆塔/护盾/控制 + SGP challenges（单杀/塔杀/补刀压制/击飞）
                + "\"doubleKills\":5,\"tripleKills\":1,\"quadraKills\":1,\"pentaKills\":0,"
                + "\"damageDealtToTurrets\":4600,\"totalDamageShieldedOnTeammates\":1200,\"timeCCingOthers\":30,"
                + "\"challenges\":{\"soloKills\":3,\"killsNearEnemyTurret\":5,\"killsUnderOwnTurret\":2,"
                + "\"maxCsAdvantageOnLaneOpponent\":42,\"knockEnemyIntoTeamAndKill\":7}}";
    }

    /**
     * SGP 嵌套 perks statsJson fixture：perks 为对象（perkIds/perkStyle/perkSubStyle），
     * 验证双路径探测的嵌套分支
     */
    private String sgpNestedPerksJson() {
        return "{\"item0\":3157,\"item1\":3089,\"item2\":3020,\"item3\":3135,\"item4\":3152,\"item5\":3340,\"item6\":3364,"
                + "\"perks\":{\"perkIds\":[8112,8128,8009,8138,8304,8316],\"perkStyle\":8100,\"perkSubStyle\":8300}}";
    }
}
