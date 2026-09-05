package com.leagueakari.controller;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.common.web.ApiResult;
import com.leagueakari.common.web.ClientDisconnectDetector;
import com.leagueakari.dto.match.MatchDetailResponse;
import com.leagueakari.dto.match.MatchSummaryResponse;
import com.leagueakari.dto.match.MatchSyncRequest;
import com.leagueakari.dto.common.PageResponse;
import com.leagueakari.dto.match.TimelineSyncRequest;
import com.leagueakari.match.AiAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;
import com.leagueakari.match.MatchIngestService;
import com.leagueakari.match.MatchQueryService;
import com.leagueakari.match.MatchTimelineService;

/**
 * 对局 API：同步写入（幂等）与查询
 * <p>路由层职责：参数校验（@Valid / @RequestParam）与返回值封装（统一 ApiResult）；
 * 业务逻辑下沉 service 层（写入走 MatchIngestService，查询走 MatchQueryService），
 * 异常由全局异常处理器统一转换（HTTP 200 + 业务码）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchController {

    /** 对局摄取：幂等保存（重复推送同一 gameId 不会产生重复数据） */
    private final MatchIngestService matchIngestService;
    /** 对局查询：列表/详情的视图组装 */
    private final MatchQueryService matchQueryService;
    private final MatchTimelineService matchTimelineService;
    private final AiAnalysisService aiAnalysisService;

    /** 接收对局同步推送，幂等写入 */
    @PostMapping
    public ApiResult<Void> syncMatch(@Valid @RequestBody MatchSyncRequest request) {
        // 幂等保存：重复推送同一 gameId 不会产生重复数据；
        // 局后播报由落库事务提交后的"对局已同步"事件触发（控制器不再承载编排）
        matchIngestService.saveMatch(request);
        // 同步接口契约：成功即返回 code=0（无业务数据，data 缺省）
        return ApiResult.success();
    }

    /**
     * 分页查询对局列表：支持 queueId / puuid / summonerName / startTime / endTime / championId 筛选；
     * puuid 与 summonerName 二选一（只能查询指定玩家，都缺失时返回空页）；
     * championId 过滤"该玩家本局使用的英雄"，与其余筛选叠加
     */
    @GetMapping
    public ApiResult<PageResponse<MatchSummaryResponse>> listMatches(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) Integer queueId,
            @RequestParam(required = false) String puuid,
            @RequestParam(required = false) String summonerName,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime,
            @RequestParam(required = false) Integer championId) {
        // 筛选参数均为可选，page/pageSize 缺省时取默认值 1/20；业务校验与查询下沉 service 层
        return ApiResult.success(
                matchQueryService.pageMatches(page, pageSize, queueId, puuid, summonerName, startTime, endTime, championId));
    }

    /** 查询对局详情，不存在返回业务码 2001（HTTP 仍为 200） */
    @GetMapping("/{gameId}")
    public ApiResult<MatchDetailResponse> getMatchDetail(@PathVariable Long gameId) {
        // 对局不存在时由 service 抛出 BizException(MATCH_NOT_FOUND)，全局处理器转为统一响应
        return ApiResult.success(matchQueryService.getMatchDetail(gameId));
    }

    /**
     * AI 对局表现分析（SSE 流式）：取本局详情组装数据摘要，流式调用 opencode go 模型，
     * 增量推送分析文本（前端打字机效果；结果 JVM 缓存 2 分钟，命中时 start 事件 fromCache=true）。
     * 事件协议见 AiAnalysisService.analyzeStream；校验失败（无 API Key / 对局不存在）
     * 由全局异常处理器在 HTTP 响应阶段返回统一响应（业务码 4101/2001）。
     * SseEmitter 生命周期回调打日志：连接完成/超时/异常是"无响应"排查的关键边界
     */
    @PostMapping(value = "/{gameId}/ai-analysis", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeMatch(@PathVariable Long gameId) {
        long startTime = System.currentTimeMillis();
        log.info("AI analysis requested: gameId={}", gameId);
        // 前置校验：API Key 未配置（503）或对局不存在（404）时在此抛出，避免流已建立再中断
        aiAnalysisService.validateAndConfigured(gameId);
        // SseEmitter 超时 5 分钟：流式分析整体耗时较长，超时后连接自动关闭
        SseEmitter emitter = new SseEmitter(300_000L);
        // 连接生命周期日志：正常结束/超时/异常各打一条，用于确认流是否被服务器侧正常收尾
        emitter.onCompletion(() -> log.info("AI analysis SSE connection completed: gameId={}, elapsed={}ms",
                gameId, System.currentTimeMillis() - startTime));
        emitter.onTimeout(() -> log.warn("AI analysis SSE connection timed out: gameId={}, elapsed={}ms",
                gameId, System.currentTimeMillis() - startTime));
        emitter.onError(e -> {
            // 客户端断开（关页面/刷新/网络断开导致的 Broken pipe）：预期现象，
            // INFO 一条即可——service 层已记录停止推送，这里不再打 ERROR 堆栈重复刷屏
            if (ClientDisconnectDetector.isClientDisconnect(e)) {
                log.info("AI analysis SSE client disconnected: gameId={}, elapsed={}ms",
                        gameId, System.currentTimeMillis() - startTime);
                return;
            }
            // 其余连接异常（服务端问题）：ERROR + 堆栈，保留排查线索
            log.error("AI analysis SSE connection error: gameId={}, elapsed={}ms",
                    gameId, System.currentTimeMillis() - startTime, e);
        });
        // 流式分析在线程池异步执行并推送事件，controller 立即返回响应头
        aiAnalysisService.analyzeStream(gameId, emitter);
        log.info("AI analysis stream dispatched: gameId={}, elapsed={}ms",
                gameId, System.currentTimeMillis() - startTime);
        return emitter;
    }

    /** 接收时间线推送（frames 全量），幂等写入 */
    @PostMapping("/{gameId}/timeline")
    public ApiResult<Void> syncTimeline(@PathVariable Long gameId,
            @Valid @RequestBody TimelineSyncRequest request) {
        // 契约要求 body 与 path 的 gameId 一致：不一致属调用方参数错误，
        // 拒绝写入而非静默按 path 落库，避免幂等键与调用方预期不符
        if (!Objects.equals(gameId, request.getGameId())) {
            log.warn("timeline gameId 不一致, path={}, body={}", gameId, request.getGameId());
            throw new BizException(ErrorCode.GAME_ID_MISMATCH,
                    "path 与 body 的 gameId 不一致: path=" + gameId + ", body=" + request.getGameId());
        }
        // 幂等保存：重复推送同一 gameId 不会覆盖首次写入的 frames
        matchTimelineService.saveTimeline(gameId, request.getFrames());
        // 同步接口契约：成功即返回 code=0（无业务数据，data 缺省）
        return ApiResult.success();
    }

    /** 查询对局时间线，不存在返回业务码 2002（HTTP 仍为 200） */
    @GetMapping("/{gameId}/timeline")
    public ApiResult<Object> getTimeline(@PathVariable Long gameId) {
        // 未命中时由 service 返回 null（TeamStatsService 依赖该 null 语义跳过缺失时间线），
        // HTTP 404 语义在此转为业务码 2002 交给全局处理器
        Object frames = matchTimelineService.getTimeline(gameId);
        if (frames == null) {
            throw new BizException(ErrorCode.TIMELINE_NOT_FOUND, "对局时间线不存在: gameId=" + gameId);
        }
        return ApiResult.success(frames);
    }
}
