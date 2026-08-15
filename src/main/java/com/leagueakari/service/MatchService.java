package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MatchDetailResponse;
import com.leagueakari.dto.MatchSummaryResponse;
import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.PageResponse;
import com.leagueakari.dto.ParticipantSyncRequest;
import com.leagueakari.dto.TeamSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对局数据服务：幂等保存与查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    private final MatchMapper matchMapper;
    private final MatchParticipantMapper matchParticipantMapper;
    private final ObjectMapper objectMapper;

    /**
     * 幂等保存对局（先查后插）：
     * 1. 按 game_id 查重，已存在则直接跳过，避免重复入库；
     * 2. 不存在则写入 match 主表，并逐条写入参赛者明细。
     */
    @Transactional
    public void saveMatch(MatchSyncRequest request) {
        // 幂等检查：先查后插，以 game_id 为唯一键判断该对局是否已同步
        Long gameId = request.getGameId();
        Long exists = matchMapper.selectCount(
                new QueryWrapper<Match>().eq("game_id", gameId));
        // 对局已存在：直接返回，不产生任何写入（调用方无需感知）
        if (exists != null && exists > 0) {
            log.info("Match already exists, skip sync: gameId={}", gameId);
            return;
        }

        // 组装 match 主表记录：字段与实体一一对应，teams 整体序列化为 teams_json
        Match match = new Match();
        match.setGameId(gameId);
        match.setGameCreation(request.getGameCreation());
        match.setGameDuration(request.getGameDuration());
        match.setGameMode(request.getGameMode());
        match.setGameType(request.getGameType());
        match.setQueueId(request.getQueueId());
        match.setMapId(request.getMapId());
        match.setGameVersion(request.getGameVersion());
        match.setRegion(request.getRegion());
        match.setRsoPlatformId(request.getRsoPlatformId());
        match.setDataSource(request.getDataSource());
        match.setWinnerTeamId(request.getWinnerTeamId());
        match.setSelfPuuid(request.getSelfPuuid());
        match.setTeamsJson(writeJson(request.getTeams()));
        match.setCreatedAt(LocalDateTime.now());
        // 主表插入后 id 自动回填（AUTO 主键），供参赛者关联 match_id
        matchMapper.insert(match);

        // 逐条组装参赛者：直显字段缺失时写 0，stats 全量透传存入 stats_json
        for (ParticipantSyncRequest p : request.getParticipants()) {
            // 基础字段：玩家身份、英雄与队伍归属
            MatchParticipant participant = new MatchParticipant();
            participant.setMatchId(match.getId());
            participant.setPuuid(p.getPuuid());
            participant.setSummonerName(p.getSummonerName());
            participant.setChampionId(p.getChampionId());
            participant.setTeamId(p.getTeamId());
            participant.setPosition(p.getPosition());
            // 直显统计字段：kills/deaths/assists 等缺失时写 0，保证下游渲染不出现 null
            participant.setKills(p.getKills() == null ? 0 : p.getKills());
            participant.setDeaths(p.getDeaths() == null ? 0 : p.getDeaths());
            participant.setAssists(p.getAssists() == null ? 0 : p.getAssists());
            participant.setWin(p.getWin());
            participant.setGoldEarned(p.getGoldEarned() == null ? 0 : p.getGoldEarned());
            participant.setCs(p.getCs() == null ? 0 : p.getCs());
            // 出装/召唤师技能/stats 统一走 JSON 序列化，保证原始快照完整
            participant.setItems(writeJson(p.getItems()));
            participant.setSummonerSpells(writeJson(p.getSummonerSpells()));
            participant.setStatsJson(writeJson(p.getStats()));
            matchParticipantMapper.insert(participant);
        }

        log.info("Match saved: gameId={}, participants={}", gameId, request.getParticipants().size());
    }

    /**
     * 对象转 JSON 字符串，用于 teams_json / items / summonerSpells / stats_json 等快照列；
     * 序列化失败仅记录日志并返回 null，不阻断保存流程
     */
    private String writeJson(Object value) {
        if (value == null) {
            // null 或空集合直接返回 null，对应快照列落库为 NULL
            return null;
        }
        try {
            // 通过 Jackson 序列化为 JSON 字符串
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // 序列化失败不抛出：快照列允许为 NULL，避免单点异常中断整局保存
            log.error("Failed to serialize value to JSON", e);
            return null;
        }
    }

    /**
     * 分页查询对局列表，支持队列与时间范围筛选
     * <p>筛选条件均为可选：queueId 精确匹配，startTime/endTime 为 game_creation
     * 时间戳区间；结果按创建时间倒序，返回精简的列表项 DTO。</p>
     */
    public PageResponse<MatchSummaryResponse> pageMatches(
            long page, long pageSize, Integer queueId, Long startTime, Long endTime) {

        // 组装查询条件：可选的队列过滤与时间范围过滤
        QueryWrapper<Match> wrapper = new QueryWrapper<>();
        if (queueId != null) {
            wrapper.eq("queue_id", queueId);
        }
        if (startTime != null) {
            wrapper.ge("game_creation", startTime);
        }
        if (endTime != null) {
            wrapper.le("game_creation", endTime);
        }
        // 新对局排前面，便于客户端展示最近战绩
        wrapper.orderByDesc("game_creation");

        // 分页插件改写 SQL：自动生成 COUNT 与 LIMIT，total 为满足条件的总条数
        Page<Match> result = matchMapper.selectPage(new Page<>(page, pageSize), wrapper);
        // 实体转列表项 DTO：只透传列表需要的精简字段，不携带 JSON 快照
        List<MatchSummaryResponse> items = result.getRecords().stream().map(m -> {
            MatchSummaryResponse resp = new MatchSummaryResponse();
            resp.setGameId(m.getGameId());
            resp.setGameCreation(m.getGameCreation());
            resp.setGameDuration(m.getGameDuration());
            resp.setGameMode(m.getGameMode());
            resp.setQueueId(m.getQueueId());
            resp.setRegion(m.getRegion());
            resp.setWinnerTeamId(m.getWinnerTeamId());
            resp.setSelfPuuid(m.getSelfPuuid());
            return resp;
        }).toList();

        log.info("Query matches: page={}, pageSize={}, total={}", page, pageSize, result.getTotal());
        return new PageResponse<>(items, page, pageSize, result.getTotal());
    }

    /**
     * 查询对局详情（含参赛者列表），不存在抛 MatchNotFoundException
     * <p>主表按 game_id 精确查询，命中后按主键关联参赛者明细；
     * 详情包含 teams_json 与各参赛者的 stats_json 全量快照。</p>
     */
    public MatchDetailResponse getMatchDetail(Long gameId) {
        // 按幂等键 game_id 查询主表记录
        Match match = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", gameId));
        if (match == null) {
            // 未命中：抛出领域异常，由全局异常处理器转为 404
            log.warn("Match not found, gameId={}", gameId);
            throw new MatchNotFoundException(gameId);
        }

        // 按主表主键查询参赛者明细（match_participant.match_id 外键）
        List<MatchParticipant> participants = matchParticipantMapper.selectList(
                new QueryWrapper<MatchParticipant>().eq("match_id", match.getId()));

        // 实体字段逐一透传到详情 DTO，保证响应契约字段齐全
        MatchDetailResponse resp = new MatchDetailResponse();
        resp.setGameId(match.getGameId());
        resp.setGameCreation(match.getGameCreation());
        resp.setGameDuration(match.getGameDuration());
        resp.setGameMode(match.getGameMode());
        resp.setGameType(match.getGameType());
        resp.setQueueId(match.getQueueId());
        resp.setMapId(match.getMapId());
        resp.setGameVersion(match.getGameVersion());
        resp.setRegion(match.getRegion());
        resp.setRsoPlatformId(match.getRsoPlatformId());
        resp.setDataSource(match.getDataSource());
        resp.setWinnerTeamId(match.getWinnerTeamId());
        resp.setSelfPuuid(match.getSelfPuuid());
        resp.setTeamsJson(match.getTeamsJson());
        resp.setParticipants(participants);
        log.info("Query match detail: gameId={}, participants={}", gameId, participants.size());
        return resp;
    }
}
