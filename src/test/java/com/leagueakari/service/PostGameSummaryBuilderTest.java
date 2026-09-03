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
 * PostGameSummaryBuilder 单元测试：
 * 验证局后锐评输入摘要升级——双方 10 人全量可见、每人伤害/承伤/经济字段、
 * 比分、车队成员标记与称号映射（主队 MVP/尽力，对方 MVP）。不触网不触库。
 */
class PostGameSummaryBuilderTest {

    private final GameDataService gameData = mock(GameDataService.class);
    private final TeamProperties teamProps = mock(TeamProperties.class);
    private final PostGameSummaryBuilder builder =
            new PostGameSummaryBuilder(gameData, new ObjectMapper(), teamProps);

    /** stats_json 片段：伤害/承伤/金币（Riot v5 键名） */
    private static String stats(int dmg, int taken, int gold) {
        return "{\"totalDamageDealtToChampions\":" + dmg
                + ",\"totalDamageTaken\":" + taken
                + ",\"goldEarned\":" + gold + "}";
    }

    /** 构造参赛者；fleet 为 true 表示车队成员（进 memberByPuuid） */
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

    /** 用例：胜局摘要含双方 10 人全量数据（伤害/承伤/金币/比分/成员标记/称号） */
    @Test
    @SuppressWarnings("unchecked")
    void build_winGame_containsBothTeamsWithFullStats() {
        when(gameData.championName(anyInt())).thenAnswer(inv -> "英雄" + inv.getArgument(0));
        when(teamProps.getName()).thenReturn("舰队");

        Map<String, Object> s = builder.build(match(), participants(), roster(),
                List.of(award(1L, "MVP"), award(8L, "ACE")));

        assertThat(s.get("result")).isEqualTo("胜利");
        assertThat(s.get("score")).isEqualTo("32:19");
        assertThat((String) s.get("meta")).contains("灵活组排", "28分42秒");
        assertThat(s.get("teamName")).isEqualTo("舰队");

        List<Map<String, Object>> main = (List<Map<String, Object>>) s.get("mainTeam");
        List<Map<String, Object>> other = (List<Map<String, Object>>) s.get("otherTeam");
        assertThat(main).hasSize(5);
        assertThat(other).hasSize(5);

        // 车队成员(member=true)置前，路人甲排最后
        Map<String, Object> first = main.get(0);
        assertThat(first.get("name")).isEqualTo("峡谷养鱼人");
        assertThat(first.get("member")).isEqualTo(true);
        assertThat(first.get("title")).isEqualTo("MVP");
        assertThat((String) first.get("kda")).isEqualTo("12/3/7");
        // 伤害/承伤/金币从 stats_json 解析；数值齐全供锐评引用
        assertThat(first.get("dmg")).isEqualTo(28600);
        assertThat(first.get("taken")).isEqualTo(14300);
        assertThat(first.get("gold")).isEqualTo(15400);
        assertThat(main.get(4).get("member")).isEqualTo(false);

        // 对方：全员非车队成员；ACE 不标（只主队尽力），数据齐全（击杀降序，9 杀排首位）
        Map<String, Object> opp = other.get(0);
        assertThat(opp.get("name")).isEqualTo("别打野区");
        assertThat(opp.get("member")).isEqualTo(false);
        assertThat(opp.get("title")).isNull();
        assertThat(opp.get("dmg")).isEqualTo(16500);
    }

    /** 用例：败局摘要——result=败北、比分对调、主队 ACE 标"尽力"、对方 MVP 标"MVP" */
    @Test
    @SuppressWarnings("unchecked")
    void build_loseGame_marksAceAsJinLiAndOpponentMvp() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        Match m = match();
        m.setWinnerTeamId(200);

        Map<String, Object> s = builder.build(m, participants(), roster(),
                List.of(award(7L, "MVP"), award(4L, "ACE")));

        assertThat(s.get("result")).isEqualTo("败北");
        assertThat(s.get("score")).isEqualTo("32:19");
        List<Map<String, Object>> main = (List<Map<String, Object>>) s.get("mainTeam");
        List<Map<String, Object>> other = (List<Map<String, Object>>) s.get("otherTeam");
        // 主队 ACE(盾辅阿离) 标"尽力"
        Map<String, Object> ace = main.stream()
                .filter(r -> "盾辅阿离".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(ace.get("title")).isEqualTo("尽力");
        // 对方 MVP 标"MVP"
        Map<String, Object> oppMvp = other.stream()
                .filter(r -> "别打野区".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(oppMvp.get("title")).isEqualTo("MVP");
    }

    /** 用例：stats_json 缺失/损坏时数值归 0，不抛错（老数据兜底） */
    @Test
    @SuppressWarnings("unchecked")
    void build_brokenStatsJson_defaultsToZero() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        List<MatchParticipant> parts = List.of(
                part(1, "无数据选手", 103, 100, 3, 5, 4, null),
                part(6, "损坏选手", 59, 200, 2, 4, 3, "{not-json"));
        Map<String, TeamRosterService.RosterMember> empty = Map.of();

        Map<String, Object> s = builder.build(match(), parts, empty, List.of());

        List<Map<String, Object>> main = (List<Map<String, Object>>) s.get("mainTeam");
        assertThat(main).hasSize(1);
        assertThat(main.get(0).get("dmg")).isEqualTo(0);
        assertThat(main.get(0).get("taken")).isEqualTo(0);
        assertThat(main.get(0).get("member")).isEqualTo(false);
    }
}
