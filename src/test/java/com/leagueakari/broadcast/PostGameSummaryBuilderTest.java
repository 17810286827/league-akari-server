package com.leagueakari.broadcast;


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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.team.TeamRosterService;

/**
 * PostGameSummaryBuilder 单元测试（AI 投影层）：
 * 一局摘要（FleetGameSummary）→ 锐评输入 JSON 的投影契约——
 * 键名（result/score/meta/teamName/mainTeam/otherTeam 与行内 name/champion/win/member/kda/dmg/taken/gold/title）
 * 与 v2 序列化格式保持一致（提示词与消费方零改动）。
 * 组装口径已由 FleetGameSummaryServiceTest 锁定，本类经真实组装器喂数据。
 */
class PostGameSummaryBuilderTest {

    private final GameDataService gameData = mock(GameDataService.class);

    /** 队列名转换 mock 兜底：未打桩的队列 ID 回退数字串（与 GameDataService 口径一致） */
    {
        when(gameData.queueName(any())).thenAnswer(inv -> {
            Integer q = inv.getArgument(0);
            return q == null ? "对局" : String.valueOf(q);
        });
    }
    private final TeamProperties teamProps = mock(TeamProperties.class);
    /** 真实组装器：投影测试喂真实摘要（口径唯一实现） */
    private final FleetGameSummaryService summaryService =
            new FleetGameSummaryService(gameData, new ObjectMapper(), teamProps, new ParticipantStatsReader(new ObjectMapper()));
    private final PostGameSummaryBuilder builder = new PostGameSummaryBuilder(gameData);

    /** stats_json 片段：伤害/承伤/金币（Riot v5 键名） */
    private static String stats(int dmg, int taken, int gold) {
        return "{\"totalDamageDealtToChampions\":" + dmg
                + ",\"totalDamageTaken\":" + taken
                + ",\"goldEarned\":" + gold + "}";
    }

    /** 构造参赛者；puuid 命中 roster 即车队成员 */
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

    /** 用例：胜局投影保持 v2 JSON 契约——顶层键、行内键、称号与数值缩写 */
    @Test
    @SuppressWarnings("unchecked")
    void build_winGame_keepsAiJsonContract() {
        when(gameData.championName(anyInt())).thenAnswer(inv -> "英雄" + inv.getArgument(0));
        // 队列名经 GameDataService 统一出口（spec #43 收编）：meta 行断言用
        when(gameData.queueName(440)).thenReturn("灵活组排");
        when(teamProps.getName()).thenReturn("舰队");
        FleetGameSummary summary = summaryService.build(match(), participants(), roster(),
                List.of(award(1L, "MVP"), award(8L, "ACE")));

        Map<String, Object> s = builder.build(summary);

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
        // 伤害/承伤/金币为省 token 缩写键（dmg/taken/gold），数值齐全供锐评引用
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

    /** 用例：败局投影——result=败北、主队 ACE 标"尽力"、对方 MVP 标"MVP" */
    @Test
    @SuppressWarnings("unchecked")
    void build_loseGame_marksAceAsJinLiAndOpponentMvp() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        Match m = match();
        m.setWinnerTeamId(200);
        FleetGameSummary summary = summaryService.build(m, participants(), roster(),
                List.of(award(7L, "MVP"), award(4L, "ACE")));

        Map<String, Object> s = builder.build(summary);

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

    /** 用例：stats_json 缺失/损坏的行数值归 0（摘要层兜底已测，此处验证投影透传） */
    @Test
    @SuppressWarnings("unchecked")
    void build_brokenStatsJson_defaultsToZero() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        List<MatchParticipant> parts = List.of(
                part(1, "无数据选手", 103, 100, 3, 5, 4, null),
                part(6, "损坏选手", 59, 200, 2, 4, 3, "{not-json"));
        FleetGameSummary summary = summaryService.build(match(), parts, Map.of(), List.of());

        Map<String, Object> s = builder.build(summary);

        List<Map<String, Object>> main = (List<Map<String, Object>>) s.get("mainTeam");
        assertThat(main).hasSize(1);
        assertThat(main.get(0).get("dmg")).isEqualTo(0);
        assertThat(main.get(0).get("taken")).isEqualTo(0);
        assertThat(main.get(0).get("member")).isEqualTo(false);
    }

    /**
     * 用例：投影补全（AI 加厚 spec #43）——一局摘要里现成但被丢弃的字段全部投影：
     * 资源对比（塔/龙/大龙/一血）、各人 opScore、占比分母（全 10 人伤害/承伤合计）。
     * 点名依据更立体：AI 能引用"塔 7:3 龙我们多两条"与"伤害占比 28%"式素材。
     */
    @Test
    @SuppressWarnings("unchecked")
    void build_includesResourcesOpScoreAndDamageTotals() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        when(teamProps.getName()).thenReturn("舰队");
        Match m = match();
        // teams_json 资源快照：主队(100) 塔7/龙3/大龙1/一血，对方(200) 塔3/龙1/大龙0
        m.setTeamsJson("[{\"teamId\":100,\"towerKills\":7,\"dragonKills\":3,\"baronKills\":1,\"firstBlood\":true},"
                + "{\"teamId\":200,\"towerKills\":3,\"dragonKills\":1,\"baronKills\":0}]");
        MatchMvp mvp = award(1L, "MVP");
        mvp.setOpScore(java.math.BigDecimal.valueOf(8.5));
        FleetGameSummary summary = summaryService.build(m, participants(), roster(), List.of(mvp));

        Map<String, Object> s = builder.build(summary);

        // 资源对比：塔/龙/大龙/一血（投影为可读文案，AI 引用有据）
        assertThat(s.get("resources")).isEqualTo("塔 7:3 · 小龙 3:1 · 大龙 1:0 · 一血我方");
        // 占比分母：全 10 人伤害/承伤合计（行内 dmg 与它相除即占比）
        assertThat(s.get("totalDmg")).isEqualTo(121300);
        assertThat(s.get("totalTaken")).isEqualTo(139300);
        // 各人 opScore（评分与锐评不再脱节，spec #43 用户故事 5）
        List<Map<String, Object>> main = (List<Map<String, Object>>) s.get("mainTeam");
        Map<String, Object> first = main.get(0);
        assertThat(first.get("name")).isEqualTo("峡谷养鱼人");
        assertThat(first.get("opScore")).isEqualTo(8.5);
        // 无评选记录的行不携带 opScore 键（缺失跳过口径，不输出 null）
        Map<String, Object> second = main.get(1);
        assertThat(second).doesNotContainKey("opScore");
    }

    /** 用例：资源无数据（teams_json 缺失/-1）时不投影 resources 键，不输出误导性 0:0 */
    @Test
    void build_missingResources_omitsResourceKey() {
        when(gameData.championName(anyInt())).thenReturn("阿狸");
        FleetGameSummary summary = summaryService.build(match(), participants(), roster(), List.of());

        Map<String, Object> s = builder.build(summary);

        assertThat(s).doesNotContainKey("resources");
        // 分母恒在（stats 缺失补 0 也不影响合计语义）
        assertThat(s).containsKey("totalDmg").containsKey("totalTaken");
    }
}
