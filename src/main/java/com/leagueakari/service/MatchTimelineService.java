package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.entity.MatchTimeline;
import com.leagueakari.mapper.MatchTimelineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 对局时间线服务：frames 全量快照的幂等写入与查询
 * <p>写入契约：同一 gameId 只保留首次推送的 frames，重复推送幂等跳过；
 * 查询契约：命中返回解析后的 frames 原始结构，未命中返回 null（由 controller 转 404）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchTimelineService {

    private final MatchTimelineMapper matchTimelineMapper;
    private final ObjectMapper objectMapper;

    /**
     * 幂等保存时间线（先查后插）：
     * 1. 按 game_id 查重，已存在则直接跳过，避免重复入库；
     * 2. 不存在则把 frames 全量序列化为 frames_json 落库。
     */
    public void saveTimeline(Long gameId, Object frames) {
        // 幂等检查：以 game_id 为唯一键判断该时间线是否已同步
        Long exists = matchTimelineMapper.selectCount(
                new QueryWrapper<MatchTimeline>().eq("game_id", gameId));
        // 已存在：直接返回，不产生任何写入（调用方无需感知）
        if (exists != null && exists > 0) {
            log.info("timeline 幂等命中 gameId={}", gameId);
            return;
        }

        // 组装时间线记录：frames 全量序列化为 frames_json，原样存储
        MatchTimeline timeline = new MatchTimeline();
        timeline.setGameId(gameId);
        timeline.setFramesJson(writeJson(frames));
        timeline.setCreatedAt(LocalDateTime.now());
        try {
            matchTimelineMapper.insert(timeline);
        } catch (DuplicateKeyException e) {
            // 并发兜底：两个请求同时通过幂等检查，后插入者撞 game_id 唯一键。
            // 异常已在方法内吞掉，视为幂等成功直接返回
            log.info("timeline 并发插入被唯一键拦截，视为幂等成功 gameId={}", gameId);
            return;
        }

        log.info("timeline 写入成功 gameId={}", gameId);
    }

    /**
     * 查询对局时间线：命中则把 frames_json 反序列化为原始 Object 返回；
     * 未命中返回 null（controller 层转 404），快照解析失败同样按缺失处理
     */
    public Object getTimeline(Long gameId) {
        // 按幂等键 game_id 查询时间线记录
        MatchTimeline timeline = matchTimelineMapper.selectOne(
                new QueryWrapper<MatchTimeline>().eq("game_id", gameId));
        if (timeline == null) {
            // 未命中：记录日志并返回 null，由 controller 抛出领域异常转 404
            log.warn("timeline 查询未命中 gameId={}", gameId);
            return null;
        }

        // frames_json 反序列化为原始结构，保证响应与推送内容原样一致
        try {
            return objectMapper.readValue(timeline.getFramesJson(), Object.class);
        } catch (Exception e) {
            // 快照损坏不阻断查询接口：仅记录日志并按缺失（null）处理
            log.error("timeline frames_json 解析失败 gameId={}", gameId, e);
            return null;
        }
    }

    /**
     * 对象转 JSON 字符串（与 MatchService.writeJson 同款 ObjectMapper 逻辑）；
     * 序列化失败仅记录日志并返回 null，不阻断保存流程
     */
    private String writeJson(Object value) {
        if (value == null) {
            // null 直接返回 null，对应快照列落库为 NULL
            return null;
        }
        try {
            // 通过 Jackson 序列化为 JSON 字符串
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // 序列化失败不抛出：仅记录日志，避免单点异常中断写入
            log.error("Failed to serialize timeline frames to JSON", e);
            return null;
        }
    }
}
