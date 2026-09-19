package com.leagueakari.team;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.common.version.GameVersionNormalizer;
import com.leagueakari.dto.team.SeasonArchiveResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 英雄×强化胜率统计服务（工单 #53 / spec：赛季资料 /season-archive）：
 * 按**游戏版本**（ADR 0013：归一主版本）统计英雄×海克斯强化的局数/胜率/出场率。
 * <p>口径（CONTEXT 词条 + ADR 0014）：</p>
 * <ul>
 *   <li><b>样本</b>：全库全量参与者行（含敌方、路人，不做车队过滤），双条件 =
 *       KIWI 队列白名单（经 {@link GameDataService#isKiwiQueue}，单点出口）且该行
 *       playerAugment1-6 至少一个非零；</li>
 *   <li><b>胜率分母</b>：持有该强化的对局数；<b>出场率分母</b>：该英雄对局数
 *       （两个分母刻意不同，防读者混读）；</li>
 *   <li><b>聚合</b>：请求时内存实时计算（与七榜/周报同哲学），无物化表、无定时任务；
 *       主扫描只装载 KIWI 队列对局（SQL 预筛），队列外排除计数走独立轻量分组查询
 *       （ADR 0014：不随主扫描全量加载 stats_json）。</li>
 * </ul>
 * <p>响应携带全版本逐版本序列（走势卡数据），前端切版本/排序/悬停全本地完成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentWinRateService {

    /** stats_json 中强化槽位的连续键名（一局最多 6 个强化） */
    private static final String[] AUGMENT_KEYS = {
            "playerAugment1", "playerAugment2", "playerAugment3",
            "playerAugment4", "playerAugment5", "playerAugment6"};

    private final MatchMapper matchMapper;
    private final MatchParticipantMapper participantMapper;
    private final ParticipantStatsReader statsReader;
    private final GameDataService gameDataService;

    /**
     * 计算赛季资料截面
     *
     * @param version 归一主版本（如 "16.15"）；null/空白 = 最新有数据版本
     * @return 所选版本截面（版本列表 + 覆盖率 + 英雄×强化条目）；无数据时空结构
     */
    public SeasonArchiveResponse seasonArchive(String version) {
        long startTime = System.currentTimeMillis();
        // 主扫描只装 KIWI 队列对局（SQL 预筛：非白名单对局的 stats_json 不进内存，
        // 版本列表也只含 KIWI 对局——任意队列的脏版本行不会污染版本 pills）
        List<Match> kiwiMatches = matchMapper.selectList(new QueryWrapper<Match>()
                .in("queue_id", GameDataService.kiwiQueueIds())
                .orderByAsc("game_creation"));
        List<Long> matchIds = kiwiMatches.stream().map(Match::getId).toList();
        Map<Long, List<MatchParticipant>> participantsByMatch = matchIds.isEmpty() ? Map.of()
                : participantMapper.selectList(new QueryWrapper<MatchParticipant>()
                        .in("match_id", matchIds)).stream()
                        .collect(java.util.stream.Collectors.groupingBy(MatchParticipant::getMatchId));

        // ---- 版本轴：KIWI 对局的版本列表（升序）与选中版本 ----
        TreeMap<String, Integer> versionIndex = new TreeMap<>();
        for (Match match : kiwiMatches) {
            String v = GameVersionNormalizer.normalize(match.getGameVersion());
            if (v != null) {
                versionIndex.merge(v, 1, Integer::sum);
            }
        }
        List<String> versions = new ArrayList<>(versionIndex.keySet());
        String selected = resolveSelected(version, versions);

        // ---- 覆盖率：纳入/无强化计数（主扫描范围内）；队列外排除计数走独立轻量查询 ----
        int includedGames = 0;
        int kiwiNoAugmentGames = 0;
        for (Match match : kiwiMatches) {
            boolean anyAugment = participantsByMatch.getOrDefault(match.getId(), List.of()).stream()
                    .anyMatch(p -> !distinctAugments(p.getStatsJson()).isEmpty());
            if (anyAugment) {
                includedGames++;
            } else {
                kiwiNoAugmentGames++;
            }
        }
        int outsideQueueAugmentRows = countOutsideQueueAugmentRows(matchIds);

        // ---- 聚合：KIWI 对局的全部参与者行 → 英雄×强化×版本 ----
        // champAcc：championId → [局数, 胜场]（所选版本截面，出场率分母）
        Map<Integer, int[]> champAcc = new LinkedHashMap<>();
        // augAcc：championId → augmentId → [局数, 胜场]（所选版本）
        Map<Integer, Map<Integer, int[]>> augAcc = new LinkedHashMap<>();
        // trend：championId → augmentId → version → [局数, 胜场]（全版本，走势卡）
        Map<Integer, Map<Integer, TreeMap<String, int[]>>> trend = new LinkedHashMap<>();
        int processedRows = 0;
        for (Match match : kiwiMatches) {
            String normalized = GameVersionNormalizer.normalize(match.getGameVersion());
            boolean inSelected = selected != null && selected.equals(normalized);
            for (MatchParticipant p : participantsByMatch.getOrDefault(match.getId(), List.of())) {
                List<Integer> ids = distinctAugments(p.getStatsJson());
                if (ids.isEmpty()) {
                    continue;   // 无强化行不进英雄分母（双条件之二）
                }
                processedRows++;
                // 英雄层（出场率分母）：仅所选版本截面计入
                accumulate(champAcc.computeIfAbsent(p.getChampionId(), k -> new int[2]),
                        Boolean.TRUE.equals(p.getWin()), inSelected);
                for (int augId : ids) {
                    // 所选版本截面（胜率/出场率分母在此累计）
                    if (inSelected) {
                        accumulate(augAcc.computeIfAbsent(p.getChampionId(), k -> new LinkedHashMap<>())
                                .computeIfAbsent(augId, k -> new int[2]),
                                Boolean.TRUE.equals(p.getWin()), true);
                    }
                    // 全版本逐版本序列（走势卡数据）
                    if (normalized != null) {
                        accumulate(trend.computeIfAbsent(p.getChampionId(), k -> new LinkedHashMap<>())
                                .computeIfAbsent(augId, k -> new TreeMap<>())
                                .computeIfAbsent(normalized, k -> new int[2]),
                                Boolean.TRUE.equals(p.getWin()), true);
                    }
                }
            }
        }

        // ---- 组装英雄条目（按所选版本纳入对局数降序；名称经 GameDataService 全站唯一出口）----
        // 以 augAcc 为遍历源：它只在所选版本截面累计（champAcc/trend 含非所选版本行，
        // 遍历它们会产出"该版本没玩过的英雄"空条目）
        List<SeasonArchiveResponse.ChampionEntry> champions = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, int[]>> e : augAcc.entrySet()) {
            int champId = e.getKey();
            Map<Integer, int[]> augs = e.getValue();
            int games = champAcc.get(champId)[0];
            Map<Integer, TreeMap<String, int[]>> champTrend = trend.getOrDefault(champId, Map.of());
            List<SeasonArchiveResponse.AugmentEntry> augEntries = new ArrayList<>();
            for (Map.Entry<Integer, int[]> a : augs.entrySet()) {
                int augGames = a.getValue()[0];
                int augWins = a.getValue()[1];
                // 走势序列：版本 → [局数, 胜场]（TreeMap 升序；List<Integer> 便于 JSON 序列化）
                Map<String, List<Integer>> byVersion = new LinkedHashMap<>();
                champTrend.getOrDefault(a.getKey(), new TreeMap<>()).forEach((v, cell) ->
                        byVersion.put(v, List.of(cell[0], cell[1])));
                augEntries.add(SeasonArchiveResponse.AugmentEntry.builder()
                        .augmentId(a.getKey())
                        .games(augGames)
                        .wins(augWins)
                        .winRate(augGames > 0 ? (double) augWins / augGames : 0.0)
                        .appearanceRate(games > 0 ? (double) augGames / games : 0.0)
                        .byVersion(byVersion)
                        .build());
            }
            // 强化默认按所选版本出场次数降序，同次数按胜率降序（防"2 局 100%"顶首屏的口径延伸）
            augEntries.sort(Comparator
                    .comparingInt((SeasonArchiveResponse.AugmentEntry x) -> x.getGames()).reversed()
                    .thenComparing(Comparator.comparingDouble(
                            (SeasonArchiveResponse.AugmentEntry x) -> x.getWinRate()).reversed()));
            champions.add(SeasonArchiveResponse.ChampionEntry.builder()
                    .championId(champId)
                    .championName(gameDataService.championName(champId))
                    .games(games)
                    .augments(augEntries)
                    .build());
        }
        // 英雄按所选版本纳入对局数降序（主玩英雄排前，spec #53）
        champions.sort(Comparator
                .comparingInt((SeasonArchiveResponse.ChampionEntry c) -> c.getGames()).reversed());

        log.info("Season archive computed: version={}, champions={}, rows={}, includedGames={}, "
                        + "kiwiNoAugment={}, outsideQueueRows={}, elapsed={}ms",
                selected, champions.size(), processedRows, includedGames,
                kiwiNoAugmentGames, outsideQueueAugmentRows, System.currentTimeMillis() - startTime);
        return SeasonArchiveResponse.builder()
                .versions(versions)
                .selectedVersion(selected)
                .coverage(SeasonArchiveResponse.Coverage.builder()
                        .includedGames(includedGames)
                        .kiwiNoAugmentGames(kiwiNoAugmentGames)
                        .outsideQueueAugmentRows(outsideQueueAugmentRows)
                        .build())
                .champions(champions)
                .build();
    }

    /**
     * 队列外排除计数（ADR 0014：独立轻量查询，不随主扫描全量加载）：
     * 只查"非 KIWI 队列 + 参与者行 stats_json 含 playerAugment1 键"的行数——
     * 不反序列化 JSON 值（LIKE 命中键名即视为带强化，0/null 值的行属罕见噪音可接受），
     * 与主扫描（KIWI 对局全量装配）互不拖累
     *
     * @param kiwiMatchIds 主扫描已装载的 KIWI 对局 ID（排除重复计数）
     * @return 队列不在白名单、但参与者行带强化的行数
     */
    private int countOutsideQueueAugmentRows(List<Long> kiwiMatchIds) {
        // 非 KIWI 对局的参与者行 = match_id 不在 KIWI 对局集合里的行；
        // 用 match 表排除法（not in）而非全量 participant 扫描
        QueryWrapper<Match> nonKiwi = new QueryWrapper<Match>()
                .notIn("queue_id", GameDataService.kiwiQueueIds())
                .select("id");
        List<Long> nonKiwiIds = matchMapper.selectList(nonKiwi).stream()
                .map(Match::getId).toList();
        if (nonKiwiIds.isEmpty()) {
            return 0;
        }
        // 轻量判定：stats_json LIKE 命中强化键名（只计键存在，不解析值）
        Long count = participantMapper.selectCount(new QueryWrapper<MatchParticipant>()
                .in("match_id", nonKiwiIds)
                .like("stats_json", "%playerAugment1%"));
        return count == null ? 0 : count.intValue();
    }

    /**
     * 累计 [局数, 胜场] 计数器（三个聚合位共用同一写法，消除三处同形重复）
     *
     * @param cell    [局数, 胜场] 计数器
     * @param win     本行是否胜
     * @param counted 是否计入（false 只初始化不累计——调用方控制条件过滤）
     */
    private void accumulate(int[] cell, boolean win, boolean counted) {
        if (!counted) {
            return;
        }
        cell[0]++;
        if (win) {
            cell[1]++;
        }
    }

    /**
     * 解析选中版本：请求指定（必须存在于库内版本列表）或默认最新有数据版本
     *
     * @param version  请求的归一主版本；null/空白 = 默认
     * @param versions 库内有数据的版本列表（升序）
     * @return 选中版本；库无数据时 null
     */
    private String resolveSelected(String version, List<String> versions) {
        if (versions.isEmpty()) {
            return null;
        }
        if (version != null && !version.isBlank()) {
            return versions.contains(version) ? version : versions.getLast();
        }
        return versions.getLast();
    }

    /**
     * 读取一行 stats 快照中的有效强化 ID 集合（去重、丢弃 0——双条件之二的判定原料）
     *
     * @param statsJson stats 快照原文
     * @return 去重后的非零强化 ID（保持出现顺序）
     */
    private List<Integer> distinctAugments(String statsJson) {
        List<Integer> values = statsReader.listVal(statsJson, AUGMENT_KEYS);
        // LinkedHashSet 去重保序：同局重复 ID 只计 1 次（防御性去重）
        return new ArrayList<>(new LinkedHashSet<>(values.stream()
                .filter(id -> id != null && id != 0)
                .toList()));
    }
}
