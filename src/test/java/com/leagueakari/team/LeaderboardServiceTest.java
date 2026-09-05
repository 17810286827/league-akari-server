package com.leagueakari.team;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.dto.team.LeaderboardResponse;
import com.leagueakari.entity.Match;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * LeaderboardService 单元测试：榜单维度路由与未知维度拒绝
 * <p>断言数值与拆分前 TeamStatsServiceTest 逐字一致。</p>
 */
class LeaderboardServiceTest extends TeamStatsTestBase {

    /** 用例：榜单按维度路由（与周报共享口径），未知维度抛业务异常 */
    @Test
    void leaderboard_routesDimensionAndRejectsUnknown() {
        LeaderboardService svc = leaderboardService();
        Match g1 = match(1, 100L, ms(8, 26, 14), 1200, "KIWI", 100);
        when(matchMapper.selectList(any())).thenReturn(List.of(g1));
        when(participantMapper.selectList(any())).thenReturn(List.of(
                participant(1, 1, "puuid-a", "赌书消得泼茶香", 103, 100, 5, 2, 5, true, 20000),
                participant(2, 1, "puuid-b", "手裂鬼子", 117, 100, 3, 4, 4, true, 15000)));
        when(mvpMapper.selectList(any())).thenReturn(List.of());
        stubScoresByGame(Map.of());

        LeaderboardResponse board = svc.leaderboard("attendance", null, null, null);

        assertThat(board.getDimension()).isEqualTo("attendance");
        assertThat(board.getEntries()).hasSize(2);
        assertThat(board.getEntries().get(0).getValue()).isEqualTo(1.0);

        assertThatThrownBy(() -> svc.leaderboard("no-such-dim", null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("维度");
    }
}
