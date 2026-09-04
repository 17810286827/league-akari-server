package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 车队数据聚合服务（核心接缝）：周报、榜单、成员与成员卡的全部业务口径都在这里
 * <p>口径约定：</p>
 * <ul>
 *   <li><b>车队对局</b>：同局出现的车队成员数 ≥ team.min-shared-members（默认 2），
 *       用于过滤成员的单人局/路人局——周报与全部榜单都基于车队对局；</li>
 *   <li><b>自然周</b>：周一 00:00 ~ 次周一 00:00（Asia/Shanghai），按对局开始时间归属；</li>
 *   <li><b>成员卡</b>：唯一按"个人全部对局"统计的出口（含单人局，历史回填数据在此体现）。</li>
 * </ul>
 */
@Slf4j
@Service
public class TeamStatsService {

    /** 车队成员卡成长曲线的周数（近 8 周，含当前周） */
    static final int TREND_WEEKS = 8;

    /** 周口径时区：车队按国内作息开黑，自然周以 Asia/Shanghai 为准 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 榜单维度全集：与各榜单一一对应（用于维度参数校验） */
    private static final Set<String> DIMENSIONS =
            Set.of("mvp", "opscore", "criminal", "feeder", "carry", "signature", "attendance");

    /** 自然周区间：startMs/endMs 为 [startMs, endMs) 开区间毫秒时间戳 */
    public record WeekRange(long startMs, long endMs, LocalDate monday) {}

    /**
     * 单场对局的完整内存视图：主表 + 参赛者 + 评选记录 + 全员 op_score
     * <p>scores 为 match_mvp 引擎的实时计算结果（puuid → 评分视图），不含落库写入。</p>
     */
    private record GameData(Match match, List<MatchParticipant> participants,
            List<MatchMvp> awards, Map<String, PlayerScoreView> scores) {}

    /** 单成员的跨对局聚合（榜单计算中间态） */
    private static class MemberAgg {
        int games;
        int wins;
        int deaths;
        double kpSum;
        int kpCount;
        /** 伤害占比累计与计数（分子=个人对英雄伤害，分母=本队对英雄伤害） */
        double dmgShareSum;
        int dmgShareCount;
        final List<Double> opScores = new ArrayList<>();
        /** 最差一局的 op_score 与 gameId（战犯榜"代表局"展示用） */
        double worstOpScore = Double.MAX_VALUE;
        Long worstGameId;
        int mvpCount;
        int aceCount;
        double awardScoreSum;
        final Map<Integer, ChampAgg> champs = new LinkedHashMap<>();
    }

    /** 单成员×单英雄的聚合（绝活榜/成员卡用） */
    private static class ChampAgg {
        int games;
        int wins;
        final List<Double> opScores = new ArrayList<>();
        final List<Double> damagePerMin = new ArrayList<>();
    }

    /** 七榜单的中间结果容器 */
    private record Boards(List<WeeklyReportResponse.BoardEntry> mvp, List<WeeklyReportResponse.BoardEntry> opScore,
            List<WeeklyReportResponse.BoardEntry> criminal, List<WeeklyReportResponse.BoardEntry> feeder,
            List<WeeklyReportResponse.BoardEntry> carry, List<WeeklyReportResponse.BoardEntry> signature,
            List<WeeklyReportResponse.BoardEntry> attendance) {}

    /** 依赖项（构造注入，测试可整体替换） */
    private final TeamProperties teamProperties;
    private final TeamRosterService rosterService;
    private final MatchMapper matchMapper;
    private final MatchParticipantMapper participantMapper;
    private final MatchMvpMapper mvpMapper;
    private final MatchTimelineService timelineService;
    private final MatchMvpService mvpService;
    private final GameDataService gameDataService;
    private final WeeklyAiCommentService aiCommentService;
    private final BaselineService baselineService;
    private final ObjectMapper objectMapper;
    /** stats_json 读取门面：缺失补 0 口径的唯一实现（架构清理 T4） */
    private final ParticipantStatsReader statsReader;
    private final Clock clock;

    public TeamStatsService(TeamProperties teamProperties, TeamRosterService rosterService,
            MatchMapper matchMapper, MatchParticipantMapper participantMapper,
            MatchMvpMapper mvpMapper, MatchTimelineService timelineService,
            MatchMvpService mvpService, GameDataService gameDataService,
            WeeklyAiCommentService aiCommentService, BaselineService baselineService,
            ObjectMapper objectMapper, ParticipantStatsReader statsReader, Clock clock) {
        this.teamProperties = teamProperties;
        this.rosterService = rosterService;
        this.matchMapper = matchMapper;
        this.participantMapper = participantMapper;
        this.mvpMapper = mvpMapper;
        this.timelineService = timelineService;
        this.mvpService = mvpService;
        this.gameDataService = gameDataService;
        this.aiCommentService = aiCommentService;
        this.baselineService = baselineService;
        this.objectMapper = objectMapper;
        this.statsReader = statsReader;
        this.clock = clock;
    }

    /**
     * 计算某天所在的自然周区间（纯函数）：周一 00:00 ~ 次周一 00:00
     *
     * @param anyDayOfWeek 该周内任意一天
     * @param zone          口径时区
     * @return 周区间（含 monday 字段便于生成周标签）
     */
    public static WeekRange weekRange(LocalDate anyDayOfWeek, ZoneId zone) {
        // 回退到本周一（含当天本身是周一的情况）
        LocalDate monday = anyDayOfWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long startMs = monday.atStartOfDay(zone).toInstant().toEpochMilli();
        long endMs = monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new WeekRange(startMs, endMs, monday);
    }

    /**
     * 车队周报：默认统计"上一个自然周"（今天回退 7 天所在周），
     * 传入任意日期则统计该日期所在周。总览/六个榜单/名场面 + AI 锐评（失败降级为 null）
     *
     * @param anyDayOfWeek 该周内任意一天；null 表示上一周
     * @return 完整周报
     * @throws IllegalArgumentException 车队名单未配置
     * @throws IllegalStateException    任一成员解析失败
     */
    public WeeklyReportResponse weeklyReport(LocalDate anyDayOfWeek) {
        // 默认周：今天回退 7 天所在周（无论今天是周几，都落在上一个自然周）
        LocalDate targetDay = anyDayOfWeek != null ? anyDayOfWeek : LocalDate.now(clock).minusDays(7);
        WeekRange range = weekRange(targetDay, ZONE);
        log.info("Building weekly report: week={} ~ {}", range.monday(), range.monday().plusDays(6));

        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        // 周报只看"车队对局"：过滤掉成员的单人局/路人局
        List<GameData> fleetGames = loadGames(range.startMs(), range.endMs(), null, true).stream()
                .filter(g -> isFleet(g, roster)).toList();
        log.info("Weekly report games: weekOf={}, fleetGames={}", range.monday(), fleetGames.size());

        Boards boards = computeBoards(fleetGames, roster);
        WeeklyReportResponse report = WeeklyReportResponse.builder()
                .weekStartMs(range.startMs())
                .weekEndMs(range.endMs())
                .weekLabel(range.monday() + " ~ " + range.monday().plusDays(6))
                .teamName(teamProperties.getName())
                .overview(buildOverview(fleetGames, roster))
                .mvpBoard(boards.mvp())
                .opScoreBoard(boards.opScore())
                .criminalBoard(boards.criminal())
                .feederBoard(boards.feeder())
                .carryBoard(boards.carry())
                .signatureBoard(boards.signature())
                .attendanceBoard(boards.attendance())
                .highlights(extractHighlights(fleetGames, roster))
                .build();
        // AI 锐评为增强信息：失败降级为 null，不影响周报主体（关键容错点，记 warn）
        try {
            report.setAiComment(aiCommentService.generateComment(report));
        } catch (Exception e) {
            log.warn("Weekly AI comment failed, degrade to null: {}", e.getMessage());
        }
        return report;
    }

    /**
     * 榜单中心：单一维度榜单（与周报共享口径引擎）
     *
     * @param dimension mvp / criminal / feeder / carry / signature / attendance
     * @param gameMode  模式过滤（game_mode 精确匹配）；null 表示全部模式
     * @param startMs   范围起始（含）；null 表示不限
     * @param endMs     范围结束（不含）；null 表示不限
     * @return 榜单数据（已排序）
     * @throws IllegalArgumentException 维度未知或车队名单未配置
     */
    public LeaderboardResponse leaderboard(String dimension, String gameMode, Long startMs, Long endMs) {
        if (dimension == null || !DIMENSIONS.contains(dimension)) {
            throw new IllegalArgumentException("未知榜单维度：" + dimension + "，可选：" + DIMENSIONS);
        }
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        List<GameData> fleetGames = loadGames(startMs, endMs, gameMode, true).stream()
                .filter(g -> isFleet(g, roster)).toList();
        Boards boards = computeBoards(fleetGames, roster);
        List<WeeklyReportResponse.BoardEntry> entries = switch (dimension) {
            case "mvp" -> boards.mvp();
            case "opscore" -> boards.opScore();
            case "criminal" -> boards.criminal();
            case "feeder" -> boards.feeder();
            case "carry" -> boards.carry();
            case "signature" -> boards.signature();
            case "attendance" -> boards.attendance();
            default -> List.of();
        };
        return LeaderboardResponse.builder()
                .dimension(dimension)
                .startMs(startMs)
                .endMs(endMs)
                .gameMode(gameMode)
                .entries(entries)
                .build();
    }

    /**
     * 车队成员列表：roster + 全时段车队对局出勤统计
     */
    public TeamMembersResponse members() {
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        // 出勤按车队对局口径（与周报一致）；成员卡才用个人全部对局口径
        List<GameData> fleetGames = loadGames(null, null, null, false).stream()
                .filter(g -> isFleet(g, roster)).toList();
        List<TeamMembersResponse.Member> members = new ArrayList<>();
        for (TeamRosterService.RosterMember member : roster) {
            int games = 0;
            int wins = 0;
            for (GameData g : fleetGames) {
                for (MatchParticipant p : g.participants()) {
                    // 身份集合匹配：同一名成员的腾讯 UUID / Riot puuid 都计入其名下
                    if (member.owns(p.getPuuid())) {
                        games++;
                        if (Boolean.TRUE.equals(p.getWin())) {
                            wins++;
                        }
                    }
                }
            }
            members.add(TeamMembersResponse.Member.builder()
                    .puuid(member.primaryPuuid())
                    .riotId(member.riotId())
                    .games(games)
                    .wins(wins)
                    .winRate(games == 0 ? null : (double) wins / games)
                    .build());
        }
        return TeamMembersResponse.builder().members(members).build();
    }

    /**
     * 成员卡：个人成长曲线（近 {@value TREND_WEEKS} 周）+ 英雄基线对比（全时段）
     *
     * @param puuid 成员 puuid（主标识或任一别名均可）
     * @throws IllegalArgumentException 非车队成员
     */
    public MemberCardResponse memberCard(String puuid) {
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        // 只有车队成员有成员卡：按身份集合匹配（任一别名 puuid 命中即可），陌生 puuid 直接参数错误
        TeamRosterService.RosterMember member = roster.stream()
                .filter(m -> m.owns(puuid))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("非车队成员：" + puuid));
        log.info("Building member card: riotId={}, puuids={}", member.riotId(), member.puuids().size());

        // 成长曲线：近 TREND_WEEKS 周（含当前周），按"个人全部对局"统计（含单人局与回填局）
        WeekRange currentWeek = weekRange(LocalDate.now(clock), ZONE);
        LocalDate trendFirstMonday = currentWeek.monday().minusWeeks(TREND_WEEKS - 1);
        long trendStartMs = trendFirstMonday.atStartOfDay(ZONE).toInstant().toEpochMilli();
        List<GameData> trendGames = loadGames(trendStartMs, currentWeek.endMs(), null, true);

        List<MemberCardResponse.TrendPoint> trend = new ArrayList<>();
        for (int i = 0; i < TREND_WEEKS; i++) {
            LocalDate monday = trendFirstMonday.plusWeeks(i);
            WeekRange week = weekRange(monday, ZONE);
            List<GameData> inWeek = trendGames.stream()
                    .filter(g -> g.match().getGameCreation() >= week.startMs()
                            && g.match().getGameCreation() < week.endMs())
                    .filter(g -> hasMember(g, member))
                    .toList();
            trend.add(buildTrendPoint(monday, inWeek, member));
        }

        // 英雄对比：全时段（不设范围），按场次降序
        List<GameData> allGames = loadGames(null, null, null, true);
        List<MemberCardResponse.ChampionStat> champions = buildChampionStats(allGames, member);

        return MemberCardResponse.builder()
                .puuid(member.primaryPuuid())
                .riotId(member.riotId())
                .trend(trend)
                .champions(champions)
                .build();
    }

    // ---------- 数据装载 ----------

    /**
     * 按范围/模式装载对局完整视图（时间过滤走 SQL，模式过滤走 SQL）；
     * withScores=true 时对每场对局实时计算全员 op_score（战犯榜/绝活榜/成员卡需要）
     *
     * @param startMs    范围起始（含）；null 不限
     * @param endMs      范围结束（不含）；null 不限
     * @param gameMode   模式精确过滤；null 不限
     * @param withScores 是否实时计算评分（评分引擎纯计算、不落库）
     */
    private List<GameData> loadGames(Long startMs, Long endMs, String gameMode, boolean withScores) {
        // 分段计时：定位榜单/成员卡慢请求的耗时构成（SQL 装载 vs 实时评分）
        long startNanos = System.nanoTime();
        QueryWrapper<Match> matchWrapper = new QueryWrapper<>();
        if (startMs != null) {
            matchWrapper.ge("game_creation", startMs);
        }
        if (endMs != null) {
            matchWrapper.lt("game_creation", endMs);
        }
        if (gameMode != null && !gameMode.isBlank()) {
            matchWrapper.eq("game_mode", gameMode);
        }
        // 升序：名场面的"连败/翻盘"依赖时间顺序
        matchWrapper.orderByAsc("game_creation");
        List<Match> matches = matchMapper.selectList(matchWrapper);
        if (matches.isEmpty()) {
            return List.of();
        }
        long participantsLoadedNanos = System.nanoTime();
        List<Long> matchIds = matches.stream().map(Match::getId).toList();
        // 批量装载参赛者与评选记录，避免逐局查库的 N+1
        Map<Long, List<MatchParticipant>> participantsByMatch = participantMapper.selectList(
                        new QueryWrapper<MatchParticipant>().in("match_id", matchIds)).stream()
                .collect(Collectors.groupingBy(MatchParticipant::getMatchId));
        Map<Long, List<MatchMvp>> awardsByMatch = mvpMapper.selectList(
                        new QueryWrapper<MatchMvp>().in("match_id", matchIds)).stream()
                .collect(Collectors.groupingBy(MatchMvp::getMatchId));
        long scoringStartedNanos = System.nanoTime();

        List<GameData> games = new ArrayList<>(matches.size());
        for (Match match : matches) {
            List<MatchParticipant> participants =
                    participantsByMatch.getOrDefault(match.getId(), List.of());
            // 评分实时计算：与落库评选共用同一引擎同一权重，老对局同样可算
            Map<String, PlayerScoreView> scores = withScores
                    ? mvpService.computeScores(match, participants)
                    : Map.of();
            games.add(new GameData(match, participants,
                    awardsByMatch.getOrDefault(match.getId(), List.of()), scores));
        }
        long scoringDoneNanos = System.nanoTime();
        log.info("loadGames 耗时分段：games={} 对局装载={}ms 参与者/评选装载={}ms 评分计算={}ms",
                matches.size(),
                (participantsLoadedNanos - startNanos) / 1_000_000,
                (scoringStartedNanos - participantsLoadedNanos) / 1_000_000,
                (scoringDoneNanos - scoringStartedNanos) / 1_000_000);
        return games;
    }

    /** 判断对局是否"车队对局"：同局车队成员数 ≥ 配置阈值（按成员身份集合匹配，跨两种 puuid 体系） */
    private boolean isFleet(GameData game, List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> memberByPuuid = memberIndex(roster);
        long count = game.participants().stream()
                .filter(p -> memberByPuuid.containsKey(p.getPuuid()))
                .count();
        return count >= Math.max(1, teamProperties.getMinSharedMembers());
    }

    /** 对局中是否有指定成员（身份集合匹配） */
    private boolean hasMember(GameData game, TeamRosterService.RosterMember member) {
        return game.participants().stream().anyMatch(p -> member.owns(p.getPuuid()));
    }

    /** 成员在该对局中的参赛记录（找不到返回 null，理论上不发生） */
    private MatchParticipant memberParticipant(GameData game, TeamRosterService.RosterMember member) {
        return game.participants().stream()
                .filter(p -> member.owns(p.getPuuid()))
                .findFirst()
                .orElse(null);
    }

    // ---------- 总览与榜单 ----------

    /**
     * 构建总览：场次按车队对局计，胜负按成员人次计
     * （两人分属敌我两队的极端局按每人各自胜负统计，胜+负=人次）
     */
    private WeeklyReportResponse.Overview buildOverview(List<GameData> games,
            List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> memberByPuuid = memberIndex(roster);
        int memberGameCount = 0;
        int winCount = 0;
        long totalDuration = 0;
        Map<String, Integer> gamesByDay = new LinkedHashMap<>();
        for (GameData g : games) {
            totalDuration += g.match().getGameDuration() == null ? 0 : g.match().getGameDuration();
            String day = dayLabel(g.match().getGameCreation());
            gamesByDay.merge(day, 1, Integer::sum);
            for (MatchParticipant p : g.participants()) {
                if (p.getPuuid() == null || !memberByPuuid.containsKey(p.getPuuid())) {
                    continue;
                }
                memberGameCount++;
                if (Boolean.TRUE.equals(p.getWin())) {
                    winCount++;
                }
            }
        }
        // 出勤成员（按配置顺序）：有 ≥1 次参与的成员
        List<String> activeMembers = roster.stream()
                .filter(m -> games.stream().anyMatch(g -> hasMember(g, m)))
                .map(TeamRosterService.RosterMember::riotId)
                .toList();
        // 最密集的一天
        String busiestDay = null;
        int busiestGames = 0;
        for (Map.Entry<String, Integer> e : gamesByDay.entrySet()) {
            if (e.getValue() > busiestGames) {
                busiestGames = e.getValue();
                busiestDay = e.getKey();
            }
        }
        return WeeklyReportResponse.Overview.builder()
                .gameCount(games.size())
                .memberGameCount(memberGameCount)
                .winCount(winCount)
                .lossCount(memberGameCount - winCount)
                .totalDurationSeconds(totalDuration)
                .busiestDay(busiestDay)
                .busiestDayGames(busiestGames)
                .activeMembers(activeMembers)
                .build();
    }

    /** 对局开始时间的日期标签（yyyy-MM-dd，周口径时区） */
    private String dayLabel(Long gameCreationMs) {
        return java.time.Instant.ofEpochMilli(gameCreationMs).atZone(ZONE).toLocalDate().toString();
    }

    /** 数值列表均值（空列表返回 0） */
    private static double avgOf(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** 四舍五入保留两位小数（对外输出的所有评分/比率类数值统一走这里，避免浮点尾巴） */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 计算全部七个榜单（周报与榜单中心共享此口径）
     * <p>聚合键为成员（riotId）而非 puuid——同一成员可能同时存在腾讯 UUID 与 Riot puuid
     * 两种标识符（LCU/SGP 同步局 vs MATCH-V5 回填局），按 puuid 聚合会把一人拆成两行。</p>
     */
    private Boards computeBoards(List<GameData> games, List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> memberByPuuid = memberIndex(roster);
        // 聚合键是成员的 riotId，榜单输出时经此映射回成员（取主 puuid 展示）
        Map<String, TeamRosterService.RosterMember> memberByRiotId = roster.stream()
                .collect(java.util.stream.Collectors.toMap(TeamRosterService.RosterMember::riotId,
                        m -> m, (a, b) -> a));
        Map<String, MemberAgg> aggByMember = new LinkedHashMap<>();
        for (GameData g : games) {
            // 每局的队伍总击杀与总伤害（Carry 王击杀参与率/伤害占比的分母，含非车队队友）
            Map<Integer, Integer> teamKills = new HashMap<>();
            Map<Integer, Double> teamDamage = new HashMap<>();
            for (MatchParticipant p : g.participants()) {
                int teamKey = p.getTeamId() == null ? 0 : p.getTeamId();
                teamKills.merge(teamKey, p.getKills() == null ? 0 : p.getKills(), Integer::sum);
                teamDamage.merge(teamKey, totalDamage(p), Double::sum);
            }
            for (MatchParticipant p : g.participants()) {
                TeamRosterService.RosterMember member = memberByPuuid.get(p.getPuuid());
                if (member == null) {
                    continue;
                }
                MemberAgg agg = aggByMember.computeIfAbsent(member.riotId(), k -> new MemberAgg());
                agg.games++;
                if (Boolean.TRUE.equals(p.getWin())) {
                    agg.wins++;
                }
                agg.deaths += p.getDeaths() == null ? 0 : p.getDeaths();
                // 击杀参与率：(k+a)/队伍总击杀；队伍总击杀为 0 时跳过该局
                int totalKills = teamKills.getOrDefault(p.getTeamId() == null ? 0 : p.getTeamId(), 0);
                if (totalKills > 0) {
                    agg.kpSum += ((p.getKills() == null ? 0 : p.getKills())
                            + (p.getAssists() == null ? 0 : p.getAssists())) / (double) totalKills;
                    agg.kpCount++;
                }
                // 伤害占比：个人对英雄伤害/本队总伤害（队伍伤害为 0 时跳过）
                int teamKey = p.getTeamId() == null ? 0 : p.getTeamId();
                double teamDmg = teamDamage.getOrDefault(teamKey, 0.0);
                if (teamDmg > 0) {
                    agg.dmgShareSum += totalDamage(p) / teamDmg;
                    agg.dmgShareCount++;
                }
                // op_score：来自评分引擎的实时计算（缺失时跳过该局评分维度）；
                // 同时记录最差一局（战犯榜"代表局"）
                PlayerScoreView score = g.scores().get(p.getPuuid());
                if (score != null && score.getOpScore() != null) {
                    agg.opScores.add(score.getOpScore());
                    if (score.getOpScore() < agg.worstOpScore) {
                        agg.worstOpScore = score.getOpScore();
                        agg.worstGameId = g.match().getGameId();
                    }
                }
                // 成员×英雄聚合（绝活榜）
                ChampAgg champ = agg.champs.computeIfAbsent(p.getChampionId(), k -> new ChampAgg());
                champ.games++;
                if (Boolean.TRUE.equals(p.getWin())) {
                    champ.wins++;
                }
                if (score != null && score.getOpScore() != null) {
                    champ.opScores.add(score.getOpScore());
                }
                double dmgPerMin = damagePerMin(p, g.match());
                if (dmgPerMin >= 0) {
                    champ.damagePerMin.add(dmgPerMin);
                }
            }
            // MVP/SVP 计数：按 participantId 回溯到参赛者，只统计车队成员
            for (MatchMvp award : g.awards()) {
                MatchParticipant owner = g.participants().stream()
                        .filter(p -> p.getId() != null && p.getId().equals(award.getParticipantId()))
                        .findFirst().orElse(null);
                TeamRosterService.RosterMember ownerMember =
                        owner == null ? null : memberByPuuid.get(owner.getPuuid());
                if (ownerMember == null) {
                    continue;
                }
                MemberAgg agg = aggByMember.computeIfAbsent(ownerMember.riotId(), k -> new MemberAgg());
                if ("MVP".equals(award.getType())) {
                    agg.mvpCount++;
                } else if ("ACE".equals(award.getType())) {
                    agg.aceCount++;
                }
                if (award.getScore() != null) {
                    agg.awardScoreSum += award.getScore().doubleValue();
                }
            }
        }

        // MVP 榜：只收录有称号的成员；次数降序，同次数按评选总分降序
        List<WeeklyReportResponse.BoardEntry> mvpBoard = aggByMember.entrySet().stream()
                .filter(e -> e.getValue().mvpCount + e.getValue().aceCount > 0)
                .sorted((a, b) -> {
                    int byCount = Integer.compare(b.getValue().mvpCount + b.getValue().aceCount,
                            a.getValue().mvpCount + a.getValue().aceCount);
                    return byCount != 0 ? byCount
                            : Double.compare(b.getValue().awardScoreSum, a.getValue().awardScoreSum);
                })
                .map(e -> WeeklyReportResponse.BoardEntry.builder()
                        .puuid(memberByRiotId.get(e.getKey()) == null ? null : memberByRiotId.get(e.getKey()).primaryPuuid()).riotId(e.getKey())
                        .value((double) (e.getValue().mvpCount + e.getValue().aceCount))
                        .detail((e.getValue().mvpCount > 0 ? "MVP×" + e.getValue().mvpCount : "")
                                + (e.getValue().mvpCount > 0 && e.getValue().aceCount > 0 ? " " : "")
                                + (e.getValue().aceCount > 0 ? "SVP×" + e.getValue().aceCount : ""))
                        .build())
                .toList();

        // 场均 op_score 排行（降序，与战犯榜同口径反向）
        List<WeeklyReportResponse.BoardEntry> opScoreBoard = aggByMember.entrySet().stream()
                .filter(e -> !e.getValue().opScores.isEmpty())
                .sorted((a, b) -> Double.compare(avgOf(b.getValue().opScores), avgOf(a.getValue().opScores)))
                .map(e -> WeeklyReportResponse.BoardEntry.builder()
                        .puuid(memberByRiotId.get(e.getKey()) == null ? null : memberByRiotId.get(e.getKey()).primaryPuuid()).riotId(e.getKey())
                        .value(round2(avgOf(e.getValue().opScores)))
                        .detail(e.getValue().games + "场")
                        .build())
                .toList();

        // 战犯榜：场均 op_score 升序（最低分最"战犯"），detail 带最差一局（代表局）
        List<WeeklyReportResponse.BoardEntry> criminalBoard = aggByMember.entrySet().stream()
                .filter(e -> !e.getValue().opScores.isEmpty())
                .sorted((a, b) -> Double.compare(avgOf(a.getValue().opScores), avgOf(b.getValue().opScores)))
                .map(e -> WeeklyReportResponse.BoardEntry.builder()
                        .puuid(memberByRiotId.get(e.getKey()) == null ? null : memberByRiotId.get(e.getKey()).primaryPuuid()).riotId(e.getKey())
                        .value(round2(avgOf(e.getValue().opScores)))
                        .detail(e.getValue().games + "场 · 最差局 op "
                                + (e.getValue().worstGameId == null ? "—"
                                : String.format("%.1f（%d）", e.getValue().worstOpScore, e.getValue().worstGameId)))
                        .build())
                .toList();

        // 送头王：场均死亡降序
        List<WeeklyReportResponse.BoardEntry> feederBoard = aggByMember.entrySet().stream()
                .filter(e -> e.getValue().games > 0)
                .sorted((a, b) -> Double.compare(
                        (double) b.getValue().deaths / b.getValue().games,
                        (double) a.getValue().deaths / a.getValue().games))
                .map(e -> WeeklyReportResponse.BoardEntry.builder()
                        .puuid(memberByRiotId.get(e.getKey()) == null ? null : memberByRiotId.get(e.getKey()).primaryPuuid()).riotId(e.getKey())
                        .value(round2((double) e.getValue().deaths / e.getValue().games))
                        .detail("总死亡" + e.getValue().deaths)
                        .build())
                .toList();

        // Carry 王：场均击杀参与率降序
        List<WeeklyReportResponse.BoardEntry> carryBoard = aggByMember.entrySet().stream()
                .filter(e -> e.getValue().kpCount > 0)
                .sorted((a, b) -> Double.compare(
                        b.getValue().kpSum / b.getValue().kpCount,
                        a.getValue().kpSum / a.getValue().kpCount))
                .map(e -> WeeklyReportResponse.BoardEntry.builder()
                        .puuid(memberByRiotId.get(e.getKey()) == null ? null : memberByRiotId.get(e.getKey()).primaryPuuid()).riotId(e.getKey())
                        .value(round2(e.getValue().kpCount == 0 ? 0 : e.getValue().kpSum / e.getValue().kpCount))
                        .detail("场均击杀参与 "
                                + Math.round(e.getValue().kpCount == 0 ? 0
                                : e.getValue().kpSum / e.getValue().kpCount * 100) + "% · 伤害占比 "
                                + Math.round(e.getValue().dmgShareCount == 0 ? 0
                                : e.getValue().dmgShareSum / e.getValue().dmgShareCount * 100) + "%")
                        .build())
                .toList();

        // 绝活榜：成员×英雄场次 ≥2，场均 op_score 降序（按 roster 顺序遍历成员）
        List<WeeklyReportResponse.BoardEntry> signatureBoard = new ArrayList<>();
        for (TeamRosterService.RosterMember member : roster) {
            MemberAgg agg = aggByMember.get(member.riotId());
            if (agg == null) {
                continue;
            }
            agg.champs.forEach((champId, champ) -> {
                if (champ.games >= 2 && !champ.opScores.isEmpty()) {
                    signatureBoard.add(WeeklyReportResponse.BoardEntry.builder()
                            .puuid(member.primaryPuuid()).riotId(member.riotId())
                            .value(round2(avgOf(champ.opScores)))
                            .detail(gameDataService.championName(champId) + " " + champ.games + "场 胜率"
                                    + Math.round(champ.wins * 100.0 / champ.games) + "%")
                            .championId(champId)
                            .championName(gameDataService.championName(champId))
                            .games(champ.games)
                            .wins(champ.wins)
                            .build());
                }
            });
        }
        signatureBoard.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 出勤榜：场次降序，同场次按胜场降序
        List<WeeklyReportResponse.BoardEntry> attendanceBoard = aggByMember.entrySet().stream()
                .filter(e -> e.getValue().games > 0)
                .sorted((a, b) -> {
                    int byGames = Integer.compare(b.getValue().games, a.getValue().games);
                    return byGames != 0 ? byGames : Integer.compare(b.getValue().wins, a.getValue().wins);
                })
                .map(e -> WeeklyReportResponse.BoardEntry.builder()
                        .puuid(memberByRiotId.get(e.getKey()) == null ? null : memberByRiotId.get(e.getKey()).primaryPuuid()).riotId(e.getKey())
                        .value((double) e.getValue().games)
                        .detail(e.getValue().games + "场 胜率"
                                + Math.round(e.getValue().wins * 100.0 / e.getValue().games) + "%")
                        .build())
                .toList();

        return new Boards(mvpBoard, opScoreBoard, criminalBoard, feederBoard, carryBoard, signatureBoard,
                attendanceBoard);
    }

    /** roster → 成员索引：每个成员注册其全部已知 puuid（同一人可能同时有腾讯 UUID 与 Riot puuid 两种标识符） */
    private static Map<String, TeamRosterService.RosterMember> memberIndex(List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> index = new HashMap<>();
        for (TeamRosterService.RosterMember member : roster) {
            for (String puuid : member.puuids()) {
                index.putIfAbsent(puuid, member);
            }
        }
        return index;
    }

    /** 个人对英雄伤害（statsJson.totalDamageDealtToChampions，缺失/损坏计 0——门面口径） */
    private double totalDamage(MatchParticipant p) {
        return statsReader.doubleVal(p.getStatsJson(), "totalDamageDealtToChampions");
    }

    /** 分均伤害（对英雄）：statsJson.totalDamageDealtToChampions / 分钟数；数据缺失返回 -1（榜单绝活榜的无效样本语义） */
    private double damagePerMin(MatchParticipant p, Match match) {
        if (match.getGameDuration() == null || match.getGameDuration() <= 0) {
            return -1;
        }
        // 门面口径：JSON 为 null/损坏/字段缺失时值为 0——与旧实现"字段缺失返回 -1"
        // 的差异仅在 statsJson 整体存在的判断上，此处显式保留：null 快照视为无数据
        if (p.getStatsJson() == null || p.getStatsJson().isBlank()) {
            return -1;
        }
        double damage = statsReader.doubleVal(p.getStatsJson(), "totalDamageDealtToChampions");
        if (damage <= 0) {
            return -1;
        }
        return damage / (match.getGameDuration() / 60.0);
    }

    // ---------- 名场面 ----------

    /**
     * 从时间线抽取名场面：最大翻盘 / 最惨连败 / 多杀时刻 / 单局最高击杀。
     * 时间线缺失的对局优雅跳过并计入 missingTimelineCount（覆盖度标注）；
     * 全部缺失时各字段为 null
     */
    private WeeklyReportResponse.Highlights extractHighlights(List<GameData> games,
            List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> memberByPuuid = memberIndex(roster);

        WeeklyReportResponse.HighlightItem comeback = null;
        WeeklyReportResponse.HighlightItem worstStreak = null;
        WeeklyReportResponse.HighlightItem multiKill = null;
        WeeklyReportResponse.HighlightItem mostKills = null;
        double bestDeficit = -1;
        int bestStreak = 0;

        // 单局最高击杀：不依赖时间线
        for (GameData g : games) {
            for (MatchParticipant p : g.participants()) {
                TeamRosterService.RosterMember member = memberByPuuid.get(p.getPuuid());
                if (member == null) {
                    continue;
                }
                int kills = p.getKills() == null ? 0 : p.getKills();
                if (mostKills == null || kills > mostKills.getValue()) {
                    mostKills = WeeklyReportResponse.HighlightItem.builder()
                            .gameId(g.match().getGameId())
                            .title("单局最高击杀")
                            .detail(member.riotId() + " 单局 " + kills + " 杀（"
                                    + gameDataService.championName(p.getChampionId()) + "）")
                            .value((double) kills)
                            .build();
                }
            }
        }
        // 最惨连败：按时间顺序（loadGames 升序）数每个成员的最长连续败场
        Map<String, Integer> currentStreak = new HashMap<>();
        String bestStreakMember = null;
        Long bestStreakEndGame = null;
        for (GameData g : games) {
            for (MatchParticipant p : g.participants()) {
                TeamRosterService.RosterMember member = memberByPuuid.get(p.getPuuid());
                if (member == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(p.getWin())) {
                    // 胜场重置连败计数
                    currentStreak.put(member.riotId(), 0);
                } else {
                    int streak = currentStreak.merge(member.riotId(), 1, Integer::sum);
                    // 实时记录最长连败的归属者与终结局（并列时保留先出现者）
                    if (streak > bestStreak) {
                        bestStreak = streak;
                        bestStreakMember = member.riotId();
                        bestStreakEndGame = g.match().getGameId();
                    }
                }
            }
        }
        if (bestStreakMember != null) {
            worstStreak = WeeklyReportResponse.HighlightItem.builder()
                    .gameId(bestStreakEndGame)
                    .title("最惨连败")
                    .detail(bestStreakMember + " " + bestStreak + "连败")
                    .value((double) bestStreak)
                    .build();
        }

        // 时间线类名场面：翻盘 + 多杀（时间线缺失的局跳过并计数标注）
        int missingTimelineCount = 0;
        for (GameData g : games) {
            Object timeline = timelineService.getTimeline(g.match().getGameId());
            if (timeline == null) {
                missingTimelineCount++;
                continue;
            }
            JsonNode frames = objectMapper.valueToTree(timeline);
            // 局内 participantId（1..N）→ 参赛者：
            // 时间线帧的 participantFrames/killerId 都是局内序号（1..10），而 match_participant.id
            // 是数据库自增主键（两者完全不同）——按"上报数组顺序 = 局内序号"映射
            // （客户端按 LCU/SGP 原始 participants 顺序推送，MATCH-V5 亦按 1..N 排列）
            Map<Integer, MatchParticipant> byGameSlot = new HashMap<>();
            for (int i = 0; i < g.participants().size(); i++) {
                byGameSlot.put(i + 1, g.participants().get(i));
            }
            // 局内 participantId → 队伍 ID（队伍金币聚合需要）
            Map<Integer, Integer> teamById = new HashMap<>();
            byGameSlot.forEach((slot, p) -> {
                if (p.getTeamId() != null) {
                    teamById.put(slot, p.getTeamId());
                }
            });
            Integer winnerTeamId = g.match().getWinnerTeamId();
            // 逐帧聚合双方金币，找胜方最大落后值
            if (winnerTeamId != null) {
                Integer loserTeamId = winnerTeamId == 100 ? 200 : 100;
                double maxDeficit = -1;
                for (JsonNode frame : frames) {
                    Map<Integer, Double> goldByTeam = new HashMap<>();
                    frame.path("participantFrames").fields().forEachRemaining(e -> {
                        int pid;
                        try {
                            pid = Integer.parseInt(e.getKey());
                        } catch (NumberFormatException ex) {
                            return;
                        }
                        Integer teamId = teamById.get(pid);
                        if (teamId != null) {
                            goldByTeam.merge(teamId, e.getValue().path("totalGold").asDouble(0), Double::sum);
                        }
                    });
                    if (goldByTeam.containsKey(winnerTeamId) && goldByTeam.containsKey(loserTeamId)) {
                        double deficit = goldByTeam.get(loserTeamId) - goldByTeam.get(winnerTeamId);
                        maxDeficit = Math.max(maxDeficit, deficit);
                    }
                }
                if (maxDeficit > bestDeficit && maxDeficit > 0) {
                    bestDeficit = maxDeficit;
                    comeback = WeeklyReportResponse.HighlightItem.builder()
                            .gameId(g.match().getGameId())
                            .title("绝地翻盘")
                            .detail("胜方最大落后 " + Math.round(maxDeficit) + " 金币完成翻盘")
                            .value(maxDeficit)
                            .build();
                }
            }
            // 多杀时刻：CHAMPION_KILL.killStreakLength（5=五杀）
            for (JsonNode frame : frames) {
                for (JsonNode event : frame.path("events")) {
                    if (!"CHAMPION_KILL".equals(event.path("type").asText())) {
                        continue;
                    }
                    int streakLength = event.path("killStreakLength").asInt(0);
                    if (streakLength < 3) {
                        continue;
                    }
                    if (multiKill == null || streakLength > multiKill.getValue()) {
                        // 击杀者按局内 participantId 定位（而非数据库主键）
                        MatchParticipant killer = byGameSlot.get(event.path("killerId").asInt());
                        TeamRosterService.RosterMember killerMember =
                                killer == null ? null : memberByPuuid.get(killer.getPuuid());
                        if (killerMember == null) {
                            continue;
                        }
                        String streakName = switch (streakLength) {
                            case 5 -> "五杀时刻";
                            case 4 -> "四杀时刻";
                            default -> "三杀时刻";
                        };
                        multiKill = WeeklyReportResponse.HighlightItem.builder()
                                .gameId(g.match().getGameId())
                                .title(streakName)
                                .detail(killerMember.riotId() + " 用 "
                                        + gameDataService.championName(killer.getChampionId())
                                        + " 拿下" + streakName.replace("时刻", ""))
                                .value((double) streakLength)
                                .build();
                    }
                }
            }
        }

        return WeeklyReportResponse.Highlights.builder()
                .biggestComeback(comeback)
                .worstStreak(worstStreak)
                .multiKillMoment(multiKill)
                .mostKillsGame(mostKills)
                .missingTimelineCount(missingTimelineCount)
                .build();
    }

    // ---------- 成员卡 ----------

    /** 构建单周趋势点（成员视角） */
    private MemberCardResponse.TrendPoint buildTrendPoint(LocalDate monday, List<GameData> games,
            TeamRosterService.RosterMember member) {
        int gamesCount = games.size();
        int wins = 0;
        List<Double> opScores = new ArrayList<>();
        for (GameData g : games) {
            MatchParticipant p = memberParticipant(g, member);
            if (p == null) {
                continue;
            }
            if (Boolean.TRUE.equals(p.getWin())) {
                wins++;
            }
            // 评分按该局参赛者自己的 puuid 索引（同一成员不同来源局的标识符可能不同）
            PlayerScoreView score = g.scores().get(p.getPuuid());
            if (score != null && score.getOpScore() != null) {
                opScores.add(score.getOpScore());
            }
        }
        return MemberCardResponse.TrendPoint.builder()
                .weekLabel(monday.toString())
                .games(gamesCount)
                .winRate(gamesCount == 0 ? null : (double) wins / gamesCount)
                .avgOpScore(opScores.isEmpty() ? null
                        : round2(opScores.stream().mapToDouble(Double::doubleValue).average().orElse(0)))
                .build();
    }

    /** 构建英雄统计与基线对比（按场次降序；基线 = 全库同英雄分均伤害） */
    private List<MemberCardResponse.ChampionStat> buildChampionStats(List<GameData> games,
            TeamRosterService.RosterMember member) {
        // 全库基线：championId → 分均伤害（走 BaselineService 缓存，避免每请求全表查询）
        Map<Integer, Double> baselineDamageByChamp = new HashMap<>();
        for (Map.Entry<Integer, ChampionBaseline> entry : baselineService.getBaselineMap().entrySet()) {
            // 无样本英雄的 meanOf 返回 null → 视为无基线跳过
            Double meanDamage = entry.getValue().meanOf(OpScoreEngine.DIM_DAMAGE);
            if (meanDamage != null) {
                baselineDamageByChamp.put(entry.getKey(), round2(meanDamage));
            }
        }
        // 成员×英雄聚合（身份集合匹配，覆盖腾讯 UUID 局与 Riot puuid 回填局）
        Map<Integer, ChampAgg> champs = new LinkedHashMap<>();
        for (GameData g : games) {
            MatchParticipant p = memberParticipant(g, member);
            if (p == null || p.getChampionId() == null) {
                continue;
            }
            ChampAgg champ = champs.computeIfAbsent(p.getChampionId(), k -> new ChampAgg());
            champ.games++;
            if (Boolean.TRUE.equals(p.getWin())) {
                champ.wins++;
            }
            PlayerScoreView score = g.scores().get(p.getPuuid());
            if (score != null && score.getOpScore() != null) {
                champ.opScores.add(score.getOpScore());
            }
            double dmgPerMin = damagePerMin(p, g.match());
            if (dmgPerMin >= 0) {
                champ.damagePerMin.add(dmgPerMin);
            }
        }
        return champs.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().games, a.getValue().games))
                .map(e -> MemberCardResponse.ChampionStat.builder()
                        .championId(e.getKey())
                        .championName(gameDataService.championName(e.getKey()))
                        .games(e.getValue().games)
                        .wins(e.getValue().wins)
                        .avgOpScore(e.getValue().opScores.isEmpty() ? null
                                : round2(e.getValue().opScores.stream().mapToDouble(Double::doubleValue).average().orElse(0)))
                        .avgDamagePerMin(e.getValue().damagePerMin.isEmpty() ? null
                                : round2(e.getValue().damagePerMin.stream().mapToDouble(Double::doubleValue).average().orElse(0)))
                        .baselineDamagePerMin(baselineDamageByChamp.get(e.getKey()))
                        .build())
                .toList();
    }
}
