package com.leagueakari.controller;

import com.leagueakari.common.web.ApiResult;
import com.leagueakari.dto.intel.GameStartSignalRequest;
import com.leagueakari.dto.intel.ScoutHitResponse;
import com.leagueakari.dto.intel.ScoutSyncRequest;
import com.leagueakari.intel.IntelBroadcastService;
import com.leagueakari.intel.ScoutIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敌方情报推送 API：侦察数据接收、缓存命中查询、游戏开始信号。
 * <p>路由层职责：参数校验（@Valid）与返回值封装（统一 ApiResult），业务逻辑下沉
 * service 层（ingest 走 ScoutIngestService，发卡编排走 IntelBroadcastService）；
 * 异常由全局异常处理器统一转换（HTTP 200 + 业务码）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/intel")
@RequiredArgsConstructor
public class IntelController {

    /** 敌方侦察数据 ingest：段位 upsert + 摘要幂等写入 + 缓存命中查询 */
    private final ScoutIngestService scoutIngestService;
    /** 敌方情报推送编排：游戏开始信号驱动的发卡状态机 */
    private final IntelBroadcastService intelBroadcastService;

    /**
     * 接收敌方侦察数据（桌面端选人阶段喂入）：幂等写入。
     * 契约：成功即返回 code=0（无业务数据，data 缺省）。
     */
    @PostMapping("/scout")
    public ApiResult<Void> ingestScout(@Valid @RequestBody ScoutSyncRequest request) {
        scoutIngestService.ingest(request);
        return ApiResult.success();
    }

    /**
     * 侦察缓存命中查询：给定一批 puuid，返回哪些人的历史已在缓存内
     * （桌面端据此减少重复喂量，发卡编排据此判断敌方历史可得性）。
     */
    @PostMapping("/scout/hit")
    public ApiResult<ScoutHitResponse> scoutHit(@Valid @RequestBody ScoutHitResponse.Request request) {
        return ApiResult.success(new ScoutHitResponse(scoutIngestService.queryHit(request.getPuuids())));
    }

    /**
     * 接收游戏开始信号（桌面端 Gameflow 进入 InProgress 时）：触发敌方情报推送编排。
     * 契约：成功即返回 code=0（推送为异步编排，结果落库 intel_game_start.push_status）。
     */
    @PostMapping("/game-start")
    public ApiResult<Void> gameStart(@Valid @RequestBody GameStartSignalRequest request) {
        intelBroadcastService.onGameStart(request);
        return ApiResult.success();
    }
}
