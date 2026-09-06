package com.leagueakari.controller;

import com.leagueakari.common.web.ApiResult;
import com.leagueakari.common.web.ClientDisconnectDetector;
import com.leagueakari.dto.team.DuoExtendedResponse;
import com.leagueakari.dto.team.DuoMatrixResponse;
import com.leagueakari.dto.team.LeaderboardResponse;
import com.leagueakari.dto.team.MemberCardResponse;
import com.leagueakari.dto.team.SeasonReportResponse;
import com.leagueakari.dto.team.TeamMembersResponse;
import com.leagueakari.dto.team.WeeklyReportResponse;
import com.leagueakari.riot.RiotMatchHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.Map;
import com.leagueakari.team.LeaderboardService;
import com.leagueakari.team.DuoStatsService;
import com.leagueakari.team.MemberStatsService;
import com.leagueakari.team.SeasonReportService;
import com.leagueakari.team.WeeklyAiCommentService;
import com.leagueakari.team.WeeklyReportService;

/**
 * 车队数据路由（路由层职责：参数校验与返回值封装，业务在按场景拆分的聚合服务）：
 * <ul>
 *   <li>GET /api/team/weekly?date= —— 车队周报统计（date 为该周任意一天，缺省=上一周；
 *       不含 AI 锐评——锐评经 SSE 端点单独流式拉取，工单 #33 / ADR 0007）</li>
 *   <li>GET /api/team/weekly/ai-comment?date= —— 周报 AI 锐评（SSE 流式，事件契约与单局分析一致）</li>
 *   <li>GET /api/team/leaderboards?dimension=&mode=&start=&end= —— 榜单中心单维度榜单</li>
 *   <li>GET /api/team/members —— roster 成员与车队对局出勤</li>
 *   <li>GET /api/team/members/{puuid} —— 成员卡（成长曲线 + 英雄基线对比）</li>
 *   <li>POST /api/team/backfill —— 触发 Riot 历史对局回填（异步）</li>
 * </ul>
 * 异常由全局异常处理器统一转换（HTTP 200 + 业务码）：roster 未配置 → 1101，
 * 维度未知 → 1104，非成员 → 1103，AI/外部依赖失败 → 4xxx
 */
@Slf4j
@RestController
@RequestMapping("/api/team")
public class TeamController {

    private final WeeklyReportService weeklyReportService;
    private final WeeklyAiCommentService weeklyAiCommentService;
    private final LeaderboardService leaderboardService;
    /** 搭档胜率矩阵（工单 #37） */
    private final DuoStatsService duoStatsService;
    private final MemberStatsService memberStatsService;
    /** 赛季报告（工单 #42）：手动触发，赛季边界人工指定 */
    private final SeasonReportService seasonReportService;
    private final RiotMatchHistoryService backfillService;

    public TeamController(WeeklyReportService weeklyReportService, WeeklyAiCommentService weeklyAiCommentService,
            LeaderboardService leaderboardService, DuoStatsService duoStatsService,
            MemberStatsService memberStatsService, SeasonReportService seasonReportService,
            RiotMatchHistoryService backfillService) {
        this.weeklyReportService = weeklyReportService;
        this.weeklyAiCommentService = weeklyAiCommentService;
        this.leaderboardService = leaderboardService;
        this.duoStatsService = duoStatsService;
        this.memberStatsService = memberStatsService;
        this.seasonReportService = seasonReportService;
        this.backfillService = backfillService;
    }

    /**
     * 车队周报统计：date 为该周内任意一天（ISO yyyy-MM-dd），缺省统计上一周。
     * 统计数据即时返回；AI 锐评不在本接口（生成耗时，走独立 SSE 端点）
     */
    @GetMapping("/weekly")
    public ApiResult<WeeklyReportResponse> weekly(@RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.success(weeklyReportService.weeklyReport(date));
    }

    /**
     * 周报 AI 锐评（SSE 流式）：周报统计先行渲染，锐评打字机逐字推送。
     * 事件契约与单局 AI 分析一致（start/chunk/reasoning/reasoning-reset/done/error，
     * 协议见 WeeklyAiCommentService javadoc）；校验失败（AI Key 未配置 4101）由全局
     * 异常处理器在 HTTP 响应阶段返回统一信封。
     * SseEmitter 生命周期回调打日志：连接完成/超时/异常是"无响应"排查的关键边界
     */
    @GetMapping(value = "/weekly/ai-comment", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter weeklyAiComment(@RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        long startTime = System.currentTimeMillis();
        log.info("Weekly AI comment requested: date={}", date);
        // 前置校验：API Key 未配置（4101）时在此抛出，避免流已建立再中断
        weeklyAiCommentService.validateAndConfigured();
        // SseEmitter 超时 5 分钟：与单局分析一致（流式生成整体耗时较长）
        SseEmitter emitter = new SseEmitter(300_000L);
        // 连接生命周期日志：正常结束/超时/异常各打一条，用于确认流是否被服务器侧正常收尾
        emitter.onCompletion(() -> log.info("Weekly AI comment SSE connection completed: date={}, elapsed={}ms",
                date, System.currentTimeMillis() - startTime));
        emitter.onTimeout(() -> log.warn("Weekly AI comment SSE connection timed out: date={}, elapsed={}ms",
                date, System.currentTimeMillis() - startTime));
        emitter.onError(e -> {
            // 客户端断开（关页面/刷新/网络断开）：预期现象，INFO 即可——service 层已停止推送
            if (ClientDisconnectDetector.isClientDisconnect(e)) {
                log.info("Weekly AI comment SSE client disconnected: date={}, elapsed={}ms",
                        date, System.currentTimeMillis() - startTime);
                return;
            }
            // 其余连接异常（服务端问题）：ERROR + 堆栈，保留排查线索
            log.error("Weekly AI comment SSE connection error: date={}, elapsed={}ms",
                    date, System.currentTimeMillis() - startTime, e);
        });
        // 流式锐评在线程池异步执行并推送事件，controller 立即返回响应头
        weeklyAiCommentService.streamComment(date, emitter);
        log.info("Weekly AI comment stream dispatched: date={}, elapsed={}ms",
                date, System.currentTimeMillis() - startTime);
        return emitter;
    }

    /**
     * 榜单中心：dimension 必填（mvp/criminal/feeder/carry/signature/attendance），
     * mode 为模式过滤，start/end 为毫秒时间戳范围（缺省全时段），
     * version 为主版本筛选（如 "16.15"，工单 #38）
     */
    @GetMapping("/leaderboards")
    public ApiResult<LeaderboardResponse> leaderboards(
            @RequestParam String dimension,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end,
            @RequestParam(required = false) String version) {
        return ApiResult.success(leaderboardService.leaderboard(dimension, mode, start, end, version));
    }

    /**
     * 搭档胜率矩阵（工单 #37 / spec #31）：N×N 对称矩阵（胜率 + 局数 + 人次胜负），
     * 只统计车队对局（与七榜同口径）。mode/start/end 过滤同榜单中心
     */
    @GetMapping("/duo-matrix")
    public ApiResult<DuoMatrixResponse> duoMatrix(
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end) {
        return ApiResult.success(duoStatsService.duoMatrix(mode, start, end));
    }

    /**
     * 组合扩展统计（工单 #41 / spec #31）：时段胜率（五档分桶）+ 常用阵容（≥2 人组合，
     * 按局数降序）。与搭档矩阵同口径（只统计车队对局，胜负按人次）
     */
    @GetMapping("/duo-extended")
    public ApiResult<DuoExtendedResponse> duoExtended(
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end) {
        return ApiResult.success(duoStatsService.duoExtended(mode, start, end));
    }

    /**
     * 赛季报告（工单 #42 / spec #32）：版本胜率曲线 + 英雄池漂移 + 高光时刻。
     * start/end 为赛季起止（毫秒时间戳，人工指定——不做自动判定/定时任务）；
     * 纯数据聚合即时返回（无 AI、无异步生成）
     */
    @GetMapping("/season-report")
    public ApiResult<SeasonReportResponse> seasonReport(
            @RequestParam Long start,
            @RequestParam Long end) {
        return ApiResult.success(seasonReportService.seasonReport(start, end));
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
