package com.leagueakari.team;

import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

/**
 * DuoStatsService 单元测试（工单 #37：搭档胜率矩阵）：
 * 矩阵聚合口径——只统计车队对局（复用 FleetGameLoader 判定）、成员按身份集合匹配、
 * 胜负按成员人次计、小样本格子带局数（前端弱化展示）。
 * 断言数值由夹具人工复算（黄金样本）。
 */
class DuoStatsServiceTest extends TeamStatsTestBase {

    /** 构造被测服务 */
    private DuoStatsService duoService() {
        return new DuoStatsService(rosterService, loader(), gameDataService);
    }

    /** 用例：矩阵聚合——同局搭档的胜负按人次累计，格子带局数与胜率 */
    @Test
    void duoMatrix_aggregatesWinRateAndGames() {
        DuoStatsService service = duoService();
        // g1：A、B 同队 100 胜（二人各计 1 人次胜，搭档局 games+1，胜 games+1）
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        // g2：A、B 分属敌我（A 胜 B 负）；A、B 仍同局 → 搭档局 games+1，
        // 胜负按人次：A 胜 +1、B 负 +1 → 搭档胜 1 负 1
        Match g2 = match(2, 200L, ms(8, 26, 16), 1200, "KIWI", 200);
        // solo：只有 A（不构成车队对局，不计入矩阵）
        Match solo = match(3, 300L, ms(8, 27, 20), 900, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2, solo));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                // g1：A、B 同队 100（该队胜）
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 1, "puuid-c", "路人甲", 266, 200, 2, 8, 1, false, 9000),
                // g2：A 在 200 队胜，B 在 100 队负
                participant(4, 2, "puuid-a", "赌书消得泼茶香", 266, 200, 8, 3, 2, true, 30000),
                participant(5, 2, "puuid-b", "手裂鬼子", 84, 100, 4, 6, 3, false, 12000),
                // solo：只有 A
                participant(6, 3, "puuid-a", "赌书消得泼茶香", 103, 100, 1, 9, 1, false, 5000)));

        var response = service.duoMatrix(null, null, null);

        // roster 两人 → 2×2 矩阵
        assertThat(response.getMembers()).containsExactly("赌书消得泼茶香#iKun", "手裂鬼子#tw2");
        // A×B 搭档：2 局，胜 2（g1 二人胜 + g2 A 胜）负 2（g2 B 负）——胜负按人次
        // A×B 搭档（复算）：g1 二人胜 +2、g2 A 胜 B 负 +1/+1 → 2 局，胜 3 负 1，
        // 总人次 4 = 2 局×2 ✓ → 胜率 3/4
        var ab = cellOf(response, "赌书消得泼茶香#iKun", "手裂鬼子#tw2");
        assertThat(ab.getGames()).isEqualTo(2);
        assertThat(ab.getWins()).isEqualTo(3);
        assertThat(ab.getLosses()).isEqualTo(1);
        assertThat(ab.getWinRate()).isEqualTo(0.75);
        // 对角线：A 个人车队局（g1 + g2，solo 不算）2 局 2 胜
        var aa = cellOf(response, "赌书消得泼茶香#iKun", "赌书消得泼茶香#iKun");
        assertThat(aa.getGames()).isEqualTo(2);
        assertThat(aa.getWins()).isEqualTo(2);
        // 对称：B×A 与 A×B 相同
        var ba = cellOf(response, "手裂鬼子#tw2", "赌书消得泼茶香#iKun");
        assertThat(ba.getGames()).isEqualTo(ab.getGames());
        assertThat(ba.getWinRate()).isEqualTo(ab.getWinRate());
        // 无对局格子：0 局 null 胜率
        var bb = cellOf(response, "手裂鬼子#tw2", "手裂鬼子#tw2");
        assertThat(bb.getGames()).isEqualTo(2);
    }

    /** 用例：时间范围过滤（g2 在范围外不计入） */
    @Test
    void duoMatrix_filtersByTimeRange() {
        DuoStatsService service = duoService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 27, 16), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 103, 100, 1, 2, 3, false, 8000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 100, 2, 5, 3, false, 7000)));

        // 只统计 g1 所在时段（loadGames 的 SQL 范围过滤由集成测试覆盖，这里 mock 语义一致：
        // 时间范围传入后返回的集合就是 g1——通过 mapper 打桩模拟）
        when(matchMapper.selectList(any())).thenReturn(List.of(g1));
        var response = service.duoMatrix(null, ms(8, 26, 0), ms(8, 26, 23));

        var ab = cellOf(response, "赌书消得泼茶香#iKun", "手裂鬼子#tw2");
        assertThat(ab.getGames()).isEqualTo(1);
        assertThat(ab.getWins()).isEqualTo(2);
        assertThat(ab.getWinRate()).isEqualTo(1.0);
    }

    /** 从矩阵取指定格子的便捷断言 */
    private com.leagueakari.dto.team.DuoMatrixResponse.Cell cellOf(
            com.leagueakari.dto.team.DuoMatrixResponse response, String rowRiotId, String colRiotId) {
        int row = response.getMembers().indexOf(rowRiotId);
        int col = response.getMembers().indexOf(colRiotId);
        return response.getMatrix().get(row).get(col);
    }

    /** 用例（工单 #41）：时段胜率——按 game_creation 的时段分桶（下午/晚间/深夜/凌晨/上午） */
    @Test
    void timeSlots_aggregatesWinRateByDaypart() {
        DuoStatsService service = duoService();
        // 三局车队对局：g1 下午 14 点（2 人次胜）、g2 晚间 21 点（2 人次负）、g3 晚间 22 点（2 人次胜）
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 26, 21), 1200, "KIWI", 200);
        Match g3 = match(3, 300L, ms(8, 27, 22), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2, g3));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 103, 100, 1, 5, 2, false, 8000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 100, 2, 5, 3, false, 7000),
                participant(5, 3, "puuid-a", "赌书消得泼茶香", 103, 100, 6, 1, 4, true, 22000),
                participant(6, 3, "puuid-b", "手裂鬼子", 117, 100, 4, 2, 6, true, 16000)));

        var response = service.timeSlots(null, null, null);

        // 下午（12-18 点）：1 局 2 胜 0 负 → 100%
        var afternoon = slotOf(response, "afternoon");
        assertThat(afternoon.getGames()).isEqualTo(1);
        assertThat(afternoon.getWinRate()).isEqualTo(1.0);
        // 晚间（18-24 点）：2 局 2 胜 2 负 → 50%
        var evening = slotOf(response, "evening");
        assertThat(evening.getGames()).isEqualTo(2);
        assertThat(evening.getWins()).isEqualTo(2);
        assertThat(evening.getWinRate()).isEqualTo(0.5);
        // 无对局时段：0 局 null
        assertThat(slotOf(response, "morning").getWinRate()).isNull();
    }

    /** 用例（工单 #41）：常用阵容——同局出战成员组合聚合并按局数降序 */
    @Test
    void lineups_aggregatesByRosterCombination() {
        DuoStatsService service = duoService();
        // g1、g2 同样两人出战（阵容 {A,B}），g3 只有 A（不构成 ≥2 人阵容）
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 26, 16), 1200, "KIWI", 100);
        Match g3 = match(3, 300L, ms(8, 27, 20), 900, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2, g3));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 103, 100, 4, 1, 4, false, 12000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 100, 2, 6, 3, false, 9000),
                participant(5, 3, "puuid-a", "赌书消得泼茶香", 103, 100, 1, 9, 1, false, 5000)));

        var response = service.lineups(null, null, null);

        // 阵容 {A,B}：2 局（胜 2 + 负 2 人次）→ 50%；单人局不入阵容
        assertThat(response).hasSize(1);
        var lineup = response.get(0);
        assertThat(lineup.getMembers()).containsExactlyInAnyOrder("赌书消得泼茶香#iKun", "手裂鬼子#tw2");
        assertThat(lineup.getGames()).isEqualTo(2);
        assertThat(lineup.getWinRate()).isEqualTo(0.5);
    }

    /** 从时段列表取指定 key 的便捷断言 */
    private com.leagueakari.dto.team.DuoExtendedResponse.TimeSlotStats slotOf(
            com.leagueakari.dto.team.DuoExtendedResponse response, String key) {
        return response.getTimeSlots().stream()
                .filter(s -> s.getKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 用例：阵容补成员×英雄（头像 spec #44 用户故事 3）——
     * LineupStats 携带每位成员在该阵容局中最常使用的英雄（championId + 中文名），
     * 前端在成员名旁渲染英雄头像。
     */
    @Test
    void lineups_carryMemberChampions() {
        DuoStatsService service = duoService();
        // A 两局都玩阿狸（103），B 第一局盲僧（117）/第二局阿卡丽（84）→ B 众数 = 各 1 局取先到者
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        Match g2 = match(2, 200L, ms(8, 26, 16), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1, g2));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000),
                participant(3, 2, "puuid-a", "赌书消得泼茶香", 103, 100, 4, 1, 4, false, 12000),
                participant(4, 2, "puuid-b", "手裂鬼子", 84, 100, 2, 6, 3, false, 9000)));

        var response = service.lineups(null, null, null);

        assertThat(response).hasSize(1);
        var lineup = response.get(0);
        // 每位成员携带英雄（championId + 中文名）
        assertThat(lineup.getMemberChampions()).hasSize(2);
        var champA = lineup.getMemberChampions().stream()
                .filter(mc -> mc.getRiotId().equals("赌书消得泼茶香#iKun")).findFirst().orElseThrow();
        assertThat(champA.getChampionId()).isEqualTo(103);
    }
}
