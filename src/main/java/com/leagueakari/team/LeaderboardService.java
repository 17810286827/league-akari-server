package com.leagueakari.team;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.dto.team.LeaderboardResponse;
import com.leagueakari.dto.team.WeeklyReportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 榜单中心服务（TeamStatsService 拆分后的榜单入口）：
 * 单维度榜单查询，装载与七榜单口径委托共享组件（FleetGameLoader / BoardEngine）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    /** 榜单维度全集：与各榜单一一对应（用于维度参数校验） */
    private static final Set<String> DIMENSIONS =
            Set.of("mvp", "opscore", "criminal", "feeder", "carry", "signature", "attendance");

    private final TeamRosterService rosterService;
    /** 车队对局装载器：装载与车队局判定的共享口径 */
    private final FleetGameLoader gameLoader;
    /** 七榜单计算引擎：与周报共享口径 */
    private final BoardEngine boardEngine;

    /**
     * 榜单中心：单一维度榜单（与周报共享口径引擎）
     *
     * @param dimension mvp / opscore / criminal / feeder / carry / signature / attendance
     * @param gameMode  模式过滤（game_mode 精确匹配）；null 表示全部模式
     * @param startMs   范围起始（含）；null 表示不限
     * @param endMs     范围结束（不含）；null 表示不限
     * @return 榜单数据（已排序）
     * @throws IllegalArgumentException 维度未知或车队名单未配置
     */
    public LeaderboardResponse leaderboard(String dimension, String gameMode, Long startMs, Long endMs) {
        if (dimension == null || !DIMENSIONS.contains(dimension)) {
            throw new BizException(ErrorCode.UNKNOWN_DIMENSION, "未知榜单维度：" + dimension + "，可选：" + DIMENSIONS);
        }
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        List<GameData> fleetGames = gameLoader.loadGames(startMs, endMs, gameMode, true).stream()
                .filter(g -> gameLoader.isFleet(g, roster)).toList();
        BoardEngine.Boards boards = boardEngine.computeBoards(fleetGames, roster);
        List<WeeklyReportResponse.BoardEntry> entries = switch (dimension) {
            case "mvp" -> boards.mvp();
            case "opscore" -> boards.opScore();
            case "criminal" -> boards.criminal();
            case "feeder" -> boards.feeder();
            case "carry" -> boards.carry();
            case "signature" -> boards.signature();
            case "attendance" -> boards.attendance();
            default -> List.of();
        };
        return LeaderboardResponse.builder()
                .dimension(dimension)
                .startMs(startMs)
                .endMs(endMs)
                .gameMode(gameMode)
                .entries(entries)
                .build();
    }
}
