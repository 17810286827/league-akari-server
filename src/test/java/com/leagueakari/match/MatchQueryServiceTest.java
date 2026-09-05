package com.leagueakari.match;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.dto.match.MatchDetailResponse;
import com.leagueakari.dto.match.MatchSummaryResponse;
import com.leagueakari.dto.scoring.PlayerScoreView;
import com.leagueakari.dto.common.PageResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchMvpMapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import com.leagueakari.scoring.MatchMvpService;

/**
 * MatchQueryService 查询组装单元测试（对局同步子系统的读取半边）
 * <p>验证核心契约：详情（MVP/SVP 称号、全员实时评分）、
 * 列表（折叠卡聚合、视角归属、stats 缺失兜底）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MatchQueryServiceTest {

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchParticipantMapper matchParticipantMapper;

    /** MVP/SVP 评选编排服务：详情接口的全员实时评分调用其纯计算路径 */
    @Mock
    private MatchMvpService matchMvpService;

    /** 真实 Jackson 实例（spy），验证 statsJson 解析路径 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    /** MVP 评选结果查询：详情/列表接口填充 mvp/svp 字段时使用 */
    @Mock
    private MatchMvpMapper matchMvpMapper;

    /** 真实门面（spy 其内 mapper 无必要——门面是纯读逻辑，直接用真实实例） */
    @Spy
    private ParticipantStatsReader statsReader = new ParticipantStatsReader(new ObjectMapper());

    @InjectMocks
    private MatchQueryService matchQueryService;

    /**
     * 用例：详情响应填充 mvp/svp 字段——称号持有者的玩家档案与得分齐全
     */
    @Test
    void 详情响应包含MVP与SVP字段() {
        // 主表记录：game_id 命中
        Match match = new Match();
        match.setId(1L);
        match.setGameId(1000000006L);
        match.setGameMode("CLASSIC");
        match.setWinnerTeamId(100);
        when(matchMapper.selectOne(any())).thenReturn(match);

        // 参赛者明细：两人（101 胜方 MVP、102 负方 SVP）
        MatchParticipant mvpPlayer = new MatchParticipant();
        mvpPlayer.setId(101L);
        mvpPlayer.setPuuid("puuid-101");
        mvpPlayer.setSummonerName("MvpPlayer");
        mvpPlayer.setChampionId(22);
        mvpPlayer.setTeamId(100);
        mvpPlayer.setWin(true);
        MatchParticipant svpPlayer = new MatchParticipant();
        svpPlayer.setId(102L);
        svpPlayer.setPuuid("puuid-102");
        svpPlayer.setSummonerName("SvpPlayer");
        svpPlayer.setChampionId(81);
        svpPlayer.setTeamId(200);
        svpPlayer.setWin(false);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of(mvpPlayer, svpPlayer));

        // 评选结果：两条记录（MVP/SVP）
        MatchMvp mvpRecord = new MatchMvp();
        mvpRecord.setMatchId(1L);
        mvpRecord.setParticipantId(101L);
        mvpRecord.setType("MVP");
        mvpRecord.setScore(new java.math.BigDecimal("92.50"));
        MatchMvp svpRecord = new MatchMvp();
        svpRecord.setMatchId(1L);
        svpRecord.setParticipantId(102L);
        svpRecord.setType("ACE");
        svpRecord.setScore(new java.math.BigDecimal("85.00"));
        when(matchMvpMapper.selectList(any())).thenReturn(List.of(mvpRecord, svpRecord));

        // 执行详情查询
        MatchDetailResponse resp = matchQueryService.getMatchDetail(1000000006L);

        // MVP：称号 + 玩家档案 + 得分
        assertThat(resp.getMvp()).isNotNull();
        assertThat(resp.getMvp().getParticipantId()).isEqualTo(101L);
        assertThat(resp.getMvp().getSummonerName()).isEqualTo("MvpPlayer");
        assertThat(resp.getMvp().getChampionId()).isEqualTo(22);
        assertThat(resp.getMvp().getScore()).isEqualByComparingTo(new java.math.BigDecimal("92.50"));
        // ACE：同上
        assertThat(resp.getAce()).isNotNull();
        assertThat(resp.getAce().getParticipantId()).isEqualTo(102L);
        assertThat(resp.getAce().getSummonerName()).isEqualTo("SvpPlayer");
        assertThat(resp.getAce().getScore()).isEqualByComparingTo(new java.math.BigDecimal("85.00"));
    }

    /**
     * 用例：对局未评选（老数据无 match_mvp 记录）时 mvp/svp 为 null，不报错
     */
    @Test
    void 详情响应无评选记录时mvpSvp为空() {
        Match match = new Match();
        match.setId(1L);
        match.setGameId(1000000007L);
        when(matchMapper.selectOne(any())).thenReturn(match);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of());
        when(matchMvpMapper.selectList(any())).thenReturn(List.of());

        MatchDetailResponse resp = matchQueryService.getMatchDetail(1000000007L);

        assertThat(resp.getMvp()).isNull();
        assertThat(resp.getAce()).isNull();
    }

    /**
     * 用例：详情响应附带全员实时评分（playerScores 按 puuid 索引，透传 MatchMvpService 计算结果）
     */
    @Test
    void 详情响应包含全员实时评分() {
        Match match = new Match();
        match.setId(1L);
        match.setGameId(1000000009L);
        match.setGameMode("CLASSIC");
        when(matchMapper.selectOne(any())).thenReturn(match);
        MatchParticipant p = new MatchParticipant();
        p.setId(101L);
        p.setPuuid("puuid-101");
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of(p));
        when(matchMvpMapper.selectList(any())).thenReturn(List.of());
        // mock 评分服务：puuid-101 → opScore 8.88 grade 卓越
        Map<String, PlayerScoreView> scores = Map.of("puuid-101",
                PlayerScoreView.builder().opScore(8.88).grade("卓越").dimensions(Map.of()).build());
        when(matchMvpService.computeScores(any(Match.class), anyList())).thenReturn(scores);

        MatchDetailResponse resp = matchQueryService.getMatchDetail(1000000009L);

        // 透传契约：playerScores 原样返回，按 puuid 键
        assertThat(resp.getPlayerScores()).isNotNull();
        assertThat(resp.getPlayerScores()).containsKey("puuid-101");
        assertThat(resp.getPlayerScores().get("puuid-101").getOpScore()).isEqualTo(8.88);
        assertThat(resp.getPlayerScores().get("puuid-101").getGrade()).isEqualTo("卓越");
    }

    /**
     * 用例：列表响应每条对局附带 mvp/svp（一次批量查询，不逐局查库）
     */
    @Test
    void 列表响应附带mvpSvp称号() {
        // 主表：1 局对局
        Match match = new Match();
        match.setId(1L);
        match.setGameId(1000000010L);
        match.setGameCreation(1720000000000L);
        match.setGameDuration(1800);
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

        // 参赛者：两次 selectList（过滤定位 + 批量加载）都返回同一人
        MatchParticipant p = new MatchParticipant();
        p.setMatchId(1L);
        p.setId(101L);
        p.setPuuid("self-puuid-1");
        p.setSummonerName("PlayerOne");
        p.setChampionId(22);
        p.setTeamId(100);
        p.setWin(true);
        when(matchParticipantMapper.selectList(any())).thenReturn(List.of(p));

        // 评选记录：MVP 一条
        MatchMvp mvpRecord = new MatchMvp();
        mvpRecord.setMatchId(1L);
        mvpRecord.setParticipantId(101L);
        mvpRecord.setType("MVP");
        mvpRecord.setScore(new java.math.BigDecimal("92.50"));
        when(matchMvpMapper.selectList(any())).thenReturn(List.of(mvpRecord));

        PageResponse<MatchSummaryResponse> resp =
                matchQueryService.pageMatches(1, 10, null, "self-puuid-1", null, null, null);

        // 列表项契约：mvp 称号携带玩家档案与得分
        MatchSummaryResponse item = resp.getItems().get(0);
        assertThat(item.getMvp()).isNotNull();
        assertThat(item.getMvp().getParticipantId()).isEqualTo(101L);
        assertThat(item.getMvp().getSummonerName()).isEqualTo("PlayerOne");
        assertThat(item.getMvp().getScore()).isEqualByComparingTo(new java.math.BigDecimal("92.50"));
        assertThat(item.getAce()).isNull();
        // 批量契约：评选查询只发起一次（matchId IN），不逐局 N+1
        verify(matchMvpMapper, times(1)).selectList(any());
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
        PageResponse<MatchSummaryResponse> resp = matchQueryService.pageMatches(1, 10, null, "self-puuid-1", null, null, null);
        MatchSummaryResponse item = resp.getItems().get(0);

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
                matchQueryService.pageMatches(1, 10, null, null, null, null, null);

        // 空页契约：data 空列表、total 0，且不抛错
        assertThat(resp.getItems()).isEmpty();
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
        PageResponse<MatchSummaryResponse> resp = matchQueryService.pageMatches(1, 10, null, "self-puuid-1", null, null, null);
        MatchSummaryResponse item = resp.getItems().get(0);

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
     * 用例：列表 self 增强跟随查询者 puuid（视角归属），而非对局推送者 self_puuid。
     * <p>场景：所有对局由推送者（如 iKun）客户端同步，match.self_puuid 全是同一人；
     * 其他玩家查询自己战绩时，self 卡片（召唤师名/英雄/KDA）必须展示查询者本人的数据，
     * 否则任何用户查战绩都看到推送者的信息（视角串号）。</p>
     */
    @Test
    void 列表self增强跟随查询者puuid而非对局推送者() {
        // 主表：1 局对局，推送者（记录者）是 ikun-puuid
        Match match = new Match();
        match.setId(1L);
        match.setGameId(1000000008L);
        match.setGameCreation(1720000000000L);
        match.setGameDuration(1800);
        match.setGameMode("CLASSIC");
        match.setMapId(11);
        match.setQueueId(420);
        match.setRegion("na1");
        match.setWinnerTeamId(100);
        match.setSelfPuuid("ikun-puuid");
        Page<Match> page = new Page<>(1, 10);
        page.setRecords(List.of(match));
        page.setTotal(1);
        when(matchMapper.selectPage(any(), any())).thenReturn(page);

        // 参与者：推送者 ikun（100 队）与查询者 other（200 队），两人都在场
        MatchParticipant ikun = new MatchParticipant();
        ikun.setMatchId(1L);
        ikun.setPuuid("ikun-puuid");
        ikun.setSummonerName("IKun");
        ikun.setChampionId(22);
        ikun.setTeamId(100);
        ikun.setKills(10);
        ikun.setDeaths(1);
        ikun.setAssists(5);
        ikun.setWin(true);
        ikun.setGoldEarned(15000);
        ikun.setCs(280);
        ikun.setStatsJson("{\"item0\":1055,\"totalDamageDealtToChampions\":45000}");
        MatchParticipant other = new MatchParticipant();
        other.setMatchId(1L);
        other.setPuuid("other-puuid");
        other.setSummonerName("OtherPlayer");
        other.setChampionId(57);
        other.setTeamId(200);
        other.setKills(3);
        other.setDeaths(4);
        other.setAssists(8);
        other.setWin(false);
        other.setGoldEarned(9000);
        other.setCs(150);
        other.setStatsJson("{\"item0\":1055,\"totalDamageDealtToChampions\":12000}");

        // 第一次 selectList：按 puuid 过滤定位查询者参与的对局（返回查询者行）
        // 第二次 selectList：batchLoadParticipants 批量加载本页对局全部参与者
        when(matchParticipantMapper.selectList(any()))
                .thenReturn(List.of(other))
                .thenReturn(List.of(ikun, other));

        // 查询者视角查询战绩
        PageResponse<MatchSummaryResponse> resp =
                matchQueryService.pageMatches(1, 10, null, "other-puuid", null, null, null);
        MatchSummaryResponse item = resp.getItems().get(0);

        // 断言：self 卡片是查询者本人的数据，而不是推送者 ikun 的
        assertThat(item.getSelfPuuid()).isEqualTo("other-puuid");
        assertThat(item.getSelf().getSummonerName()).isEqualTo("OtherPlayer");
        assertThat(item.getSelf().getChampionId()).isEqualTo(57);
        assertThat(item.getSelf().getKills()).isEqualTo(3);
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

    /**
     * 用例：对局存在性断言放行——主表有记录（exists=true）时不抛任何异常
     * <p>轻量语义：仅一次 exists 探存（SELECT ... LIMIT 1），不得触发详情组装
     * （参赛者批量加载 / MVP 评选 / 全员评分都是重路径，前置校验用不起）。</p>
     */
    @Test
    void 存在性断言_对局存在时放行() {
        // 主表 exists 命中：对局已同步入库
        when(matchMapper.exists(any())).thenReturn(true);

        // 不抛即通过；除 exists 外不应有任何其他 mapper 交互
        matchQueryService.assertExists(1000000006L);
        verify(matchMapper, times(1)).exists(any());
        verifyNoMoreInteractions(matchMapper);
    }

    /**
     * 用例：对局不存在时抛 BizException(MATCH_NOT_FOUND, 2001)
     * <p>错误语义与 {@code getMatchDetail} 完全一致（同一文案、同一业务码），
     * 调用方（如 AI 分析前置校验）换用本方法后对外契约不变。</p>
     */
    @Test
    void 存在性断言_对局不存在时抛业务异常() {
        // 主表 exists 未命中：gameId 不存在
        when(matchMapper.exists(any())).thenReturn(false);

        assertThatThrownBy(() -> matchQueryService.assertExists(123L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("对局不存在")
                .satisfies(e -> assertThat(((BizException) e).getErrorCode().getCode()).isEqualTo(2001));
    }
}
