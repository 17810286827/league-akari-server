package com.leagueakari.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.dto.team.WeeklyReportResponse;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.match.MatchTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 车队周报聚合服务（TeamStatsService 拆分后的周报入口）：
 * 周边界归属、总览、七榜单装配、名场面抽取与 AI 锐评编排
 * <p>装载与榜单口径分别委托 FleetGameLoader 与 BoardEngine（共享组件）；
 * 本服务只保留周报特有的总览/名场面/锐评降级编排。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportService {

    private final TeamProperties teamProperties;
    private final TeamRosterService rosterService;
    /** 车队对局装载器：装载与车队局判定的共享口径 */
    private final FleetGameLoader gameLoader;
    /** 七榜单计算引擎：与榜单中心共享口径 */
    private final BoardEngine boardEngine;
    private final MatchTimelineService timelineService;
    private final WeeklyAiCommentService aiCommentService;
    private final GameDataService gameDataService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 车队周报：默认统计"上一个自然周"（今天回退 7 天所在周），
     * 传入任意日期则统计该日期所在周。总览/六个榜单/名场面 + AI 锐评（失败降级为 null）
     *
     * @param anyDayOfWeek 该周内任意一天；null 表示上一周
     * @return 完整周报
     * @throws BizException 车队名单未配置（1101），任一成员解析失败（1102）
     */
    public WeeklyReportResponse weeklyReport(LocalDate anyDayOfWeek) {
        // 默认周：今天回退 7 天所在周（无论今天是周几，都落在上一个自然周）
        LocalDate targetDay = anyDayOfWeek != null ? anyDayOfWeek : LocalDate.now(clock).minusDays(7);
        FleetGameLoader.WeekRange range = FleetGameLoader.weekRange(targetDay, FleetGameLoader.ZONE);
        log.info("Building weekly report: week={} ~ {}", range.monday(), range.monday().plusDays(6));

        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        // 周报只看"车队对局"：过滤掉成员的单人局/路人局
        List<GameData> fleetGames = gameLoader.loadGames(range.startMs(), range.endMs(), null, true).stream()
                .filter(g -> gameLoader.isFleet(g, roster)).toList();
        log.info("Weekly report games: weekOf={}, fleetGames={}", range.monday(), fleetGames.size());

        BoardEngine.Boards boards = boardEngine.computeBoards(fleetGames, roster);
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
     * 构建总览：场次按车队对局计，胜负按成员人次计
     * （两人分属敌我两队的极端局按每人各自胜负统计，胜+负=人次）
     */
    private WeeklyReportResponse.Overview buildOverview(List<GameData> games,
            List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> memberByPuuid = FleetGameLoader.memberIndex(roster);
        int memberGameCount = 0;
        int winCount = 0;
        long totalDuration = 0;
        Map<String, Integer> gamesByDay = new HashMap<>();
        for (GameData g : games) {
            totalDuration += g.match().getGameDuration() == null ? 0 : g.match().getGameDuration();
            String day = gameLoader.dayLabel(g.match().getGameCreation());
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
                .filter(m -> games.stream().anyMatch(g -> gameLoader.hasMember(g, m)))
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

    /**
     * 从时间线抽取名场面：最大翻盘 / 最惨连败 / 多杀时刻 / 单局最高击杀。
     * 时间线缺失的对局优雅跳过并计入 missingTimelineCount（覆盖度标注）；
     * 全部缺失时各字段为 null
     */
    private WeeklyReportResponse.Highlights extractHighlights(List<GameData> games,
            List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> memberByPuuid = FleetGameLoader.memberIndex(roster);

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
}
