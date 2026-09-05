package com.leagueakari.team;

import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.dto.scoring.PlayerScoreView;
import com.leagueakari.dto.team.WeeklyReportResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.gamedata.GameDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 七榜单计算引擎（共享组件）：周报与榜单中心共用的全部榜单口径
 * <p>从 TeamStatsService 拆出——MVP/场均 op_score/战犯/送头王/Carry/绝活/出勤
 * 七榜单的聚合与排序只在此处实现。</p>
 * <p>聚合键为成员（riotId）而非 puuid——同一成员可能同时存在腾讯 UUID 与 Riot puuid
 * 两种标识符（LCU/SGP 同步局 vs MATCH-V5 回填局），按 puuid 聚合会把一人拆成两行。</p>
 */
@Component
@RequiredArgsConstructor
public class BoardEngine {

    private final GameDataService gameDataService;
    /** stats_json 读取门面：缺失补 0 口径的唯一实现（架构清理 T4） */
    private final ParticipantStatsReader statsReader;

    /** 单成员的跨对局聚合（榜单计算中间态） */
    static class MemberAgg {
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
    static class ChampAgg {
        int games;
        int wins;
        final List<Double> opScores = new ArrayList<>();
        final List<Double> damagePerMin = new ArrayList<>();
    }

    /** 七榜单的中间结果容器 */
    record Boards(List<WeeklyReportResponse.BoardEntry> mvp, List<WeeklyReportResponse.BoardEntry> opScore,
            List<WeeklyReportResponse.BoardEntry> criminal, List<WeeklyReportResponse.BoardEntry> feeder,
            List<WeeklyReportResponse.BoardEntry> carry, List<WeeklyReportResponse.BoardEntry> signature,
            List<WeeklyReportResponse.BoardEntry> attendance) {}

    /** 数值列表均值（空列表返回 0） */
    public static double avgOf(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** 四舍五入保留两位小数（对外输出的所有评分/比率类数值统一走这里，避免浮点尾巴） */
    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 个人对英雄伤害（statsJson.totalDamageDealtToChampions，缺失/损坏计 0——门面口径） */
    double totalDamage(MatchParticipant p) {
        return statsReader.doubleVal(p.getStatsJson(), "totalDamageDealtToChampions");
    }

    /** 分均伤害（对英雄）：statsJson.totalDamageDealtToChampions / 分钟数；数据缺失返回 -1（榜单绝活榜的无效样本语义） */
    double damagePerMin(MatchParticipant p, Match match) {
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

    /**
     * 计算全部七个榜单（周报与榜单中心共享此口径）
     * <p>聚合键为成员（riotId）而非 puuid——同一成员可能同时存在腾讯 UUID 与 Riot puuid
     * 两种标识符（LCU/SGP 同步局 vs MATCH-V5 回填局），按 puuid 聚合会把一人拆成两行。</p>
     */
    public Boards computeBoards(List<GameData> games, List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> memberByPuuid = FleetGameLoader.memberIndex(roster);
        // 聚合键是成员的 riotId，榜单输出时经此映射回成员（取主 puuid 展示）
        Map<String, TeamRosterService.RosterMember> memberByRiotId = roster.stream()
                .collect(Collectors.toMap(TeamRosterService.RosterMember::riotId,
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
}
