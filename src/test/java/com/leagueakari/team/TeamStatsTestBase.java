package com.leagueakari.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.dto.scoring.PlayerScoreView;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.leagueakari.match.MatchTimelineService;
import com.leagueakari.scoring.BaselineService;
import com.leagueakari.scoring.MatchMvpService;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 车队聚合测试共享夹具（原 TeamStatsServiceTest 拆分而来）：
 * mock 的 mapper 返回"仿佛按条件查出的"对局数据，只断言公开方法的输出；
 * 时间范围 SQL 过滤由集成测试覆盖真实 WHERE 语义。
 * <p>断言数值与拆分前逐字一致——纯重构的守卫面。</p>
 */
abstract class TeamStatsTestBase {

    /** 测试时区与周口径一致：Asia/Shanghai */
    protected static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    protected final MatchMapper matchMapper = mock(MatchMapper.class);
    protected final MatchParticipantMapper participantMapper = mock(MatchParticipantMapper.class);
    protected final MatchMvpMapper mvpMapper = mock(MatchMvpMapper.class);
    protected final MatchTimelineService timelineService = mock(MatchTimelineService.class);
    protected final MatchMvpService mvpService = mock(MatchMvpService.class);
    protected final TeamRosterService rosterService = mock(TeamRosterService.class);
    protected final GameDataService gameDataService = mock(GameDataService.class);
    protected final WeeklyAiCommentService aiCommentService = mock(WeeklyAiCommentService.class);
    protected final BaselineService baselineService = mock(BaselineService.class);

    /** 固定时钟：2026-09-06（周日）10:00 +08:00，当前周 = 08-31 ~ 09-06，默认周 = 上一周（08-30 ~ 09-05） */
    protected final Clock clock = Clock.fixed(
            ZonedDateTime.of(2026, 9, 6, 10, 0, 0, 0, ZONE).toInstant(), ZONE);

    /** 两名车队成员：A=赌书消得泼茶香（puuid-a）、B=手裂鬼子（puuid-b）；身份集合为单元素（单标识符场景） */
    protected final TeamRosterService.RosterMember memberA =
            member("赌书消得泼茶香#iKun", "puuid-a");
    protected final TeamRosterService.RosterMember memberB =
            member("手裂鬼子#tw2", "puuid-b");

    @BeforeEach
    void stubRoster() {
        // 各测试类构造入口服务时统一打桩：roster=[A,B]（单用例可覆盖）
        when(rosterService.requireMembers()).thenReturn(List.of(memberA, memberB));
    }

    /** 构造被测周报服务：roster=[A,B]，阈值 2（单人与路人局不算车队对局） */
    protected WeeklyReportService weeklyService() {
        TeamProperties props = props();
        return new WeeklyReportService(props, rosterService, loader(), engine(),
                timelineService, aiCommentService, gameDataService, new ObjectMapper(), clock);
    }

    /** 构造被测榜单服务（与周报共享装载器与榜单引擎） */
    protected LeaderboardService leaderboardService() {
        return new LeaderboardService(rosterService, loader(), engine());
    }

    /** 构造被测成员服务 */
    protected MemberStatsService memberService() {
        return new MemberStatsService(props(), rosterService, loader(), engine(),
                gameDataService, baselineService, clock);
    }

    /** 车队属性：阈值 2 */
    private TeamProperties props() {
        TeamProperties props = new TeamProperties();
        props.setMinSharedMembers(2);
        return props;
    }

    /** 共享装载器：挂接全部 mocked mapper */
    protected FleetGameLoader loader() {
        TeamProperties props = new TeamProperties();
        props.setMinSharedMembers(2);
        return new FleetGameLoader(props, matchMapper, participantMapper, mvpMapper, mvpService);
    }

    /** 共享榜单引擎：真实 stats 读取门面 + mocked 游戏数据服务 */
    protected BoardEngine engine() {
        return new BoardEngine(gameDataService, new ParticipantStatsReader(new ObjectMapper()));
    }

    /** 构造成员：身份集合按可变参数顺序（首项为主标识符） */
    protected TeamRosterService.RosterMember member(String riotId, String... puuids) {
        return new TeamRosterService.RosterMember(riotId, new java.util.LinkedHashSet<>(List.of(puuids)));
    }

    /** 测试周：2026-08-24（周一）~ 2026-08-30（周日） */
    protected LocalDate weekDay() {
        return LocalDate.of(2026, 8, 26);
    }

    /** 把北京时间映射为 epoch 毫秒（夹具时间统一用这个） */
    protected long ms(int month, int day, int hour) {
        return ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    /** 构造对局主表记录 */
    protected Match match(long id, long gameId, long creation, int duration, String mode, int winnerTeamId) {
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
    protected MatchParticipant participant(long id, long matchId, String puuid, String name,
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
    protected MatchMvp award(long matchId, long participantId, String type, double opScore) {
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
    protected void stubScoresByGame(Map<Long, Map<String, Double>> scoresByGame) {
        when(mvpService.computeScores(any(Match.class), anyList())).thenAnswer(inv -> {
            Match m = inv.getArgument(0);
            Map<String, Double> byPuuid = scoresByGame.getOrDefault(m.getGameId(), Map.of());
            Map<String, PlayerScoreView> out = new java.util.LinkedHashMap<>();
            byPuuid.forEach((puuid, score) -> out.put(puuid, PlayerScoreView.builder()
                    .opScore(score).grade("良好").dimensions(Map.of()).build()));
            return out;
        });
    }
}
