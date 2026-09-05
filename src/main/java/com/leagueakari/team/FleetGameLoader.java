package com.leagueakari.team;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.dto.scoring.PlayerScoreView;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.leagueakari.scoring.MatchMvpService;

/**
 * 车队对局装载器（共享组件）：周报/榜单/成员卡共用的数据装载入口与车队判定口径
 * <p>从 TeamStatsService 拆出——装载、车队局判定、成员身份索引是三个聚合场景
 * 共同的口径来源，唯一实现避免复制漂移。</p>
 * <p>口径约定：</p>
 * <ul>
 *   <li><b>车队对局</b>：同局出现的车队成员数 ≥ team.min-shared-members（默认 2），
 *       用于过滤成员的单人局/路人局；</li>
 *   <li><b>自然周</b>：周一 00:00 ~ 次周一 00:00（Asia/Shanghai），按对局开始时间归属；</li>
 *   <li>时间/模式过滤走 SQL，实时评分纯计算不落库（withScores 控制）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FleetGameLoader {

    /** 周口径时区：车队按国内作息开黑，自然周以 Asia/Shanghai 为准 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TeamProperties teamProperties;
    private final MatchMapper matchMapper;
    private final MatchParticipantMapper participantMapper;
    private final MatchMvpMapper mvpMapper;
    private final MatchMvpService mvpService;

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
     * 按范围/模式装载对局完整视图（时间过滤走 SQL，模式过滤走 SQL）；
     * withScores=true 时对每场对局实时计算全员 op_score（战犯榜/绝活榜/成员卡需要）
     *
     * @param startMs    范围起始（含）；null 不限
     * @param endMs      范围结束（不含）；null 不限
     * @param gameMode   模式精确过滤；null 不限
     * @param withScores 是否实时计算评分（评分引擎纯计算、不落库）
     */
    public List<GameData> loadGames(Long startMs, Long endMs, String gameMode, boolean withScores) {
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

        List<GameData> games = new java.util.ArrayList<>(matches.size());
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
    public boolean isFleet(GameData game, List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> memberByPuuid = memberIndex(roster);
        long count = game.participants().stream()
                .filter(p -> memberByPuuid.containsKey(p.getPuuid()))
                .count();
        return count >= Math.max(1, teamProperties.getMinSharedMembers());
    }

    /** 对局中是否有指定成员（身份集合匹配） */
    public boolean hasMember(GameData game, TeamRosterService.RosterMember member) {
        return game.participants().stream().anyMatch(p -> member.owns(p.getPuuid()));
    }

    /** 成员在该对局中的参赛记录（找不到返回 null，理论上不发生） */
    public MatchParticipant memberParticipant(GameData game, TeamRosterService.RosterMember member) {
        return game.participants().stream()
                .filter(p -> member.owns(p.getPuuid()))
                .findFirst()
                .orElse(null);
    }

    /** 对局开始时间的日期标签（yyyy-MM-dd，周口径时区） */
    public String dayLabel(Long gameCreationMs) {
        return java.time.Instant.ofEpochMilli(gameCreationMs).atZone(ZONE).toLocalDate().toString();
    }

    /** roster → 成员索引：每个成员注册其全部已知 puuid（同一人可能同时有腾讯 UUID 与 Riot puuid 两种标识符） */
    public static Map<String, TeamRosterService.RosterMember> memberIndex(List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> index = new HashMap<>();
        for (TeamRosterService.RosterMember member : roster) {
            for (String puuid : member.puuids()) {
                index.putIfAbsent(puuid, member);
            }
        }
        return index;
    }

    /** 自然周区间：startMs/endMs 为 [startMs, endMs) 开区间毫秒时间戳 */
    public record WeekRange(long startMs, long endMs, LocalDate monday) {}
}
