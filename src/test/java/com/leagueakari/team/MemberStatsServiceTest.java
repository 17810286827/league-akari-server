package com.leagueakari.team;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.dto.team.MemberCardResponse;
import com.leagueakari.dto.team.TeamMembersResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.scoring.ChampionBaseline;
import com.leagueakari.scoring.OpScoreEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MemberStatsService 单元测试：成员列表出勤与成员卡（成长曲线 + 英雄基线对比）
 * <p>断言数值与拆分前 TeamStatsServiceTest 逐字一致。</p>
 */
class MemberStatsServiceTest extends TeamStatsTestBase {

    /** 用例：成员列表带全时段车队对局出勤与胜率，非车队成员不出现 */
    @Test
    void members_listsRosterWithAttendance() {
        MemberStatsService svc = memberService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 27, 14), 1200, "KIWI", 200);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 266, 200, 8, 3, 2, true, 30000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 100, 4, 6, 3, false, 12000)));

        TeamMembersResponse members = svc.members();

        assertThat(members.getMembers()).hasSize(2);
        TeamMembersResponse.Member first = members.getMembers().get(0);
        assertThat(first.getPuuid()).isEqualTo("puuid-a");
        assertThat(first.getGames()).isEqualTo(2);
        assertThat(first.getWins()).isEqualTo(2);
        assertThat(first.getWinRate()).isEqualTo(1.0);
    }

    /** 用例：成员卡——逐周成长曲线（近 8 周）+ 英雄基线对比（基线=全库分均伤害） */
    @Test
    void memberCard_trendAndChampionBaseline() {
        MemberStatsService svc = memberService();
        // 第 1 周（08-24 周）：A 阿狸胜场；第 2 周（08-31 周）：阿狸负 + 锐雯胜
        Match g1 = match(1, 100L, ms(8, 26, 14), 600, "KIWI", 100);
        Match g2 = match(2, 200L, ms(9, 1, 14), 900, "KIWI", 200);
        Match g3 = match(3, 300L, ms(9, 1, 16), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2, g3));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 12000),
                participant(2, 2, "puuid-a", "赌书消得泼茶香", 103, 100, 2, 6, 1, false, 9000),
                participant(3, 3, "puuid-a", "赌书消得泼茶香", 266, 100, 8, 2, 3, true, 24000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of(
                100L, Map.of("puuid-a", 8.0),
                200L, Map.of("puuid-a", 4.0),
                300L, Map.of("puuid-a", 6.0)));
        when(gameDataService.championName(103)).thenReturn("阿狸");
        when(gameDataService.championName(266)).thenReturn("锐雯");
        // 全库基线：阿狸样本 120 场、分均伤害合计 2400 → 基线 20.0；锐雯无样本
        when(baselineService.getBaselineMap()).thenReturn(Map.of(103, new ChampionBaseline(103, Map.of(OpScoreEngine.DIM_DAMAGE, 20.0), 120)));

        MemberCardResponse card = svc.memberCard("puuid-a");

        assertThat(card.getRiotId()).isEqualTo("赌书消得泼茶香#iKun");
        // 成长曲线：近 8 周，最早周在前；08-24 周 1 场全胜 opScore 8.0；08-31 周 2 场 1 胜场均 5.0
        assertThat(card.getTrend()).hasSize(8);
        MemberCardResponse.TrendPoint week1 = card.getTrend().get(6);
        assertThat(week1.getWeekLabel()).isEqualTo("2026-08-24");
        assertThat(week1.getGames()).isEqualTo(1);
        assertThat(week1.getWinRate()).isEqualTo(1.0);
        assertThat(week1.getAvgOpScore()).isEqualTo(8.0);
        MemberCardResponse.TrendPoint week2 = card.getTrend().get(7);
        assertThat(week2.getWeekLabel()).isEqualTo("2026-08-31");
        assertThat(week2.getGames()).isEqualTo(2);
        assertThat(week2.getWinRate()).isEqualTo(0.5);
        assertThat(week2.getAvgOpScore()).isEqualTo(5.0);
        // 英雄对比：阿狸 2 场 1 胜场均 6.0、分均伤害 (12000/10min + 9000/15min)/2 = (1200+600)/2=900；
        // 基线 20.0（全库样本）；锐雯 1 场无基线
        assertThat(card.getChampions()).hasSize(2);
        MemberCardResponse.ChampionStat ahr = card.getChampions().get(0);
        assertThat(ahr.getChampionName()).isEqualTo("阿狸");
        assertThat(ahr.getGames()).isEqualTo(2);
        assertThat(ahr.getAvgOpScore()).isEqualTo(6.0);
        assertThat(ahr.getAvgDamagePerMin()).isEqualTo(900.0);
        assertThat(ahr.getBaselineDamagePerMin()).isEqualTo(20.0);
        assertThat(card.getChampions().get(1).getBaselineDamagePerMin()).isNull();
    }

    /** 用例：成员卡只允许查车队成员，陌生 puuid 抛业务异常 */
    @Test
    void memberCard_rejectsNonRosterPuuid() {
        MemberStatsService svc = memberService();

        assertThatThrownBy(() -> svc.memberCard("puuid-stranger"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("车队成员");
    }

    /** 用例：成员卡/成长曲线按成员过滤——B 的成员卡不含 A 的对局数据 */
    @Test
    void memberCard_onlyCountsOwnGames() {
        MemberStatsService svc = memberService();
        Match g1 = match(1, 100L, ms(9, 1, 14), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 200, 1, 5, 1, false, 6000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of(100L, Map.of("puuid-a", 8.0, "puuid-b", 3.0)));
        when(baselineService.getBaselineMap()).thenReturn(Map.of());

        MemberCardResponse card = svc.memberCard("puuid-b");

        assertThat(card.getTrend().get(7).getGames()).isEqualTo(1);
        assertThat(card.getTrend().get(7).getWinRate()).isEqualTo(0.0);
        assertThat(card.getChampions()).hasSize(1);
        assertThat(card.getChampions().get(0).getAvgOpScore()).isEqualTo(3.0);
    }
}
