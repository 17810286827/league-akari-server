package com.leagueakari.team;

import com.leagueakari.common.exception.ErrorCode;

import com.leagueakari.common.exception.BizException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.dto.LeaderboardResponse;
import com.leagueakari.dto.MemberCardResponse;
import com.leagueakari.dto.PlayerScoreView;
import com.leagueakari.dto.TeamMembersResponse;
import com.leagueakari.dto.WeeklyReportResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.match.MatchTimelineService;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.scoring.BaselineService;
import com.leagueakari.scoring.ChampionBaseline;
import com.leagueakari.scoring.MatchMvpService;
import com.leagueakari.scoring.OpScoreEngine;

/**
 * TeamStatsService 单元测试（核心接缝）：车队周报与榜单的全部业务口径
 * <p>通过 mock 的 mapper 返回"仿佛按条件查出的"对局数据，只断言公开方法的输出——
 * 榜单排序、车队对局过滤（同局成员数阈值）、名场面抽取、成员卡趋势与基线对比、
 * AI 锐评降级、周边界归属。时间范围 SQL 过滤由集成测试覆盖真实 WHERE 语义。</p>
 */
class TeamStatsServiceTest {

    /** 测试时区与周口径一致：Asia/Shanghai */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MatchMapper matchMapper = mock(MatchMapper.class);
    private final MatchParticipantMapper participantMapper = mock(MatchParticipantMapper.class);
    private final MatchMvpMapper mvpMapper = mock(MatchMvpMapper.class);
    private final MatchTimelineService timelineService = mock(MatchTimelineService.class);
    private final MatchMvpService mvpService = mock(MatchMvpService.class);
    private final TeamRosterService rosterService = mock(TeamRosterService.class);
    private final GameDataService gameDataService = mock(GameDataService.class);
    private final WeeklyAiCommentService aiCommentService = mock(WeeklyAiCommentService.class);
    private final BaselineService baselineService = mock(BaselineService.class);

    /** 固定时钟：2026-09-06（周日）10:00 +08:00，当前周 = 08-31 ~ 09-06，默认周 = 上一周（08-30 ~ 09-05） */
    private final Clock clock = Clock.fixed(
            ZonedDateTime.of(2026, 9, 6, 10, 0, 0, 0, ZONE).toInstant(), ZONE);

    /** 两名车队成员：A=赌书消得泼茶香（puuid-a）、B=手裂鬼子（puuid-b）；身份集合为单元素（单标识符场景） */
    private final TeamRosterService.RosterMember memberA =
            member("赌书消得泼茶香#iKun", "puuid-a");
    private final TeamRosterService.RosterMember memberB =
            member("手裂鬼子#tw2", "puuid-b");

    /** 构造成员：身份集合按可变参数顺序（首项为主标识符） */
    private TeamRosterService.RosterMember member(String riotId, String... puuids) {
        return new TeamRosterService.RosterMember(riotId, new java.util.LinkedHashSet<>(List.of(puuids)));
    }

    // ---------- 测试夹具构造 ----------

    /** 构造被测服务：roster=[A,B]，阈值 2（单人与路人局不算车队对局） */
    private TeamStatsService service() {
        TeamProperties props = new TeamProperties();
        props.setMinSharedMembers(2);
        when(rosterService.requireMembers()).thenReturn(List.of(memberA, memberB));
        return new TeamStatsService(props, rosterService, matchMapper, participantMapper,
                mvpMapper, timelineService, mvpService, gameDataService, aiCommentService,
                baselineService, new ObjectMapper(), new ParticipantStatsReader(new ObjectMapper()), clock);
    }

    /** 测试周：2026-08-24（周一）~ 2026-08-30（周日） */
    private LocalDate weekDay() {
        return LocalDate.of(2026, 8, 26);
    }

    /** 把北京时间映射为 epoch 毫秒（夹具时间统一用这个） */
    private long ms(int month, int day, int hour) {
        return ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    /** 构造对局主表记录 */
    private Match match(long id, long gameId, long creation, int duration, String mode, int winnerTeamId) {
        Match m = new Match();
        m.setId(id);
        m.setGameId(gameId);
        m.setGameCreation(creation);
        m.setGameDuration(duration);
        m.setGameMode(mode);
        m.setGameType("MATCHED_GAME");
        m.setQueueId(2400);
        m.setWinnerTeamId(winnerTeamId);
        return m;
    }

    /** 构造参赛者：stats 快照默认带伤害字段（可覆盖） */
    private MatchParticipant participant(long id, long matchId, String puuid, String name,
                                         int champId, int teamId, int k, int d, int a,
                                         boolean win, int damage) {
        MatchParticipant p = new MatchParticipant();
        p.setId(id);
        p.setMatchId(matchId);
        p.setPuuid(puuid);
        p.setSummonerName(name);
        p.setChampionId(champId);
        p.setTeamId(teamId);
        p.setKills(k);
        p.setDeaths(d);
        p.setAssists(a);
        p.setWin(win);
        p.setGoldEarned(10000);
        p.setCs(200);
        p.setStatsJson("{\"totalDamageDealtToChampions\":" + damage + "}");
        return p;
    }

    /** 构造评选记录 */
    private MatchMvp award(long matchId, long participantId, String type, double opScore) {
        MatchMvp m = new MatchMvp();
        m.setMatchId(matchId);
        m.setParticipantId(participantId);
        m.setType(type);
        m.setScoringVersion(2);
        m.setScore(BigDecimal.valueOf(opScore * 10));
        m.setOpScore(BigDecimal.valueOf(opScore));
        m.setGrade("优秀");
        return m;
    }

    /** 让 computeScores 按 gameId 返回预设的 puuid → opScore 映射 */
    private void stubScoresByGame(Map<Long, Map<String, Double>> scoresByGame) {
        when(mvpService.computeScores(any(Match.class), anyList())).thenAnswer(inv -> {
            Match m = inv.getArgument(0);
            Map<String, Double> byPuuid = scoresByGame.getOrDefault(m.getGameId(), Map.of());
            Map<String, PlayerScoreView> out = new java.util.LinkedHashMap<>();
            byPuuid.forEach((puuid, score) -> out.put(puuid, PlayerScoreView.builder()
                    .opScore(score).grade("良好").dimensions(Map.of()).build()));
            return out;
        });
    }

    // ---------- 周边界（纯函数） ----------

    /** 用例：周三入参返回该自然周（周一 00:00 ~ 次周一 00:00，+08:00）的 epoch 毫秒 */
    @Test
    void weekRange_coversMondayToSunday() {
        TeamStatsService.WeekRange range = TeamStatsService.weekRange(weekDay(), ZONE);

        // 独立真值：直接用 ZonedDateTime 计算期望边界
        long expectedStart = ZonedDateTime.of(2026, 8, 24, 0, 0, 0, 0, ZONE).toInstant().toEpochMilli();
        long expectedEnd = ZonedDateTime.of(2026, 8, 31, 0, 0, 0, 0, ZONE).toInstant().toEpochMilli();
        assertThat(range.startMs()).isEqualTo(expectedStart);
        assertThat(range.endMs()).isEqualTo(expectedEnd);
        assertThat(range.monday()).isEqualTo(LocalDate.of(2026, 8, 24));
    }

    /** 用例：不传日期时默认统计"上一周"（今天回退 7 天所在周，按固定时钟） */
    @Test
    void weeklyReport_defaultsToLastWeek() {
        TeamStatsService svc = service();
        when(matchMapper.selectList(any())).thenReturn(List.of());

        WeeklyReportResponse report = svc.weeklyReport(null);

        // 固定时钟为 09-06（周日，当前周 = 08-31 ~ 09-06），上一周 = 08-24 ~ 08-30
        assertThat(report.getWeekLabel()).isEqualTo("2026-08-24 ~ 2026-08-30");
        assertThat(report.getOverview().getGameCount()).isZero();
    }

    // ---------- 总览 ----------

    /**
     * 用例：总览只统计"车队对局"（同局 ≥2 名成员）——
     * 两场车队局 + 一场仅单人出现的路人局；胜负按成员人次计
     */
    @Test
    void weeklyReport_overviewCountsOnlyFleetGames() {
        TeamStatsService svc = service();
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

    // ---------- MVP 榜 ----------

    /** 用例：MVP 榜统计 MVP+SVP（落库为 ACE）次数，非车队成员不入榜 */
    @Test
    void weeklyReport_mvpBoardCountsAwardsForRosterOnly() {
        TeamStatsService svc = service();
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

    // ---------- 战犯榜 / 送头王 / Carry 王 ----------

    /** 用例：战犯榜按车队对局的场均 op_score 升序（最低分最"战犯"），detail 带场次数 */
    @Test
    void weeklyReport_criminalBoard_sortedByAvgOpScoreAsc() {
        TeamStatsService svc = service();
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
        TeamStatsService svc = service();
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
        TeamStatsService svc = service();
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

    // ---------- 绝活榜 ----------

    /** 用例：绝活榜只收录"成员×英雄"场次 ≥2 的组合，按场均 op_score 降序 */
    @Test
    void weeklyReport_signatureBoard_requiresTwoGamesSameChampion() {
        TeamStatsService svc = service();
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

    // ---------- 名场面 ----------

    /**
     * 用例：名场面从时间线抽取——五杀时刻、最大翻盘（胜方最大落后金币）、
     * 单局最高击杀、最惨连败；无时间线的对局优雅跳过
     */
    @Test
    void weeklyReport_highlights_extractedFromTimeline() {
        TeamStatsService svc = service();
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
        TeamStatsService svc = service();
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

    // ---------- 榜单中心 ----------

    /** 用例：榜单按维度路由（与周报共享口径），未知维度抛参数异常 */
    @Test
    void leaderboard_routesDimensionAndRejectsUnknown() {
        TeamStatsService svc = service();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of());

        LeaderboardResponse board = svc.leaderboard("attendance", null, null, null);

        assertThat(board.getDimension()).isEqualTo("attendance");
        assertThat(board.getEntries()).hasSize(2);
        assertThat(board.getEntries().get(0).getValue()).isEqualTo(1.0);

        assertThatThrownBy(() -> svc.leaderboard("no-such-dim", null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("维度");
    }

    // ---------- 成员与成员卡 ----------

    /** 用例：成员列表带全时段车队对局出勤与胜率，非车队成员不出现 */
    @Test
    void members_listsRosterWithAttendance() {
        TeamStatsService svc = service();
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
        TeamStatsService svc = service();
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

    /** 用例：成员卡只允许查车队成员，陌生 puuid 抛参数异常 */
    @Test
    void memberCard_rejectsNonRosterPuuid() {
        TeamStatsService svc = service();

        assertThatThrownBy(() -> svc.memberCard("puuid-stranger"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("车队成员");
    }

    /** 用例：成员卡/成长曲线按成员过滤——B 的成员卡不含 A 的对局数据 */
    @Test
    void memberCard_onlyCountsOwnGames() {
        TeamStatsService svc = service();
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
