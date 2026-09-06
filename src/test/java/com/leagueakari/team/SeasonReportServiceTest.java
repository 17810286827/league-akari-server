package com.leagueakari.team;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.dto.team.SeasonReportResponse;
import com.leagueakari.entity.Match;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SeasonReportService 单元测试（工单 #42：赛季报告）：
 * 版本胜率序列（复用 GameVersionNormalizer 归一）、英雄池漂移（成员×版本分布）、
 * 高光时刻（复用名场面口径——单局最高击杀/最惨连败）、赛季边界人工指定。
 * 断言数值由夹具人工复算。
 */
class SeasonReportServiceTest extends TeamStatsTestBase {

    /** 被测服务（依赖与 DuoStats 同构：roster + loader + gameData） */
    private SeasonReportService seasonService() {
        return new SeasonReportService(rosterService, loader(), gameDataService);
    }

    /** 用例：版本胜率序列——按主版本聚合（16.15 两局 + 16.14 一局），胜负按人次 */
    @Test
    void seasonReport_versionWinRates() {
        SeasonReportService service = seasonService();
        Match g1 = match(1, 100L, ms(6, 10, 14), 1200, "KIWI", 100);   // 16.15
        Match g2 = match(2, 200L, ms(6, 12, 16), 1200, "KIWI", 200);   // 16.15
        Match g3 = match(3, 300L, ms(7, 1, 20), 1200, "KIWI", 100);    // 16.14
        g1.setGameVersion("16.15.802.4387");
        g2.setGameVersion("16.15.803.1234");
        g3.setGameVersion("16.14.801.9999");
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2, g3));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 103, 200, 1, 5, 2, false, 8000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 100, 2, 5, 3, false, 7000),
                participant(5, 3, "puuid-a", "赌书消得泼茶香", 103, 100, 6, 1, 4, true, 22000),
                participant(6, 3, "puuid-b", "手裂鬼子", 117, 100, 4, 2, 6, true, 16000)));

        SeasonReportResponse report = service.seasonReport(ms(6, 1, 0), ms(8, 1, 0));

        // 版本序列：16.14 → 16.15（升序），16.14 一局 2 人次全胜；16.15 两局 2 胜 2 负
        assertThat(report.getVersionStats()).hasSize(2);
        SeasonReportResponse.VersionStat v14 = report.getVersionStats().get(0);
        assertThat(v14.getVersion()).isEqualTo("16.14");
        assertThat(v14.getGames()).isEqualTo(1);
        assertThat(v14.getWinRate()).isEqualTo(1.0);
        SeasonReportResponse.VersionStat v15 = report.getVersionStats().get(1);
        assertThat(v15.getVersion()).isEqualTo("16.15");
        assertThat(v15.getGames()).isEqualTo(2);
        assertThat(v15.getWinRate()).isEqualTo(0.5);
        // 总览
        assertThat(report.getTotalGames()).isEqualTo(3);
        assertThat(report.getTotalWinRate()).isEqualTo(4.0 / 6);
    }

    /** 用例：英雄池漂移——成员 × 版本的英雄分布（每版本玩什么、几局） */
    @Test
    void seasonReport_championPoolDrift() {
        SeasonReportService service = seasonService();
        Match g1 = match(1, 100L, ms(6, 10, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(7, 1, 20), 1200, "KIWI", 100);
        g1.setGameVersion("16.14.801.9999");
        g2.setGameVersion("16.15.802.4387");
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 266, 100, 6, 1, 4, true, 22000),
                participant(4, 2, "puuid-b", "手裂鬼子", 117, 100, 4, 2, 6, true, 16000)));
        when(gameDataService.championName(103)).thenReturn("阿狸");
        when(gameDataService.championName(117)).thenReturn("盲僧");
        when(gameDataService.championName(266)).thenReturn("锐雯");

        SeasonReportResponse report = service.seasonReport(ms(6, 1, 0), ms(8, 1, 0));

        // A 的漂移：16.14 玩阿狸 1 局 → 16.15 玩锐雯 1 局；B 两版本都玩盲僧
        SeasonReportResponse.MemberDrift driftA = report.getMemberDrifts().stream()
                .filter(d -> d.getRiotId().equals("赌书消得泼茶香#iKun")).findFirst().orElseThrow();
        assertThat(driftA.getVersions()).hasSize(2);
        assertThat(driftA.getVersions().get(0).getChampions())
                .singleElement()
                .satisfies(c -> {
                    org.assertj.core.api.Assertions.assertThat(c.getChampion()).isEqualTo("阿狸");
                    org.assertj.core.api.Assertions.assertThat(c.getGames()).isEqualTo(1);
                });
        SeasonReportResponse.MemberDrift driftB = report.getMemberDrifts().stream()
                .filter(d -> d.getRiotId().equals("手裂鬼子#tw2")).findFirst().orElseThrow();
        assertThat(driftB.getVersions().get(0).getChampions()).hasSize(1);
        assertThat(driftB.getVersions().get(0).getChampions().get(0).getChampion()).isEqualTo("盲僧");
        assertThat(driftB.getVersions().get(1).getChampions()).hasSize(1);
        assertThat(driftB.getVersions().get(1).getChampions().get(0).getChampion()).isEqualTo("盲僧");
    }

    /**
     * 用例：英雄池漂移补 championId（头像 spec #44 用户故事 1）——
     * ChampionCount 携带英雄 ID，前端渲染 Data Dragon 头像（缺失回退文字）。
     */
    @Test
    void seasonReport_championDriftCarriesChampionId() {
        SeasonReportService service = seasonService();
        Match g1 = match(1, 100L, ms(6, 10, 14), 1200, "KIWI", 100);
        g1.setGameVersion("16.14.801.9999");
        when(matchMapper.selectList(any())).thenReturn(List.of(g1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000)));
        when(gameDataService.championName(103)).thenReturn("阿狸");
        when(gameDataService.championName(117)).thenReturn("盲僧");

        SeasonReportResponse report = service.seasonReport(ms(6, 1, 0), ms(8, 1, 0));

        // 每个英雄分布条目携带 championId（前端头像渲染依据）
        report.getMemberDrifts().forEach(drift ->
                drift.getVersions().forEach(vc ->
                        vc.getChampions().forEach(c ->
                                assertThat(c.getChampionId()).isNotNull())));
        // 抽查：赌书消得泼茶香 16.14 的阿狸 = 103
        SeasonReportResponse.MemberDrift driftA = report.getMemberDrifts().stream()
                .filter(d -> d.getRiotId().equals("赌书消得泼茶香#iKun")).findFirst().orElseThrow();
        assertThat(driftA.getVersions().get(0).getChampions().get(0).getChampionId()).isEqualTo(103);
    }

    /** 用例：高光时刻——单局最高击杀（复用名场面口径） */
    @Test
    void seasonReport_highlights() {
        SeasonReportService service = seasonService();
        Match g1 = match(1, 100L, ms(6, 10, 14), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 18, 3, 4, true, 40000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000)));
        when(gameDataService.championName(103)).thenReturn("阿狸");

        SeasonReportResponse report = service.seasonReport(ms(6, 1, 0), ms(8, 1, 0));

        assertThat(report.getMostKills()).isNotNull();
        assertThat(report.getMostKills().getDetail()).contains("18").contains("赌书消得泼茶香#iKun");
    }

    /** 用例：赛季起止人工指定——范围外对局不计入 */
    @Test
    void seasonReport_requiresExplicitRange() {
        SeasonReportService service = seasonService();
        Match g1 = match(1, 100L, ms(6, 10, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(9, 1, 14), 1200, "KIWI", 100);   // 赛季外
        when(matchMapper.selectList(any())).thenReturn(List.of(g1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000)));

        SeasonReportResponse report = service.seasonReport(ms(6, 1, 0), ms(8, 1, 0));

        assertThat(report.getTotalGames()).isEqualTo(1);
    }
}
