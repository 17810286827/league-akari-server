package com.leagueakari.team;

import com.leagueakari.dto.team.WeeklyReportResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WeeklyReportService 单元测试：车队周报的全部业务口径
 * <p>覆盖默认周边界、总览人次统计、MVP/战犯/送头王/Carry/绝活各榜单排序、
 * 名场面抽取与 AI 锐评优雅降级。断言数值与拆分前 TeamStatsServiceTest 逐字一致。</p>
 */
class WeeklyReportServiceTest extends TeamStatsTestBase {

    /** 用例：不传日期时默认统计"上一周"（今天回退 7 天所在周，按固定时钟） */
    @Test
    void weeklyReport_defaultsToLastWeek() {
        WeeklyReportService svc = weeklyService();
        when(matchMapper.selectList(any())).thenReturn(List.of());

        WeeklyReportResponse report = svc.weeklyReport(null);

        // 固定时钟为 09-06（周日，当前周 = 08-31 ~ 09-06），上一周 = 08-24 ~ 08-30
        assertThat(report.getWeekLabel()).isEqualTo("2026-08-24 ~ 2026-08-30");
        assertThat(report.getOverview().getGameCount()).isZero();
    }

    /**
     * 用例：总览只统计"车队对局"（同局 ≥2 名成员）——
     * 两场车队局 + 一场仅单人出现的路人局；胜负按成员人次计
     */
    @Test
    void weeklyReport_overviewCountsOnlyFleetGames() {
        WeeklyReportService svc = weeklyService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 26, 16), 1800, "KIWI", 200);
        Match solo = match(3, 300L, ms(8, 27, 20), 900, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2, solo));
        // g1：A、B 同队获胜；solo：只有 A 一名成员（不构成车队对局）
        List<MatchParticipant> participants = new ArrayList<>(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 1, "puuid-c", "路人甲", 266, 200, 2, 8, 1, false, 9000),
                participant(4, 3, "puuid-a", "赌书消得泼茶香", 103, 100, 1, 9, 1, false, 5000),
                participant(5, 3, "puuid-x", "路人乙", 84, 200, 8, 1, 2, true, 25000)));
        // g2：A、B 分属敌我两队，A 胜 B 负（胜负按人次计）
        participants.add(participant(6, 2, "puuid-a", "赌书消得泼茶香", 266, 200, 8, 3, 2, true, 30000));
        participants.add(participant(7, 2, "puuid-b", "手裂鬼子", 84, 100, 4, 6, 3, false, 12000));
        when(participantMapper.selectList(any())).thenReturn(participants);
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of());
        when(aiCommentService.generateComment(any())).thenReturn("锐评");

        WeeklyReportResponse report = svc.weeklyReport(weekDay());

        assertThat(report.getOverview().getGameCount()).isEqualTo(2);
        assertThat(report.getOverview().getMemberGameCount()).isEqualTo(4);
        // A：2 胜；B：1 胜 1 负 → 人次胜 3 负 1
        assertThat(report.getOverview().getWinCount()).isEqualTo(3);
        assertThat(report.getOverview().getLossCount()).isEqualTo(1);
        assertThat(report.getOverview().getTotalDurationSeconds()).isEqualTo(3000);
        assertThat(report.getOverview().getBusiestDay()).isEqualTo("2026-08-26");
        assertThat(report.getOverview().getBusiestDayGames()).isEqualTo(2);
        assertThat(report.getOverview().getActiveMembers())
                .containsExactly("赌书消得泼茶香#iKun", "手裂鬼子#tw2");
        assertThat(report.getAiComment()).isEqualTo("锐评");
    }

    /** 用例：MVP 榜统计 MVP+SVP（落库为 ACE）次数，非车队成员不入榜 */
    @Test
    void weeklyReport_mvpBoardCountsAwardsForRosterOnly() {
        WeeklyReportService svc = weeklyService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 27, 14), 1200, "KIWI", 200);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 9, 1, 6, true, 30000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 200, 2, 7, 2, false, 8000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 103, 200, 1, 8, 1, false, 5000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 100, 7, 2, 5, true, 22000),
                participant(5, 2, "puuid-c", "路人甲", 84, 100, 6, 3, 4, true, 20000)));
        // g1 MVP=A；g2 MVP=路人甲（不入榜）、ACE=SVP=B
        when(mvpMapper.selectList(any())).thenReturn(List.of(
                award(1, 1, "MVP", 9.5),
                award(2, 5, "MVP", 8.8),
                award(2, 4, "ACE", 8.0)));
        stubScoresByGame(Map.of());
        when(aiCommentService.generateComment(any())).thenReturn("锐评");

        WeeklyReportResponse report = svc.weeklyReport(weekDay());

        assertThat(report.getMvpBoard()).hasSize(2);
        assertThat(report.getMvpBoard().get(0).getPuuid()).isEqualTo("puuid-a");
        assertThat(report.getMvpBoard().get(0).getValue()).isEqualTo(1.0);
        assertThat(report.getMvpBoard().get(0).getDetail()).contains("MVP×1");
        assertThat(report.getMvpBoard().get(1).getPuuid()).isEqualTo("puuid-b");
        assertThat(report.getMvpBoard().get(1).getDetail()).contains("SVP×1");
    }

    /** 用例：战犯榜按车队对局的场均 op_score 升序（最低分最"战犯"），detail 带场次数 */
    @Test
    void weeklyReport_criminalBoard_sortedByAvgOpScoreAsc() {
        WeeklyReportService svc = weeklyService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 27, 14), 1200, "KIWI", 200);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 9, 1, 6, true, 30000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 2, 7, 2, true, 8000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 103, 200, 6, 4, 5, true, 18000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 200, 3, 6, 2, true, 9000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        // A 场均 (9.0+7.0)/2=8.0；B 场均 (5.0+3.0)/2=4.0 → B 更"战犯"
        stubScoresByGame(Map.of(
                100L, Map.of("puuid-a", 9.0, "puuid-b", 5.0),
                200L, Map.of("puuid-a", 7.0, "puuid-b", 3.0)));
        when(aiCommentService.generateComment(any())).thenReturn("锐评");

        WeeklyReportResponse report = svc.weeklyReport(weekDay());

        assertThat(report.getCriminalBoard()).hasSize(2);
        assertThat(report.getCriminalBoard().get(0).getPuuid()).isEqualTo("puuid-b");
        assertThat(report.getCriminalBoard().get(0).getValue()).isEqualTo(4.0);
        // 战犯榜 detail 带"代表局"（最差一局的 op_score 与 gameId）
        assertThat(report.getCriminalBoard().get(0).getDetail()).contains("2场").contains("最差局");
        assertThat(report.getCriminalBoard().get(1).getPuuid()).isEqualTo("puuid-a");
        // 场均 op_score 排行（与战犯榜同口径反向：A 第一）
        assertThat(report.getOpScoreBoard().get(0).getPuuid()).isEqualTo("puuid-a");
        assertThat(report.getOpScoreBoard().get(0).getValue()).isEqualTo(8.0);
    }

    /** 用例：送头王按场均死亡降序，且只统计车队对局（路人局死亡不计） */
    @Test
    void weeklyReport_feederBoard_avgDeathsDesc() {
        WeeklyReportService svc = weeklyService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 27, 14), 1200, "KIWI", 200);
        Match solo = match(3, 300L, ms(8, 27, 20), 900, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2, solo));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 266, 200, 8, 3, 2, true, 30000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 100, 4, 6, 3, false, 12000),
                // solo 局 A 送了 12 个头——不属于车队对局，不应计入
                participant(5, 3, "puuid-a", "赌书消得泼茶香", 103, 100, 1, 12, 1, false, 5000),
                participant(6, 3, "puuid-x", "路人乙", 84, 200, 8, 1, 2, true, 25000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of());
        when(aiCommentService.generateComment(any())).thenReturn("锐评");

        WeeklyReportResponse report = svc.weeklyReport(weekDay());

        assertThat(report.getFeederBoard()).hasSize(2);
        // A 场均 (2+3)/2=2.5，B 场均 (4+6)/2=5.0 → B 是送头王
        assertThat(report.getFeederBoard().get(0).getPuuid()).isEqualTo("puuid-b");
        assertThat(report.getFeederBoard().get(0).getValue()).isEqualTo(5.0);
        assertThat(report.getFeederBoard().get(0).getDetail()).contains("总死亡10");
    }

    /** 用例：Carry 王按场均击杀参与率 (k+a)/队伍总击杀 降序 */
    @Test
    void weeklyReport_carryBoard_byKillParticipation() {
        WeeklyReportService svc = weeklyService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1));
        // 队伍 100 总击杀 = 5+3+2 = 10；A 参与率 (5+5)/10=1.0，B (3+4)/10=0.7
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 1, "puuid-c", "路人甲", 266, 100, 2, 6, 1, true, 9000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of());
        when(aiCommentService.generateComment(any())).thenReturn("锐评");

        WeeklyReportResponse report = svc.weeklyReport(weekDay());

        assertThat(report.getCarryBoard()).hasSize(2);
        assertThat(report.getCarryBoard().get(0).getPuuid()).isEqualTo("puuid-a");
        assertThat(report.getCarryBoard().get(0).getValue()).isEqualTo(1.0);
        assertThat(report.getCarryBoard().get(1).getValue()).isEqualTo(0.7);
    }

    /** 用例：绝活榜只收录"成员×英雄"场次 ≥2 的组合，按场均 op_score 降序 */
    @Test
    void weeklyReport_signatureBoard_requiresTwoGamesSameChampion() {
        WeeklyReportService svc = weeklyService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 27, 14), 1200, "KIWI", 200);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                // A 玩阿狸两场（opScore 6/8 → 场均 7）
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 2, "puuid-a", "赌书消得泼茶香", 103, 200, 6, 4, 5, true, 18000),
                // A 玩锐雯一场（不足 2 场，不入榜）
                participant(3, 1, "puuid-a", "赌书消得泼茶香", 266, 100, 2, 3, 2, true, 12000),
                // B 玩盲僧两场（opScore 5/5 → 场均 5）
                participant(4, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(5, 2, "puuid-b", "手裂鬼子", 117, 200, 3, 4, 4, false, 15000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of(
                100L, Map.of("puuid-a", 6.0, "puuid-b", 5.0),
                200L, Map.of("puuid-a", 8.0, "puuid-b", 5.0)));
        when(gameDataService.championName(103)).thenReturn("阿狸");
        when(gameDataService.championName(117)).thenReturn("盲僧");
        when(gameDataService.championName(266)).thenReturn("锐雯");
        when(aiCommentService.generateComment(any())).thenReturn("锐评");

        WeeklyReportResponse report = svc.weeklyReport(weekDay());

        assertThat(report.getSignatureBoard()).hasSize(2);
        assertThat(report.getSignatureBoard().get(0).getPuuid()).isEqualTo("puuid-a");
        assertThat(report.getSignatureBoard().get(0).getValue()).isEqualTo(7.0);
        assertThat(report.getSignatureBoard().get(0).getDetail()).contains("阿狸").contains("2场");
        assertThat(report.getSignatureBoard().get(1).getDetail()).contains("盲僧");
    }

    /**
     * 用例：名场面从时间线抽取——五杀时刻、最大翻盘（胜方最大落后金币）、
     * 单局最高击杀、最惨连败；无时间线的对局优雅跳过
     */
    @Test
    void weeklyReport_highlights_extractedFromTimeline() {
        WeeklyReportService svc = weeklyService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 200);
        Match g2 = match(2, 200L, ms(8, 27, 14), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                // g1：A（id=1）在 100 队，B（id=2）在 200 队；200 队获胜 → B 逆转 A 队
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 8, 5, 2, false, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 200, 6, 4, 4, true, 15000),
                // g2：A 大杀特杀
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 266, 100, 18, 3, 4, true, 40000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 200, 2, 8, 2, false, 9000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of());
        when(aiCommentService.generateComment(any())).thenReturn("锐评");
        when(gameDataService.championName(117)).thenReturn("盲僧");
        when(gameDataService.championName(266)).thenReturn("锐雯");

        // g1 时间线：早期 100 队（A）领先 2000 金币，最终 200 队（B）翻盘；
        // g2 时间线缺失 → 该局不产出时间线类名场面，但不报错
        when(timelineService.getTimeline(100L)).thenReturn(List.of(
                Map.of(
                        "timestamp", 60000,
                        "participantFrames", Map.of(
                                "1", Map.of("totalGold", 6000),
                                "2", Map.of("totalGold", 4000)),
                        "events", List.of()),
                Map.of(
                        "timestamp", 120000,
                        "participantFrames", Map.of(
                                "1", Map.of("totalGold", 9000),
                                "2", Map.of("totalGold", 13000)),
                        "events", List.of(
                                Map.of("type", "CHAMPION_KILL", "killStreakLength", 5,
                                        "killerId", 2, "timestamp", 121000)))));
        when(timelineService.getTimeline(200L)).thenReturn(null);

        WeeklyReportResponse report = svc.weeklyReport(weekDay());

        // 五杀时刻：B（手裂鬼子）用盲僧
        assertThat(report.getHighlights().getMultiKillMoment()).isNotNull();
        assertThat(report.getHighlights().getMultiKillMoment().getTitle()).isEqualTo("五杀时刻");
        assertThat(report.getHighlights().getMultiKillMoment().getDetail())
                .contains("手裂鬼子#tw2").contains("盲僧");
        // 最大翻盘：g1 胜方（200 队）最大落后 2000 金币
        assertThat(report.getHighlights().getBiggestComeback().getGameId()).isEqualTo(100L);
        assertThat(report.getHighlights().getBiggestComeback().getValue()).isEqualTo(2000.0);
        // 单局最高击杀：A 在 g2 的 18 杀
        assertThat(report.getHighlights().getMostKillsGame().getDetail()).contains("18");
        // 最惨连败：A 在 g1 失利（此前无连败起点，连续败场按时间顺序数）
        assertThat(report.getHighlights().getWorstStreak().getValue()).isEqualTo(1.0);
    }

    /** 用例：AI 锐评失败时周报主体照常返回，aiComment 为 null（优雅降级） */
    @Test
    void weeklyReport_gracefulWhenAiCommentFails() {
        WeeklyReportService svc = weeklyService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of());
        when(aiCommentService.generateComment(any()))
                .thenThrow(new IllegalStateException("AI 接口调用失败"));

        WeeklyReportResponse report = svc.weeklyReport(weekDay());

        assertThat(report.getOverview().getGameCount()).isEqualTo(1);
        assertThat(report.getAiComment()).isNull();
    }
}
