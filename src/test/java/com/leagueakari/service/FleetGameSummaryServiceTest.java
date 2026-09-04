package com.leagueakari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FleetGameSummaryService 单元测试（一局摘要构造层，口径断言的集中地）：
 * 验证车队视角的单局全量事实——主队判定、比分（双方击杀合计）、
 * 车队成员置前 + 击杀降序、称号语义（主队 MVP/尽力，对方 MVP）、
 * 资源快照与 stats 数值（缺失补 0）。战报图与锐评是本摘要的两个投影，
 * 口径只存在这一处。不触网不触库。
 */
class FleetGameSummaryServiceTest {

    private final GameDataService gameData = mock(GameDataService.class);
    private final TeamProperties teamProps = mock(TeamProperties.class);
    private final FleetGameSummaryService service =
            new FleetGameSummaryService(gameData, new ObjectMapper(), teamProps);

    /** stats_json 片段：伤害/承伤/金币（Riot v5 键名） */
    private static String stats(int dmg, int taken, int gold) {
        return "{\"totalDamageDealtToChampions\":" + dmg
                + ",\"totalDamageTaken\":" + taken
                + ",\"goldEarned\":" + gold + "}";
    }

    /** 构造参赛者；puuid 与 memberByPuuid 命中即为车队成员 */
    private MatchParticipant part(long id, String name, int champion, int team, int k, int d, int a,
                                 String statsJson) {
        MatchParticipant p = new MatchParticipant();
        p.setId(id);
        p.setPuuid("puuid-" + id);
        p.setSummonerName(name);
        p.setChampionId(champion);
        p.setTeamId(team);
        p.setKills(k);
        p.setDeaths(d);
        p.setAssists(a);
        p.setStatsJson(statsJson);
        return p;
    }

    /** 标准 10 人局：主队(100) 4 车队成员+1 路人，对方(200) 5 人 */
    private Match match() {
        Match m = new Match();
        m.setQueueId(440);
        m.setGameDuration(28 * 60 + 42);
        m.setWinnerTeamId(100);
        return m;
    }

    private List<MatchParticipant> participants() {
        return List.of(
                part(1, "峡谷养鱼人", 64, 100, 12, 3, 7, stats(28600, 14300, 15400)),
                part(2, "夜雨听澜", 54, 100, 4, 2, 14, stats(7600, 26100, 11800)),
                part(3, "中路杀神", 103, 100, 8, 4, 10, stats(19400, 10800, 13600)),
                part(4, "盾辅阿离", 89, 100, 1, 4, 17, stats(3900, 22400, 9800)),
                part(5, "路人甲", 58, 100, 7, 9, 6, stats(15200, 19200, 14700)),
                part(6, "青衫仗剑", 59, 200, 5, 7, 3, stats(12100, 11900, 13000)),
                part(7, "别打野区", 142, 200, 9, 4, 5, stats(16500, 3600, 12400)),
                part(8, "午夜诗人", 110, 200, 4, 6, 6, stats(10300, 6900, 10100)),
                part(9, "一杯敬月光", 412, 200, 1, 7, 9, stats(5100, 13300, 8700)),
                part(10, "温柔辅助", 117, 200, 0, 3, 15, stats(2600, 10800, 8200)));
    }

    private Map<String, TeamRosterService.RosterMember> roster() {
        Map<String, TeamRosterService.RosterMember> map = new LinkedHashMap<>();
        for (long id : new long[]{1, 2, 3, 4}) {
            map.put("puuid-" + id, new TeamRosterService.RosterMember("riot-" + id,
                    new LinkedHashSet<>(), "puuid-" + id));
        }
        return map;
    }

    private MatchMvp award(long participantId, String type) {
        MatchMvp a = new MatchMvp();
        a.setParticipantId(participantId);
        a.setType(type);
        return a;
    }

    /** 用例：胜局摘要——主队判定、比分、成员置前、击杀降序、称号、数值全量 */
    @Test
    void build_winGame_containsFleetViewFacts() {
        when(gameData.championName(anyInt())).thenAnswer(inv -> "英雄" + inv.getArgument(0));
        when(teamProps.getName()).thenReturn("舰队");

        FleetGameSummary s = service.build(match(), participants(), roster(),
                List.of(award(1L, "MVP"), award(8L, "ACE")));

        // 顶层事实：胜负、比分（双方击杀合计 32:19）、主队判定（成员多数在 100）
        assertThat(s.isWin()).isTrue();
        assertThat(s.getMainScore()).isEqualTo(32);
        assertThat(s.getOtherScore()).isEqualTo(19);
        assertThat(s.getMainTeamId()).isEqualTo(100);
        assertThat(s.getTeamName()).isEqualTo("舰队");
        // 队列与时长为原始值（展示格式化留给投影层）
        assertThat(s.getQueueId()).isEqualTo(440);
        assertThat(s.getGameDurationSeconds()).isEqualTo(28 * 60 + 42);

        // 主队 5 行：车队成员置前（4 人，行内按击杀降序），路人最后
        assertThat(s.getMainTeam()).hasSize(5);
        FleetGameSummary.Row first = s.getMainTeam().get(0);
        assertThat(first.getSummonerName()).isEqualTo("峡谷养鱼人");
        assertThat(first.isMember()).isTrue();
        assertThat(first.getTitle()).isEqualTo("MVP");
        assertThat(first.getKills()).isEqualTo(12);
        assertThat(first.getDamage()).isEqualTo(28600);
        assertThat(first.getDamageTaken()).isEqualTo(14300);
        assertThat(first.getGold()).isEqualTo(15400);
        assertThat(first.getChampionId()).isEqualTo(64);
        // 路人甲（7 杀）排在 4 名车队成员之后，member=false
        assertThat(s.getMainTeam().get(4).getSummonerName()).isEqualTo("路人甲");
        assertThat(s.getMainTeam().get(4).isMember()).isFalse();

        // 对方 5 行：非成员、行内按击杀降序（9 杀的别打野区首位）、ACE 不在对方标称号
        assertThat(s.getOtherTeam()).hasSize(5);
        FleetGameSummary.Row opp = s.getOtherTeam().get(0);
        assertThat(opp.getSummonerName()).isEqualTo("别打野区");
        assertThat(opp.isMember()).isFalse();
        assertThat(opp.getTitle()).isNull();
        assertThat(opp.getDamage()).isEqualTo(16500);
    }

    /** 用例：败局摘要——win=false、主队 ACE 标"尽力"、对方 MVP 标"MVP" */
    @Test
    void build_loseGame_marksAceAsJinLiAndOpponentMvp() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        Match m = match();
        m.setWinnerTeamId(200);

        FleetGameSummary s = service.build(m, participants(), roster(),
                List.of(award(7L, "MVP"), award(4L, "ACE")));

        assertThat(s.isWin()).isFalse();
        // 主队 ACE(盾辅阿离) 标"尽力"
        FleetGameSummary.Row ace = s.getMainTeam().stream()
                .filter(r -> "盾辅阿离".equals(r.getSummonerName())).findFirst().orElseThrow();
        assertThat(ace.getTitle()).isEqualTo("尽力");
        // 对方 MVP 标"MVP"
        FleetGameSummary.Row oppMvp = s.getOtherTeam().stream()
                .filter(r -> "别打野区".equals(r.getSummonerName())).findFirst().orElseThrow();
        assertThat(oppMvp.getTitle()).isEqualTo("MVP");
    }

    /** 用例：主队判定取车队成员多数所在队；无成员数据时回退胜方 */
    @Test
    void build_mainTeamFallsBackToWinnerWhenNoRosterMatch() {
        when(gameData.championName(anyInt())).thenReturn("英雄");
        // 空成员集合：主队回退 winnerTeamId=100
        FleetGameSummary s = service.build(match(), participants(), Map.of(), List.of());
        assertThat(s.getMainTeamId()).isEqualTo(100);
    }

    /** 用例：stats_json 缺失/损坏时数值归 0，不抛错（老数据兜底） */
    @Test
    void build_brokenStatsJson_defaultsToZero() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        List<MatchParticipant> parts = List.of(
                part(1, "无数据选手", 103, 100, 3, 5, 4, null),
                part(6, "损坏选手", 59, 200, 2, 4, 3, "{not-json"));
        FleetGameSummary s = service.build(match(), parts, Map.of(), List.of());

        assertThat(s.getMainTeam()).hasSize(1);
        assertThat(s.getMainTeam().get(0).getDamage()).isZero();
        assertThat(s.getMainTeam().get(0).getDamageTaken()).isZero();
        assertThat(s.getMainTeam().get(0).isMember()).isFalse();
        // 比分仍可从直显列计算（3:2），不依赖 stats
        assertThat(s.getMainScore()).isEqualTo(3);
        assertThat(s.getOtherScore()).isEqualTo(2);
    }

    /** 用例：资源快照从 teams_json 解析（主队塔/龙/大龙/一血），缺失保持无数据 */
    @Test
    void build_parsesTeamResourcesFromTeamsJson() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        Match m = match();
        m.setTeamsJson("[{\"teamId\":100,\"towerKills\":9,\"dragonKills\":3,\"baronKills\":1,\"firstBlood\":true},"
                + "{\"teamId\":200,\"towerKills\":2,\"dragonKills\":1,\"baronKills\":0,\"firstBlood\":false}]");

        FleetGameSummary s = service.build(m, participants(), roster(), List.of());

        // 主队资源：塔 9、龙 3、大龙 1、一血 true
        assertThat(s.getMainTowerKills()).isEqualTo(9);
        assertThat(s.getMainDragonKills()).isEqualTo(3);
        assertThat(s.getMainBaronKills()).isEqualTo(1);
        assertThat(s.getMainFirstBlood()).isTrue();
        // 对方资源：塔 2、龙 1、大龙 0、一血 false
        assertThat(s.getOtherTowerKills()).isEqualTo(2);
        assertThat(s.getOtherDragonKills()).isEqualTo(1);
        assertThat(s.getOtherBaronKills()).isZero();
        assertThat(s.getOtherFirstBlood()).isFalse();
    }

    /** 用例：10 人伤害/承伤合计（战报图占比分母），从 stats 求和 */
    @Test
    void build_computesTotalDamageAcrossAllPlayers() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        FleetGameSummary s = service.build(match(), participants(), roster(), List.of());
        // fixture 全 10 人伤害合计 = 28600+7600+19400+3900+15200+12100+16500+10300+5100+2600
        assertThat(s.getTotalDamage()).isEqualTo(121300);
        assertThat(s.getTotalDamageTaken()).isEqualTo(14300 + 26100 + 10800 + 22400 + 19200
                + 11900 + 3600 + 6900 + 13300 + 10800);
    }
}
