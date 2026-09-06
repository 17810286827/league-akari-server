package com.leagueakari.team;

import com.leagueakari.common.version.GameVersionNormalizer;
import com.leagueakari.dto.team.SeasonReportResponse;
import com.leagueakari.gamedata.GameDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 赛季报告服务（工单 #42 / spec #32）：
 * 赛季维度的车队总结——版本胜率曲线、英雄池随版本漂移、高光时刻。
 * <p>赛季边界人工指定（startMs/endMs 由调用方传入，不做自动判定、不做定时任务）；
 * 版本归一复用 {@link GameVersionNormalizer}（T9 #38，全站单点口径）；
 * 装载与车队局判定复用 {@link FleetGameLoader}（与七榜/周报同口径）。</p>
 * <p>本票范围（spec 待细化项落定）：不包含 AI 总结与分享图——
 * 纯数据聚合秒出，仪式感页面由前端渲染。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeasonReportService {

    private final TeamRosterService rosterService;
    private final FleetGameLoader gameLoader;
    private final GameDataService gameDataService;

    /**
     * 生成赛季报告
     *
     * @param startMs 赛季起始（含，epoch 毫秒——人工指定）
     * @param endMs   赛季结束（不含，epoch 毫秒——人工指定）
     * @return 赛季报告（版本胜率/英雄池漂移/高光时刻）
     */
    public SeasonReportResponse seasonReport(Long startMs, Long endMs) {
        long startTime = System.currentTimeMillis();
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        List<GameData> fleetGames = gameLoader.loadGames(startMs, endMs, null, false).stream()
                .filter(g -> gameLoader.isFleet(g, roster)).toList();
        Map<String, TeamRosterService.RosterMember> memberByPuuid = FleetGameLoader.memberIndex(roster);

        // ---- 总览 + 版本胜率（主版本升序聚合，胜负按人次）----
        int totalPlayed = 0;
        int totalWins = 0;
        Map<String, int[]> byVersion = new TreeMap<>();   // version → [games, wins, played]
        // ---- 英雄池漂移原料：成员 → 版本 → 英雄 → 局数 ----
        Map<String, Map<String, Map<String, Integer>>> drift = new LinkedHashMap<>();
        // ---- 高光时刻：单局最高击杀（复用名场面口径）----
        SeasonReportResponse.Highlight mostKills = null;
        int bestKills = 0;

        for (GameData game : fleetGames) {
            String version = GameVersionNormalizer.normalize(game.getMatch().getGameVersion());
            if (version == null) {
                version = "未知版本";
            }
            int[] acc = byVersion.computeIfAbsent(version, k -> new int[3]);
            acc[0]++;
            for (var participant : game.getParticipants()) {
                var member = memberByPuuid.get(participant.getPuuid());
                if (member == null) {
                    continue;
                }
                acc[2]++;   // 该版本人次
                totalPlayed++;
                if (Boolean.TRUE.equals(participant.getWin())) {
                    acc[1]++;
                    totalWins++;
                }
                // 英雄池漂移
                drift.computeIfAbsent(member.getRiotId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(version, k -> new LinkedHashMap<>())
                        .merge(gameDataService.championName(
                                participant.getChampionId() == null ? 0 : participant.getChampionId()),
                                1, Integer::sum);
                // 单局最高击杀
                int kills = participant.getKills() == null ? 0 : participant.getKills();
                if (kills > bestKills) {
                    bestKills = kills;
                    mostKills = SeasonReportResponse.Highlight.builder()
                            .gameId(game.getMatch().getGameId())
                            .title("单局最高击杀")
                            .detail(member.getRiotId() + " 单局 " + kills + " 杀（"
                                    + gameDataService.championName(
                                            participant.getChampionId() == null ? 0 : participant.getChampionId())
                                    + "）")
                            .build();
                }
            }
        }

        // 版本胜率序列（TreeMap 已按主版本字符串升序）
        List<SeasonReportResponse.VersionStat> versionStats = byVersion.entrySet().stream()
                .map(e -> SeasonReportResponse.VersionStat.builder()
                        .version(e.getKey())
                        .games(e.getValue()[0])
                        .wins(e.getValue()[1])
                        .losses(e.getValue()[2] - e.getValue()[1])
                        .winRate(e.getValue()[2] > 0 ? (double) e.getValue()[1] / e.getValue()[2] : 0)
                        .build())
                .toList();

        // 英雄池漂移（roster 顺序；每版本英雄按局数降序）
        List<SeasonReportResponse.MemberDrift> memberDrifts = new ArrayList<>();
        for (TeamRosterService.RosterMember member : roster) {
            Map<String, Map<String, Integer>> byVersionMap = drift.getOrDefault(member.getRiotId(), Map.of());
            List<SeasonReportResponse.VersionChampions> versions = byVersionMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> SeasonReportResponse.VersionChampions.builder()
                            .version(e.getKey())
                            .champions(e.getValue().entrySet().stream()
                                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                                    .toList())
                            .build())
                    .toList();
            memberDrifts.add(SeasonReportResponse.MemberDrift.builder()
                    .riotId(member.getRiotId())
                    .versions(versions)
                    .build());
        }

        log.info("Season report computed: fleetGames={}, versions={}, elapsed={}ms",
                fleetGames.size(), versionStats.size(), System.currentTimeMillis() - startTime);
        return SeasonReportResponse.builder()
                .totalGames(fleetGames.size())
                .totalWinRate(totalPlayed > 0 ? (double) totalWins / totalPlayed : 0)
                .versionStats(versionStats)
                .memberDrifts(memberDrifts)
                .mostKills(mostKills)
                .build();
    }
}
