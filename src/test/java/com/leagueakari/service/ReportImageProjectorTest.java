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
 * ReportImageProjector 单元测试（战报图投影层）：
 * 一局摘要（FleetGameSummary）→ 战报图渲染规格（ReportImageData）的纯投影。
 * 回归重点：比分/胜负/资源等字段从摘要填入，杜绝"漏填静默失败"
 * （历史 bug：比分恒显 0:0，见架构评审候选2）。
 */
class ReportImageProjectorTest {

    private final GameDataService gameData = mock(GameDataService.class);
    private final TeamProperties teamProps = mock(TeamProperties.class);
    /** 真实组装器：投影测试用真实摘要构造（口径已由 FleetGameSummaryServiceTest 锁定） */
    private final FleetGameSummaryService summaryService =
            new FleetGameSummaryService(gameData, new ObjectMapper(), teamProps);
    private final ReportImageProjector projector = new ReportImageProjector();

    /** stats_json 片段（与摘要测试同 fixture 风格） */
    private static String stats(int dmg, int taken, int gold) {
        return "{\"totalDamageDealtToChampions\":" + dmg
                + ",\"totalDamageTaken\":" + taken
                + ",\"goldEarned\":" + gold + "}";
    }

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

    private Match match() {
        Match m = new Match();
        m.setGameId(2000000001L);
        m.setQueueId(440);
        m.setGameCreation(1725290460000L); // 2024-09-02 23:21（北京时间，formatGameTime 产物）
        m.setGameDuration(28 * 60 + 42);
        m.setGameMode("CLASSIC");
        m.setWinnerTeamId(100);
        m.setTeamsJson("[{\"teamId\":100,\"towerKills\":9,\"dragonKills\":3,\"baronKills\":1,\"firstBlood\":true},"
                + "{\"teamId\":200,\"towerKills\":2,\"dragonKills\":1,\"baronKills\":0,\"firstBlood\":false}]");
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

    /** 用例：投影把摘要完整映射到渲染规格——比分/胜负/资源/阵容/占比（0:0 回归验收） */
    @Test
    void project_fillsAllRenderFieldsFromSummary() {
        when(gameData.championName(anyInt())).thenAnswer(inv -> "英雄" + inv.getArgument(0));
        when(teamProps.getName()).thenReturn("舰队");
        FleetGameSummary s = summaryService.build(match(), participants(), roster(),
                List.of(award(1L, "MVP"), award(8L, "ACE")));

        var d = projector.project(s);

        // 0:0 回归：比分来自摘要的双方击杀合计，绝不缺省
        assertThat(d.mainScore).isEqualTo(32);
        assertThat(d.otherScore).isEqualTo(19);
        assertThat(d.win).isTrue();
        assertThat(d.resultLabel).isEqualTo("VICTORY · 胜利");
        assertThat(d.teamName).isEqualTo("舰队");
        // meta 行：队列名 + 时长 + 对局时间（北京时间）
        assertThat(d.metaLine).contains("灵活组排", "28分42秒", "09-02 23:21");
        // 资源：主队 塔9/龙3/大龙1/一血，对方 塔2/龙1/大龙0
        assertThat(d.mainTower).isEqualTo(9);
        assertThat(d.mainDragon).isEqualTo(3);
        assertThat(d.mainBaron).isEqualTo(1);
        assertThat(d.mainFirstBlood).isTrue();
        assertThat(d.otherTower).isEqualTo(2);
        assertThat(d.otherDragon).isEqualTo(1);
        assertThat(d.otherBaron).isZero();
        // 阵容：主队 5 行、车队成员置前、称号带 op_score
        assertThat(d.mainTeam).hasSize(5);
        assertThat(d.mainTeam.get(0).summonerName).isEqualTo("峡谷养鱼人");
        assertThat(d.mainTeam.get(0).titleTag).isEqualTo("MVP");
        assertThat(d.otherTeam).hasSize(5);
        // 三指标口径：占比 = 个人值 / 全 10 人合计，伤转 = 伤害/经济
        assertThat(d.mainTeam.get(0).damageShare).isEqualTo(28600.0 / 121300.0);
        assertThat(d.mainTeam.get(0).damagePerGold).isEqualTo(28600.0 / 15400.0);
        // 焦点卡：主队 MVP（峡谷养鱼人）
        assertThat(d.hero).isNotNull();
        assertThat(d.hero.summonerName).isEqualTo("峡谷养鱼人");
        assertThat(d.footerLeft).isEqualTo("舰队");
    }

    /** 用例：败局投影——DEFEAT 标签、焦点卡回退到主队 ACE（尽力） */
    @Test
    void project_loseGame_labelsDefeatAndHeroFallsBackToAce() {
        when(gameData.championName(anyInt())).thenReturn("英雄");
        when(teamProps.getName()).thenReturn("舰队");
        Match m = match();
        m.setWinnerTeamId(200);
        FleetGameSummary s = summaryService.build(m, participants(), roster(),
                List.of(award(7L, "MVP"), award(4L, "ACE")));

        var d = projector.project(s);

        assertThat(d.win).isFalse();
        assertThat(d.resultLabel).isEqualTo("DEFEAT · 败北");
        // 焦点卡：主队无 MVP 时回退 ACE（盾辅阿离标尽力）
        assertThat(d.hero).isNotNull();
        assertThat(d.hero.summonerName).isEqualTo("盾辅阿离");
        assertThat(d.hero.titleTag).isEqualTo("尽力");
    }

    /** 用例：无评选记录时焦点卡回退队内击杀最高（mainRows 首位），titleTag 无徽章 */
    @Test
    void project_noAwards_heroFallsBackToTopKiller() {
        when(gameData.championName(anyInt())).thenReturn("英雄");
        when(teamProps.getName()).thenReturn("舰队");
        FleetGameSummary s = summaryService.build(match(), participants(), roster(), List.of());

        var d = projector.project(s);

        // 无 MVP/ACE：焦点卡 = 主队击杀最高（车队成员置前后行内降序，首位是 12 杀的峡谷养鱼人）
        assertThat(d.hero).isNotNull();
        assertThat(d.hero.summonerName).isEqualTo("峡谷养鱼人");
        assertThat(d.hero.titleTag).isNull();
    }

    /** 用例：资源快照缺失（teams_json 为空）时保持 -1/无数据，不误填对方数值 */
    @Test
    void project_missingTeamsJson_keepsResourceFieldsEmpty() {
        when(gameData.championName(anyInt())).thenReturn("英雄");
        Match m = match();
        m.setTeamsJson(null);
        FleetGameSummary s = summaryService.build(m, participants(), roster(), List.of());

        var d = projector.project(s);

        assertThat(d.mainTower).isEqualTo(-1);
        assertThat(d.mainDragon).isEqualTo(-1);
        assertThat(d.mainBaron).isEqualTo(-1);
        assertThat(d.mainFirstBlood).isNull();
        assertThat(d.otherTower).isEqualTo(-1);
    }
}
