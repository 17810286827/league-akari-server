package com.leagueakari.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.dto.team.SeasonArchiveResponse;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 英雄×强化胜率统计测试（工单 #53 / spec：赛季资料 /season-archive）：
 * 聚合只测公开方法 seasonArchive(version) 的响应结构与数值——
 * 夹具沿用 TeamStatsTestBase 模式（mock mapper 返回"仿佛按 KIWI 队列条件查出的"行：
 * SQL 预筛 queue_id IN 白名单后语义上不可能出现非 KIWI 对局，覆盖率排除计数
 * 由独立轻量查询返回，与主扫描解耦）。
 * <p>口径断言与 CONTEXT 词条一一对应：全库参与者行（ADR 0014）、
 * 胜率分母=持有局数、出场率分母=英雄对局数、版本=归一主版本（ADR 0013）。</p>
 */
class AugmentWinRateServiceTest {

    /** KIWI 队列（海克斯乱斗，白名单 2400/2410/2450 之一） */
    private static final int KIWI_QUEUE = 2400;

    private final MatchMapper matchMapper = mock(MatchMapper.class);
    private final MatchParticipantMapper participantMapper = mock(MatchParticipantMapper.class);
    private final GameDataService gameDataService = mock(GameDataService.class);

    private AugmentWinRateService service;

    @BeforeEach
    void setUp() {
        // stats 读取门面用真实实现（口径契约有独立测试面），mapper/游戏数据全 mock
        service = new AugmentWinRateService(matchMapper, participantMapper,
                new ParticipantStatsReader(new ObjectMapper()), gameDataService);
        when(matchMapper.selectList(any())).thenReturn(List.of());
        when(participantMapper.selectList(any())).thenReturn(List.of());
        when(participantMapper.selectCount(any())).thenReturn(0L);
        when(gameDataService.championName(any(Integer.class))).thenReturn("测试英雄");
    }

    // ---- 夹具 ----

    /** 构造对局主表记录（默认 KIWI 队列） */
    private com.leagueakari.entity.Match match(long id, int queueId, String gameVersion) {
        com.leagueakari.entity.Match m = new com.leagueakari.entity.Match();
        m.setId(id);
        m.setGameId(1000 + id);
        m.setGameCreation(1_700_000_000_000L);
        m.setGameDuration(1500);
        m.setGameMode("ARAM");
        m.setGameType("MATCHED_GAME");
        m.setQueueId(queueId);
        m.setGameVersion(gameVersion);
        m.setWinnerTeamId(100);
        return m;
    }

    /** 构造参与者：statsJson 可携带 playerAugment1-6 */
    private com.leagueakari.entity.MatchParticipant participant(long id, long matchId, int champId,
                                                                boolean win, String augmentsJson) {
        com.leagueakari.entity.MatchParticipant p = new com.leagueakari.entity.MatchParticipant();
        p.setId(id);
        p.setMatchId(matchId);
        p.setPuuid("puuid-" + id);
        p.setSummonerName("player-" + id);
        p.setChampionId(champId);
        p.setTeamId(win ? 100 : 200);
        p.setKills(10);
        p.setDeaths(5);
        p.setAssists(10);
        p.setWin(win);
        p.setGoldEarned(10000);
        p.setCs(200);
        p.setStatsJson(augmentsJson == null ? "{}" : augmentsJson);
        return p;
    }

    /** stats 快照：给定的强化 ID 依次填入 playerAugment1..n */
    private String augments(int... ids) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"playerAugment").append(i + 1).append("\":").append(ids[i]);
        }
        return sb.append('}').toString();
    }

    // ---- 用例 ----

    @Test
    @DisplayName("双条件样本：主扫描只剩 KIWI 对局；无强化行不纳入；排除计数来自独立轻量查询")
    void sampleDualCondition() {
        // 主扫描已按 queue_id IN 白名单预筛：夹具只含 KIWI 对局（SQL 语义）
        when(matchMapper.selectList(any())).thenReturn(List.of(
                match(1, KIWI_QUEUE, "16.15.802.4387"),
                match(3, KIWI_QUEUE, "16.15.802.4387")));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                // 局1：孙悟空带 777（纳入）
                participant(1, 1, 62, true, augments(777)),
                // 局3：KIWI 队列孙悟空无强化（无强化行——不纳入英雄分母）
                participant(3, 3, 62, false, null)));
        // 独立轻量查询：队列外带强化的行数（预筛语义下与主扫描解耦，直接给定）
        when(participantMapper.selectCount(any())).thenReturn(1L);

        SeasonArchiveResponse res = service.seasonArchive(null);

        // 覆盖率：纳入 1 局（局1）；队列命中但无强化 1 局（局3）；队列外强化行 1（独立查询）
        assertThat(res.getCoverage().getIncludedGames()).isEqualTo(1);
        assertThat(res.getCoverage().getKiwiNoAugmentGames()).isEqualTo(1);
        assertThat(res.getCoverage().getOutsideQueueAugmentRows()).isEqualTo(1);

        // 悟空：纳入对局 1（只有局1 的行进英雄聚合）
        SeasonArchiveResponse.ChampionEntry champ = champOf(res, 62);
        assertThat(champ.getGames()).isEqualTo(1);
        SeasonArchiveResponse.AugmentEntry aug = augOf(champ, 777);
        assertThat(aug.getWins()).isEqualTo(1);
        assertThat(aug.getGames()).isEqualTo(1);
    }

    @Test
    @DisplayName("一局 6 强化各计一次；同局重复强化 ID 去重")
    void sixAugmentsCountOnceEachAndDedupe() {
        when(matchMapper.selectList(any())).thenReturn(List.of(match(1, KIWI_QUEUE, "16.15.802.4387")));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                // 一局：6 个槽位 = [777, 888, 777, 0, 999, 888] → 有效强化 {777, 888, 999} 各 1 次
                participant(1, 1, 62, true, augments(777, 888, 777, 0, 999, 888))));

        SeasonArchiveResponse res = service.seasonArchive(null);
        SeasonArchiveResponse.ChampionEntry champ = champOf(res, 62);
        // 去重后 3 个强化条目，每个 1 局 1 胜
        assertThat(champ.getAugments()).hasSize(3);
        assertThat(augOf(champ, 777).getGames()).isEqualTo(1);
        assertThat(augOf(champ, 888).getGames()).isEqualTo(1);
        assertThat(augOf(champ, 999).getGames()).isEqualTo(1);
        // 出场率：分子 1（去重后 777 只 1 次）÷ 分母 1 局 = 100%
        assertThat(augOf(champ, 777).getGames()).isEqualTo(1);
        assertThat(champ.getGames()).isEqualTo(1);
    }

    @Test
    @DisplayName("胜率分母=持有局数；出场率分母=英雄对局数（两个分母刻意不同）")
    void twoDenominators() {
        when(matchMapper.selectList(any())).thenReturn(List.of(
                match(1, KIWI_QUEUE, "16.15.802.4387"),
                match(2, KIWI_QUEUE, "16.15.802.4387"),
                match(3, KIWI_QUEUE, "16.15.802.4387")));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                // 悟空 3 局（分母 3）：局1 胜 + 带 777；局2 胜 不带；局3 负 不带
                participant(1, 1, 62, true, augments(777)),
                participant(2, 2, 62, true, augments(888)),
                participant(3, 3, 62, false, augments(888))));

        SeasonArchiveResponse res = service.seasonArchive("16.15");
        SeasonArchiveResponse.ChampionEntry champ = champOf(res, 62);
        assertThat(champ.getGames()).isEqualTo(3);
        // 胜率：777 = 1/1 = 100%（不是 1/3）；888 = 1/2 = 50%（局2 胜 + 局3 负）
        SeasonArchiveResponse.AugmentEntry a777 = augOf(champ, 777);
        assertThat(a777.getWins()).isEqualTo(1);
        assertThat(a777.getGames()).isEqualTo(1);
        assertThat(a777.getWinRate()).isEqualTo(1.0);
        SeasonArchiveResponse.AugmentEntry a888 = augOf(champ, 888);
        assertThat(a888.getWins()).isEqualTo(1);
        assertThat(a888.getGames()).isEqualTo(2);
        assertThat(a888.getWinRate()).isEqualTo(0.5);
        // 出场率：777 = 1/3；888 = 2/3
        assertThat(a777.getAppearanceRate()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(a888.getAppearanceRate()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("版本归一分桶：16.15.802 与 16.15.900 同为 16.15；不同主版本分开")
    void versionBucketing() {
        when(matchMapper.selectList(any())).thenReturn(List.of(
                match(1, KIWI_QUEUE, "16.15.802.4387"),
                match(2, KIWI_QUEUE, "16.15.900.0001"),
                match(3, KIWI_QUEUE, "16.14.700.0002")));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, 62, true, augments(777)),
                participant(2, 2, 62, false, augments(777)),
                participant(3, 3, 62, true, augments(777))));

        SeasonArchiveResponse res = service.seasonArchive(null);
        // 版本列表：库里有数据的版本，升序
        assertThat(res.getVersions()).containsExactly("16.14", "16.15");
        // 16.15 视图：悟空 2 局；16.14 的行不进
        assertThat(res.getSelectedVersion()).isEqualTo("16.15");
        SeasonArchiveResponse.ChampionEntry champ = champOf(res, 62);
        assertThat(champ.getGames()).isEqualTo(2);
        // 逐版本序列（走势卡数据）：777 在 16.14 = 1局1胜、16.15 = 2局1胜
        SeasonArchiveResponse.AugmentEntry a777 = augOf(champ, 777);
        assertThat(a777.getByVersion()).containsKeys("16.14", "16.15");
        assertThat(a777.getByVersion().get("16.14").get(0)).isEqualTo(1);
        assertThat(a777.getByVersion().get("16.14").get(1)).isEqualTo(1);
        assertThat(a777.getByVersion().get("16.15").get(0)).isEqualTo(2);
        assertThat(a777.getByVersion().get("16.15").get(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("默认版本 = 最新有数据版本；指定版本过滤该版本截面")
    void defaultLatestVersion() {
        when(matchMapper.selectList(any())).thenReturn(List.of(
                match(1, KIWI_QUEUE, "16.13.100.1"),
                match(2, KIWI_QUEUE, "16.15.802.4387")));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, 62, true, augments(777)),
                participant(2, 2, 92, false, augments(888))));

        // 省略 version：默认 16.15（最新），悟空的 16.13 行不进截面
        SeasonArchiveResponse res = service.seasonArchive(null);
        assertThat(res.getSelectedVersion()).isEqualTo("16.15");
        assertThat(res.getChampions()).hasSize(1);
        assertThat(res.getChampions().get(0).getChampionId()).isEqualTo(92);

        // 指定 16.13：锐雯行不进截面
        SeasonArchiveResponse old = service.seasonArchive("16.13");
        assertThat(old.getSelectedVersion()).isEqualTo("16.13");
        assertThat(old.getChampions()).hasSize(1);
        assertThat(old.getChampions().get(0).getChampionId()).isEqualTo(62);
    }

    @Test
    @DisplayName("空数据：无海克斯对局时返回空结构而非报错")
    void emptyData() {
        SeasonArchiveResponse res = service.seasonArchive(null);
        assertThat(res.getVersions()).isEmpty();
        assertThat(res.getChampions()).isEmpty();
        assertThat(res.getSelectedVersion()).isNull();
        assertThat(res.getCoverage().getIncludedGames()).isZero();
    }

    @Test
    @DisplayName("英雄列表按纳入对局数降序；强化默认按出场次数降序")
    void ordering() {
        when(matchMapper.selectList(any())).thenReturn(List.of(
                match(1, KIWI_QUEUE, "16.15.1.1"),
                match(2, KIWI_QUEUE, "16.15.1.1"),
                match(3, KIWI_QUEUE, "16.15.1.1"),
                match(4, KIWI_QUEUE, "16.15.1.1")));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                // 锐雯 3 局、悟空 1 局 → 锐雯排前
                participant(1, 1, 92, true, augments(777)),
                participant(2, 2, 92, true, augments(777)),
                participant(3, 3, 92, false, augments(888)),
                participant(4, 4, 62, true, augments(777, 888, 999))));

        SeasonArchiveResponse res = service.seasonArchive("16.15");
        assertThat(res.getChampions()).extracting(SeasonArchiveResponse.ChampionEntry::getChampionId)
                .containsExactly(92, 62);
        // 锐雯的强化：777 出场 2 次 > 888 出场 1 次
        SeasonArchiveResponse.ChampionEntry riven = champOf(res, 92);
        assertThat(riven.getAugments()).extracting(SeasonArchiveResponse.AugmentEntry::getAugmentId)
                .startsWith(777);
    }

    // ---- 断言辅助 ----

    private SeasonArchiveResponse.ChampionEntry champOf(SeasonArchiveResponse res, int championId) {
        return res.getChampions().stream()
                .filter(c -> c.getChampionId() == championId)
                .findFirst().orElseThrow(() -> new AssertionError("英雄不存在: " + championId));
    }

    private SeasonArchiveResponse.AugmentEntry augOf(SeasonArchiveResponse.ChampionEntry champ, int augmentId) {
        return champ.getAugments().stream()
                .filter(a -> a.getAugmentId() == augmentId)
                .findFirst().orElseThrow(() -> new AssertionError("强化不存在: " + augmentId));
    }
}
