package com.leagueakari.intel;

import lombok.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 开黑判定引擎：从历史对局中推断"谁和谁一起开黑"。
 * <p>口径源于桌面端 LeagueAkari `shared/utils/team-up-calc.ts` 的 calculateTogetherTimes
 * （共现图 + 组合枚举 + 超集剔除），但修正了桌面端两处缺陷（决策见 ADR 0011 修订，
 * 避免情报卡虚报局数/重复报组）：
 * <ol>
 *   <li>组共现次数 = <b>全组同场的对局交集大小</b>（桌面端取组内两两共现最小值，会把
 *       "三人只同场 4 局、两两另双排 2 局"虚报成 6 局）；</li>
 *   <li>子集剔除<b>跨对局集</b>进行：3 人组同场 N 局时，其 2 人子组若同场局数 ≤ N
 *       （无独立超出大组的双排）则被剔除，不重复报组。</li>
 * </ol></p>
 * <p>纯函数、无副作用：输入对局同队分组与候选玩家，输出开黑分组（可复算、可单测）。</p>
 */
public final class PremadeDetector {

    private PremadeDetector() {
    }

    /**
     * 一局中同队的一撮玩家（一局可能有蓝/红两撮，各自成组）
     *
     * @param matchId 对局标识（与桌面端 matches[].id 同语义，SGP 对局 ID）
     * @param players 本局本队玩家（含候选外玩家，参与判定前会过滤）
     */
    @Value
    public static class TeamMatch {
        String matchId;
        List<String> players;

        public TeamMatch(String matchId, List<String> players) {
            this.matchId = matchId;
            this.players = List.copyOf(players);
        }
    }

    /**
     * 判定出的一个开黑分组
     *
     * @param players  组内玩家（≥2，子组已被剔除）
     * @param times    全组同场对局次数（组内所有人共同出场的对局数）
     * @param matchIds 支撑该判定的同场对局 id 集合（升序，供展示/去重）
     */
    @Value
    public static class PremadeGroup {
        List<String> players;
        int times;
        List<String> matchIds;

        public PremadeGroup(List<String> players, int times, List<String> matchIds) {
            this.players = List.copyOf(players);
            this.times = times;
            this.matchIds = List.copyOf(matchIds);
        }
    }

    /** 共现无向图：顶点=玩家，边=同队出场对局 id 集合（集合大小即共现次数） */
    private static final class Graph {
        private final Map<String, Map<String, Set<String>>> adjacency = new LinkedHashMap<>();

        void addSameTeam(String a, String b, String matchId) {
            adjacency.computeIfAbsent(a, k -> new LinkedHashMap<>())
                    .computeIfAbsent(b, k -> new java.util.HashSet<>()).add(matchId);
            adjacency.computeIfAbsent(b, k -> new LinkedHashMap<>())
                    .computeIfAbsent(a, k -> new java.util.HashSet<>()).add(matchId);
        }

        /** 两玩家同队出场的对局 id 集合；从未同队返回空集 */
        Set<String> sameTeamMatchIds(String a, String b) {
            Map<String, Set<String>> edges = adjacency.get(a);
            if (edges == null) {
                return Set.of();
            }
            Set<String> ids = edges.get(b);
            return ids == null ? Set.of() : ids;
        }
    }

    /** 一个候选组：成员 + 全组同场的对局 id 集合 */
    private static final class Candidate {
        final List<String> players;
        final Set<String> commonMatchIds;

        Candidate(List<String> players, Set<String> commonMatchIds) {
            this.players = List.copyOf(players);
            this.commonMatchIds = commonMatchIds;
        }
    }

    /**
     * 判定开黑分组（输入为每局每队的同队玩家撮）
     *
     * @param matches    历史对局的同队分组（每局每队一条）
     * @param candidates 候选玩家（敌方 5 人）。不在候选集合内的玩家不参与建图、不参与枚举——
     *                   混入局内的车队成员/路人天然被排除，不会成为组员
     * @param threshold  共现阈值：全组同场 ≥ 该次数才算开黑
     * @return 开黑分组（每组 ≥2 人、全组同场 ≥ 阈值、子组已剔除）；无则空列表
     */
    public static List<PremadeGroup> detect(
            List<TeamMatch> matches,
            List<String> candidates,
            int threshold
    ) {
        if (matches.isEmpty() || candidates.size() < 2 || threshold <= 0) {
            return List.of();
        }
        Set<String> candidateSet = new HashSet<>(candidates);
        List<String> orderedCandidates = List.copyOf(candidates);

        // 1) 建共现图：只统计候选玩家内部的两两同队（局内同队才连边）
        Graph graph = new Graph();
        for (TeamMatch match : matches) {
            List<String> inScope = match.getPlayers().stream()
                    .filter(candidateSet::contains)
                    .toList();
            for (int i = 0; i < inScope.size(); i++) {
                for (int j = i + 1; j < inScope.size(); j++) {
                    graph.addSameTeam(inScope.get(i), inScope.get(j), match.getMatchId());
                }
            }
        }

        // 2) 枚举候选全部组合（≥2 人），求全组同场对局交集；交集 ≥ 阈值的组保留
        List<Candidate> qualified = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (int r = 2; r <= orderedCandidates.size(); r++) {
            enumerate(orderedCandidates, r, 0, current, graph, threshold, qualified);
        }

        // 3) 全局子集剔除：组 A 被组 B 包含且 A 的同场局数 ≤ B（A 无独立超出 B 的同场共现）时剔 A。
        //    跨对局集进行，避免"3 人组同场 N 局 + 其 2 人子组另双排"时重复报组；
        //    若 2 人组确有独立双排局（同场数 > 大组），保留（那是更细的真实情报）。
        List<Candidate> kept = new ArrayList<>();
        for (Candidate a : qualified) {
            boolean superseded = false;
            for (Candidate b : qualified) {
                if (a == b) {
                    continue;
                }
                if (a.commonMatchIds.size() <= b.commonMatchIds.size()
                        && new HashSet<>(a.players).stream().allMatch(b.players::contains)
                        && a.players.size() < b.players.size()) {
                    // A 是 B 的真子集且无更多独立同场局 → 被 B 覆盖，剔除
                    superseded = true;
                    break;
                }
            }
            if (!superseded) {
                kept.add(a);
            }
        }

        // 4) 输出（按组人数降序，稳定可复算）
        return kept.stream()
                .sorted(Comparator.comparingInt((Candidate c) -> c.players.size()).reversed())
                .map(c -> new PremadeGroup(
                        c.players,
                        c.commonMatchIds.size(),
                        c.commonMatchIds.stream().sorted().toList()
                ))
                .toList();
    }

    /** 回溯枚举 r 人组合；每到一个完整组合即求全组同场对局交集（任一成员对从未同队 → 交集空 → 丢弃） */
    private static void enumerate(
            List<String> pool,
            int r,
            int start,
            List<String> current,
            Graph graph,
            int threshold,
            List<Candidate> out
    ) {
        if (current.size() == r) {
            // 全组同场 = 组内所有两两"同队对局集"的交集
            Set<String> common = new TreeSet<>();
            boolean first = true;
            boolean broken = false;
            for (int i = 0; i < current.size() && !broken; i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    Set<String> ids = graph.sameTeamMatchIds(current.get(i), current.get(j));
                    if (ids.isEmpty()) {
                        // 任一对从未同队 → 该组合不可能全组同场
                        broken = true;
                        break;
                    }
                    if (first) {
                        common.addAll(ids);
                        first = false;
                    } else {
                        common.retainAll(ids);
                    }
                }
            }
            if (!broken && !common.isEmpty() && common.size() >= threshold) {
                out.add(new Candidate(current, common));
            }
            return;
        }
        for (int i = start; i < pool.size(); i++) {
            current.add(pool.get(i));
            enumerate(pool, r, i + 1, current, graph, threshold, out);
            current.remove(current.size() - 1);
        }
    }
}
