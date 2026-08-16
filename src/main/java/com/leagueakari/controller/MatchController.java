package com.leagueakari.controller;

import com.leagueakari.dto.MatchDetailResponse;
import com.leagueakari.dto.MatchSummaryResponse;
import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.PageResponse;
import com.leagueakari.dto.TimelineSyncRequest;
import com.leagueakari.service.MatchNotFoundException;
import com.leagueakari.service.MatchService;
import com.leagueakari.service.MatchTimelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/**
 * 对局 API：同步写入（幂等）与查询
 * <p>路由层职责：参数校验（@Valid / @RequestParam）与返回值封装；
 * 业务逻辑全部下沉 MatchService，异常由全局异常处理器统一转换。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;
    private final MatchTimelineService matchTimelineService;

    /** 接收对局同步推送，幂等写入 */
    @PostMapping
    public Map<String, Object> syncMatch(@Valid @RequestBody MatchSyncRequest request) {
        // 幂等保存：重复推送同一 gameId 不会产生重复数据
        matchService.saveMatch(request);
        // 同步接口契约：成功即返回 code=0，无需回传实体数据
        return Map.of("code", 0);
    }

    /**
     * 分页查询对局列表：支持 queueId / puuid / summonerName / startTime / endTime 筛选；
     * puuid 与 summonerName 二选一（只能查询指定玩家，都缺失时返回空页）
     */
    @GetMapping
    public PageResponse<MatchSummaryResponse> listMatches(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) Integer queueId,
            @RequestParam(required = false) String puuid,
            @RequestParam(required = false) String summonerName,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime) {
        // 筛选参数均为可选，page/pageSize 缺省时取默认值 1/20
        return matchService.pageMatches(page, pageSize, queueId, puuid, summonerName, startTime, endTime);
    }

    /** 查询对局详情，不存在返回 404 */
    @GetMapping("/{gameId}")
    public Map<String, MatchDetailResponse> getMatchDetail(@PathVariable Long gameId) {
        // data 包装与分页/同步响应的扁平结构区分，详情契约见规格第 4.2 节
        // 对局不存在时由 service 抛出 MatchNotFoundException，全局处理器转为 404
        return Map.of("data", matchService.getMatchDetail(gameId));
    }

    /** 接收时间线推送（frames 全量），幂等写入 */
    @PostMapping("/{gameId}/timeline")
    public Map<String, Object> syncTimeline(@PathVariable Long gameId,
            @Valid @RequestBody TimelineSyncRequest request) {
        // 契约要求 body 与 path 的 gameId 一致：不一致属调用方参数错误，
        // 拒绝写入而非静默按 path 落库，避免幂等键与调用方预期不符
        if (!Objects.equals(gameId, request.getGameId())) {
            log.warn("timeline gameId 不一致, path={}, body={}", gameId, request.getGameId());
            throw new IllegalArgumentException("path 与 body 的 gameId 不一致");
        }
        // 幂等保存：重复推送同一 gameId 不会覆盖首次写入的 frames
        matchTimelineService.saveTimeline(gameId, request.getFrames());
        // 同步接口契约：成功即返回 code=0，无需回传实体数据
        return Map.of("code", 0);
    }

    /** 查询对局时间线，不存在返回 404 */
    @GetMapping("/{gameId}/timeline")
    public Map<String, Object> getTimeline(@PathVariable Long gameId) {
        // 未命中时由 service 返回 null，此处抛出 MatchNotFoundException 复用全局 404 处理
        Object frames = matchTimelineService.getTimeline(gameId);
        if (frames == null) {
            throw new MatchNotFoundException(gameId);
        }
        return Map.of("data", frames);
    }
}
