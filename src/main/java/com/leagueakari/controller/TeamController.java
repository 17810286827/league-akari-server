package com.leagueakari.controller;

import com.leagueakari.common.web.ApiResult;
import com.leagueakari.dto.team.LeaderboardResponse;
import com.leagueakari.dto.team.MemberCardResponse;
import com.leagueakari.dto.team.TeamMembersResponse;
import com.leagueakari.dto.team.WeeklyReportResponse;
import com.leagueakari.riot.RiotMatchHistoryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import com.leagueakari.team.LeaderboardService;
import com.leagueakari.team.MemberStatsService;
import com.leagueakari.team.WeeklyReportService;

/**
 * 车队数据路由（路由层职责：参数校验与返回值封装，业务在按场景拆分的聚合服务）：
 * <ul>
 *   <li>GET /api/team/weekly?date= —— 车队周报（date 为该周任意一天，缺省=上一周）</li>
 *   <li>GET /api/team/leaderboards?dimension=&mode=&start=&end= —— 榜单中心单维度榜单</li>
 *   <li>GET /api/team/members —— roster 成员与车队对局出勤</li>
 *   <li>GET /api/team/members/{puuid} —— 成员卡（成长曲线 + 英雄基线对比）</li>
 *   <li>POST /api/team/backfill —— 触发 Riot 历史对局回填（异步）</li>
 * </ul>
 * 异常由全局异常处理器统一转换（HTTP 200 + 业务码）：roster 未配置 → 1101，
 * 维度未知 → 1104，非成员 → 1103，AI/外部依赖失败 → 4xxx
 */
@RestController
@RequestMapping("/api/team")
public class TeamController {

    private final WeeklyReportService weeklyReportService;
    private final LeaderboardService leaderboardService;
    private final MemberStatsService memberStatsService;
    private final RiotMatchHistoryService backfillService;

    public TeamController(WeeklyReportService weeklyReportService, LeaderboardService leaderboardService,
            MemberStatsService memberStatsService, RiotMatchHistoryService backfillService) {
        this.weeklyReportService = weeklyReportService;
        this.leaderboardService = leaderboardService;
        this.memberStatsService = memberStatsService;
        this.backfillService = backfillService;
    }

    /**
     * 车队周报：date 为该周内任意一天（ISO yyyy-MM-dd），缺省统计上一周
     */
    @GetMapping("/weekly")
    public ApiResult<WeeklyReportResponse> weekly(@RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.success(weeklyReportService.weeklyReport(date));
    }

    /**
     * 榜单中心：dimension 必填（mvp/criminal/feeder/carry/signature/attendance），
     * mode 为模式过滤，start/end 为毫秒时间戳范围（缺省全时段）
     */
    @GetMapping("/leaderboards")
    public ApiResult<LeaderboardResponse> leaderboards(
            @RequestParam String dimension,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end) {
        return ApiResult.success(leaderboardService.leaderboard(dimension, mode, start, end));
    }

    /**
     * 车队成员列表与全时段车队对局出勤
     */
    @GetMapping("/members")
    public ApiResult<TeamMembersResponse> members() {
        return ApiResult.success(memberStatsService.members());
    }

    /**
     * 成员卡：成长曲线（近 8 周）+ 英雄基线对比（全时段）
     */
    @GetMapping("/members/{puuid}")
    public ApiResult<MemberCardResponse> memberCard(@PathVariable String puuid) {
        return ApiResult.success(memberStatsService.memberCard(puuid));
    }

    /**
     * 触发 Riot 历史对局回填（异步执行，立即返回）；重复触发返回 started=false
     */
    @PostMapping("/backfill")
    public ApiResult<Map<String, Object>> backfill() {
        boolean started = backfillService.startBackfill();
        return ApiResult.success(Map.of("started", started));
    }
}
