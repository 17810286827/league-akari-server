package com.leagueakari.team;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.dto.scoring.PlayerScoreView;
import com.leagueakari.dto.team.MemberCardResponse;
import com.leagueakari.dto.team.TeamMembersResponse;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.scoring.BaselineService;
import com.leagueakari.scoring.ChampionBaseline;
import com.leagueakari.scoring.OpScoreEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车队成员与成员卡服务（TeamStatsService 拆分后的成员入口）：
 * 成员列表（车队对局出勤口径）与成员卡（个人全部对局口径——成长曲线 + 英雄基线对比）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberStatsService {

    /** 车队成员卡成长曲线的周数（近 8 周，含当前周） */
    static final int TREND_WEEKS = 8;

    private final TeamProperties teamProperties;
    private final TeamRosterService rosterService;
    /** 车队对局装载器：装载与车队局判定的共享口径 */
    private final FleetGameLoader gameLoader;
    /** 七榜单计算引擎：复用英雄聚合（ChampAgg）与分均伤害口径 */
    private final BoardEngine boardEngine;
    private final GameDataService gameDataService;
    private final BaselineService baselineService;
    private final Clock clock;

    /**
     * 车队成员列表：roster + 全时段车队对局出勤统计
     */
    public TeamMembersResponse members() {
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        // 出勤按车队对局口径（与周报一致）；成员卡才用个人全部对局口径
        List<GameData> fleetGames = gameLoader.loadGames(null, null, null, false).stream()
                .filter(g -> gameLoader.isFleet(g, roster)).toList();
        List<TeamMembersResponse.Member> members = new ArrayList<>();
        for (TeamRosterService.RosterMember member : roster) {
            int games = 0;
            int wins = 0;
            for (GameData g : fleetGames) {
                for (MatchParticipant p : g.getParticipants()) {
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
                    .riotId(member.getRiotId())
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
     * @throws BizException 非车队成员（1103）
     */
    public MemberCardResponse memberCard(String puuid) {
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        // 只有车队成员有成员卡：按身份集合匹配（任一别名 puuid 命中即可），陌生 puuid 直接参数错误
        TeamRosterService.RosterMember member = roster.stream()
                .filter(m -> m.owns(puuid))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_TEAM_MEMBER, "非车队成员：" + puuid));
        log.info("Building member card: riotId={}, puuids={}", member.getRiotId(), member.getPuuids().size());

        // 成长曲线：近 TREND_WEEKS 周（含当前周），按"个人全部对局"统计（含单人局与回填局）
        FleetGameLoader.WeekRange currentWeek = FleetGameLoader.weekRange(LocalDate.now(clock), FleetGameLoader.ZONE);
        LocalDate trendFirstMonday = currentWeek.getMonday().minusWeeks(TREND_WEEKS - 1);
        long trendStartMs = trendFirstMonday.atStartOfDay(FleetGameLoader.ZONE).toInstant().toEpochMilli();
        List<GameData> trendGames = gameLoader.loadGames(trendStartMs, currentWeek.getEndMs(), null, true);

        List<MemberCardResponse.TrendPoint> trend = new ArrayList<>();
        for (int i = 0; i < TREND_WEEKS; i++) {
            LocalDate monday = trendFirstMonday.plusWeeks(i);
            FleetGameLoader.WeekRange week = FleetGameLoader.weekRange(monday, FleetGameLoader.ZONE);
            List<GameData> inWeek = trendGames.stream()
                    .filter(g -> g.getMatch().getGameCreation() >= week.getStartMs()
                            && g.getMatch().getGameCreation() < week.getEndMs())
                    .filter(g -> gameLoader.hasMember(g, member))
                    .toList();
            trend.add(buildTrendPoint(monday, inWeek, member));
        }

        // 英雄对比：全时段（不设范围），按场次降序
        List<GameData> allGames = gameLoader.loadGames(null, null, null, true);
        List<MemberCardResponse.ChampionStat> champions = buildChampionStats(allGames, member);

        return MemberCardResponse.builder()
                .puuid(member.primaryPuuid())
                .riotId(member.getRiotId())
                .trend(trend)
                .champions(champions)
                .build();
    }

    /** 构建单周趋势点（成员视角） */
    private MemberCardResponse.TrendPoint buildTrendPoint(LocalDate monday, List<GameData> games,
            TeamRosterService.RosterMember member) {
        int gamesCount = games.size();
        int wins = 0;
        List<Double> opScores = new ArrayList<>();
        for (GameData g : games) {
            MatchParticipant p = gameLoader.memberParticipant(g, member);
            if (p == null) {
                continue;
            }
            if (Boolean.TRUE.equals(p.getWin())) {
                wins++;
            }
            // 评分按该局参赛者自己的 puuid 索引（同一成员不同来源局的标识符可能不同）
            PlayerScoreView score = g.getScores().get(p.getPuuid());
            if (score != null && score.getOpScore() != null) {
                opScores.add(score.getOpScore());
            }
        }
        return MemberCardResponse.TrendPoint.builder()
                .weekLabel(monday.toString())
                .games(gamesCount)
                .winRate(gamesCount == 0 ? null : (double) wins / gamesCount)
                .avgOpScore(opScores.isEmpty() ? null
                        : BoardEngine.round2(opScores.stream().mapToDouble(Double::doubleValue).average().orElse(0)))
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
                baselineDamageByChamp.put(entry.getKey(), BoardEngine.round2(meanDamage));
            }
        }
        // 成员×英雄聚合（身份集合匹配，覆盖腾讯 UUID 局与 Riot puuid 回填局）
        Map<Integer, BoardEngine.ChampAgg> champs = new LinkedHashMap<>();
        for (GameData g : games) {
            MatchParticipant p = gameLoader.memberParticipant(g, member);
            if (p == null || p.getChampionId() == null) {
                continue;
            }
            BoardEngine.ChampAgg champ = champs.computeIfAbsent(p.getChampionId(), k -> new BoardEngine.ChampAgg());
            champ.games++;
            if (Boolean.TRUE.equals(p.getWin())) {
                champ.wins++;
            }
            PlayerScoreView score = g.getScores().get(p.getPuuid());
            if (score != null && score.getOpScore() != null) {
                champ.opScores.add(score.getOpScore());
            }
            double dmgPerMin = boardEngine.damagePerMin(p, g.getMatch());
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
                                : BoardEngine.round2(e.getValue().opScores.stream().mapToDouble(Double::doubleValue).average().orElse(0)))
                        .avgDamagePerMin(e.getValue().damagePerMin.isEmpty() ? null
                                : BoardEngine.round2(e.getValue().damagePerMin.stream().mapToDouble(Double::doubleValue).average().orElse(0)))
                        .baselineDamagePerMin(baselineDamageByChamp.get(e.getKey()))
                        .build())
                .toList();
    }
}
