package com.leagueakari.team;

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
}
