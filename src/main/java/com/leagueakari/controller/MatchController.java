package com.leagueakari.controller;

import com.leagueakari.dto.MatchDetailResponse;
import com.leagueakari.dto.MatchSummaryResponse;
import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.PageResponse;
import com.leagueakari.service.MatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对局 API：同步写入（幂等）与查询
 * <p>路由层职责：参数校验（@Valid / @RequestParam）与返回值封装；
 * 业务逻辑全部下沉 MatchService，异常由全局异常处理器统一转换。</p>
 */
@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    /** 接收对局同步推送，幂等写入 */
    @PostMapping
    public Map<String, Object> syncMatch(@Valid @RequestBody MatchSyncRequest request) {
        // 幂等保存：重复推送同一 gameId 不会产生重复数据
        matchService.saveMatch(request);
        return Map.of("code", 0);
    }

    /** 分页查询对局列表，支持 queueId / startTime / endTime 筛选 */
    @GetMapping
    public PageResponse<MatchSummaryResponse> listMatches(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) Integer queueId,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime) {
        return matchService.pageMatches(page, pageSize, queueId, startTime, endTime);
    }

    /** 查询对局详情，不存在返回 404 */
    @GetMapping("/{gameId}")
    public Map<String, MatchDetailResponse> getMatchDetail(@PathVariable Long gameId) {
        // data 包装与分页/同步响应的扁平结构区分，详情契约见规格第 4.2 节
        return Map.of("data", matchService.getMatchDetail(gameId));
    }
}
