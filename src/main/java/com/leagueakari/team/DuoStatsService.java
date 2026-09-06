package com.leagueakari.team;

import com.leagueakari.dto.team.DuoExtendedResponse;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.dto.team.DuoMatrixResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 搭档胜率矩阵服务（工单 #37 / spec #31）：
 * 车队成员两两搭档的车队对局胜率统计——复用 {@link FleetGameLoader} 的
 * 车队对局装载与判定口径、{@link TeamRosterService} 的成员身份集合匹配，
 * 不重写第二套。只统计车队对局（与七榜/周报同口径）。
 * <p>聚合规则：两人同局（不论敌我）记 1 搭档局；胜负按成员人次计——
 * 两人同队时胜局各 +1（共 +2），分属敌我时一胜一负（+1/+1）。
 * 对角线为成员个人车队局统计。</p>
 * <p>5 人规模矩阵（N×N）内存聚合，无性能压力，不建预聚合表。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuoStatsService {

    private final TeamRosterService rosterService;
    private final FleetGameLoader gameLoader;
    /** 英雄名转换（头像 spec #44：阵容成员常用英雄的中文名） */
    private final GameDataService gameDataService;

    /**
     * 搭档胜率矩阵
     *
     * @param gameMode 模式过滤；null 表示全部
     * @param startMs  范围起始（含）；null 表示不限
     * @param endMs    范围结束（不含）；null 表示不限
     * @return N×N 矩阵（对称，轴为 roster 顺序）
     */
    public DuoMatrixResponse duoMatrix(String gameMode, Long startMs, Long endMs) {
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        List<GameData> fleetGames = gameLoader.loadGames(startMs, endMs, gameMode, false).stream()
                .filter(g -> gameLoader.isFleet(g, roster)).toList();
        int n = roster.size();
        // 聚合累计器：[row][col] → {games, wins, losses}（对称累计，最后只读上三角复制）
        int[][] games = new int[n][n];
        int[][] wins = new int[n][n];

        for (GameData game : fleetGames) {
            // 本局出战的成员下标 → 该成员本局是否胜利（按身份集合匹配）
            Map<Integer, Boolean> playingIndex = new HashMap<>();
            for (int i = 0; i < n; i++) {
                var member = roster.get(i);
                var participant = gameLoader.memberParticipant(game, member);
                if (participant != null && Boolean.TRUE.equals(participant.getWin())) {
                    playingIndex.put(i, true);
                } else if (participant != null) {
                    playingIndex.put(i, false);
                }
            }
            // 两两组合：同局即搭档（不论敌我）
            List<Integer> playing = new ArrayList<>(playingIndex.keySet());
            for (int a = 0; a < playing.size(); a++) {
                for (int b = a; b < playing.size(); b++) {
                    int i = playing.get(a);
                    int j = playing.get(b);
                    boolean same = i == j;
                    games[i][j]++;
                    games[j][i] = games[i][j];
                    // 胜负按人次：搭档格 = 两人各自胜负相加；对角线 = 本人胜负（只计一次）
                    int w = (Boolean.TRUE.equals(playingIndex.get(i)) ? 1 : 0)
                            + (same ? 0 : (Boolean.TRUE.equals(playingIndex.get(j)) ? 1 : 0));
                    wins[i][j] += w;
                    wins[j][i] = wins[i][j];
                }
            }
        }

        // 组装矩阵
        List<List<DuoMatrixResponse.Cell>> matrix = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<DuoMatrixResponse.Cell> row = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                int g = games[i][j];
                int w = wins[i][j];
                // 总人次：对角线 = 局数（个人人次）；搭档格 = 2×局数（两人人次）
                int total = i == j ? g : g * 2;
                int l = total - w;
                Double winRate = total > 0 ? (double) w / total : null;
                row.add(DuoMatrixResponse.Cell.builder()
                        .games(g).wins(w).losses(l).winRate(winRate)
                        .build());
            }
            matrix.add(row);
        }
        log.info("Duo matrix computed: members={}, fleetGames={}", n, fleetGames.size());
        return DuoMatrixResponse.builder()
                .members(roster.stream().map(TeamRosterService.RosterMember::getRiotId).toList())
                .matrix(matrix)
                .build();
    }

    /**
     * 时段胜率（工单 #41）：按 game_creation 的本地时段（Asia/Shanghai）分桶——
     * morning 06-12 / afternoon 12-18 / evening 18-24 / lateNight 00-03 / weeHours 03-06。
     * 只统计车队对局，胜负按成员人次计；小样本口径同矩阵（前端按局数标注）
     */
    public DuoExtendedResponse timeSlots(String gameMode, Long startMs, Long endMs) {
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        List<GameData> fleetGames = gameLoader.loadGames(startMs, endMs, gameMode, false).stream()
                .filter(g -> gameLoader.isFleet(g, roster)).toList();
        // 时段桶固定五档（语义顺序）
        String[] keys = {"morning", "afternoon", "evening", "lateNight", "weeHours"};
        String[] labels = {"上午开黑", "下午开黑", "晚间开黑", "深夜爆肝", "凌晨修仙"};
        int[] games = new int[keys.length];
        int[] wins = new int[keys.length];
        for (GameData game : fleetGames) {
            int slotIndex = slotIndexOf(game.getMatch().getGameCreation());
            games[slotIndex]++;
            // 人次：本局出战的每个成员各计一次
            for (TeamRosterService.RosterMember member : roster) {
                var participant = gameLoader.memberParticipant(game, member);
                if (participant != null) {
                    if (Boolean.TRUE.equals(participant.getWin())) {
                        wins[slotIndex]++;
                    }
                }
            }
        }
        // 人次累计（每局出战的成员各计一次；losses = 人次 - wins）
        int[] played = new int[keys.length];
        for (GameData game : fleetGames) {
            int slotIndex = slotIndexOf(game.getMatch().getGameCreation());
            for (TeamRosterService.RosterMember member : roster) {
                if (gameLoader.memberParticipant(game, member) != null) {
                    played[slotIndex]++;
                }
            }
        }
        List<DuoExtendedResponse.TimeSlotStats> out = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            int w = wins[i];
            int l = played[i] - w;
            out.add(DuoExtendedResponse.TimeSlotStats.builder()
                    .key(keys[i]).label(labels[i])
                    .games(games[i]).wins(w).losses(l)
                    .winRate(played[i] > 0 ? (double) w / played[i] : null)
                    .build());
        }
        log.info("Time slots computed: fleetGames={}", fleetGames.size());
        return DuoExtendedResponse.builder().timeSlots(out).lineups(List.of()).build();
    }

    /**
     * 常用阵容（工单 #41）：同局出战的成员组合（≥2 人）聚合，按局数降序。
     * 只统计车队对局，胜负按成员人次计。
     * <p>头像 spec #44：聚合时同步记录成员×英雄出现次数，组装时取众数
     * （该阵容局中最常使用的英雄）透出 championId 供前端渲染头像。</p>
     */
    public List<DuoExtendedResponse.LineupStats> lineups(String gameMode, Long startMs, Long endMs) {
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        List<GameData> fleetGames = gameLoader.loadGames(startMs, endMs, gameMode, false).stream()
                .filter(g -> gameLoader.isFleet(g, roster)).toList();
        // 阵容键：出战成员的 roster 下标集合（LinkedHash 保持顺序）
        Map<java.util.Set<Integer>, int[]> byLineup = new java.util.LinkedHashMap<>();
        // 成员×英雄累计（阵容键 → roster 下标 → [championId, 次数…]众数原料）
        Map<java.util.Set<Integer>, Map<Integer, Map<Integer, Integer>>> champCountByLineup =
                new java.util.LinkedHashMap<>();
        for (GameData game : fleetGames) {
            java.util.Set<Integer> playing = new java.util.LinkedHashSet<>();
            int w = 0;
            for (int i = 0; i < roster.size(); i++) {
                var participant = gameLoader.memberParticipant(game, roster.get(i));
                if (participant != null) {
                    playing.add(i);
                    if (Boolean.TRUE.equals(participant.getWin())) {
                        w++;
                    }
                }
            }
            if (playing.size() < 2) {
                continue;   // 单人局不构成阵容
            }
            int[] acc = byLineup.computeIfAbsent(playing, k -> new int[3]);
            acc[0]++;      // games
            acc[1] += w;   // wins（人次）
            // 成员×英雄累计（spec #44：众数原料）
            Map<Integer, Map<Integer, Integer>> champCount =
                    champCountByLineup.computeIfAbsent(playing, k -> new java.util.HashMap<>());
            for (int i : playing) {
                var participant = gameLoader.memberParticipant(game, roster.get(i));
                if (participant != null && participant.getChampionId() != null) {
                    champCount.computeIfAbsent(i, k -> new java.util.LinkedHashMap<>())
                            .merge(participant.getChampionId(), 1, Integer::sum);
                }
            }
        }
        // 组装并按局数降序
        List<DuoExtendedResponse.LineupStats> out = new ArrayList<>();
        byLineup.forEach((playing, acc) -> {
            int totalPlayed = acc[0] * playing.size();   // 人次总数 = 局数 × 出战人数
            int l = totalPlayed - acc[1];
            out.add(DuoExtendedResponse.LineupStats.builder()
                    .members(playing.stream().map(i -> roster.get(i).getRiotId()).toList())
                    .games(acc[0]).wins(acc[1]).losses(l)
                    .winRate(totalPlayed > 0 ? (double) acc[1] / totalPlayed : null)
                    .memberChampions(memberChampionsOf(playing,
                            champCountByLineup.getOrDefault(playing, Map.of()), roster))
                    .build());
        });
        out.sort((a, b) -> Integer.compare(b.getGames(), a.getGames()));
        log.info("Lineups computed: fleetGames={}, lineups={}", fleetGames.size(), out.size());
        return out;
    }

    /**
     * 阵容成员的常用英雄（众数）：每个 roster 下标取出现次数最多的英雄；
     * 并列取先达到该次数者（LinkedHashMap 插入序），无英雄数据（championId 全缺失）的成员跳过
     */
    private List<DuoExtendedResponse.MemberChampion> memberChampionsOf(
            java.util.Set<Integer> playing, Map<Integer, Map<Integer, Integer>> champCount,
            List<TeamRosterService.RosterMember> roster) {
        List<DuoExtendedResponse.MemberChampion> out = new ArrayList<>();
        for (int i : playing) {
            Map<Integer, Integer> counts = champCount.get(i);
            if (counts == null || counts.isEmpty()) {
                continue;
            }
            // 众数：次数最多；并列取先插入（首次出现）者
            var best = counts.entrySet().stream()
                    .max(Map.Entry.<Integer, Integer>comparingByValue()
                            .thenComparing(e -> -firstSeenOrder(counts, e.getKey())))
                    .orElse(null);
            if (best == null) {
                continue;
            }
            out.add(DuoExtendedResponse.MemberChampion.builder()
                    .riotId(roster.get(i).getRiotId())
                    .championId(best.getKey())
                    .championName(gameDataService.championName(best.getKey()))
                    .games(best.getValue())
                    .build());
        }
        return out;
    }

    /** 众数并列时的稳定决胜：按 Map 插入序取序号（先出现者优先） */
    private int firstSeenOrder(Map<Integer, Integer> counts, int key) {
        int order = 0;
        for (int k : counts.keySet()) {
            if (k == key) {
                return order;
            }
            order++;
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 组合扩展统计入口（工单 #41）：时段胜率 + 常用阵容一次返回
     * （两次独立扫描 fleetGames，5 人规模无性能压力；避免为一个端点写双聚合循环）
     */
    public DuoExtendedResponse duoExtended(String gameMode, Long startMs, Long endMs) {
        DuoExtendedResponse slots = timeSlots(gameMode, startMs, endMs);
        return DuoExtendedResponse.builder()
                .timeSlots(slots.getTimeSlots())
                .lineups(lineups(gameMode, startMs, endMs))
                .build();
    }

    /** game_creation（epoch ms）→ 时段桶下标（morning0/afternoon1/evening2/lateNight3/weeHours4） */
    private int slotIndexOf(Long gameCreationMs) {
        if (gameCreationMs == null) {
            return 0;
        }
        int hour = java.time.Instant.ofEpochMilli(gameCreationMs)
                .atZone(FleetGameLoader.ZONE).getHour();
        if (hour >= 6 && hour < 12) {
            return 0;
        }
        if (hour >= 12 && hour < 18) {
            return 1;
        }
        if (hour >= 18) {
            return 2;
        }
        if (hour < 3) {
            return 3;
        }
        return 4;
    }
}
