package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
        try {
            matchMapper.insert(match);
        } catch (DuplicateKeyException e) {
            // 并发兜底：两个请求同时通过"先查后插"的幂等检查，后插入者撞 game_id 唯一键。
            // 异常已在方法内吞掉、不向事务边界传播，事务不会标记回滚，视为幂等成功直接返回
            log.info("Match concurrently inserted, skip sync: gameId={}", gameId);
            return;
        }

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

        // 收集本页对局主键，用于一次性批量查询参赛者，避免逐局查库的 N+1 问题
        List<Long> matchIds = result.getRecords().stream().map(Match::getId).toList();
        // 按 match_id 分组：本页所有参赛者一次查出，供各对局聚合 self/teamTotals/teammates
        Map<Long, List<MatchParticipant>> participantsByMatch = batchLoadParticipants(matchIds);

        // 实体转列表项 DTO：透传精简字段，并补充本玩家数据、队伍聚合与队友摘要
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
            // 填充本玩家/队伍聚合/队友摘要：self 行缺失时输出全零占位，不抛错
            fillMatchExtras(resp, m.getSelfPuuid(),
                    participantsByMatch.getOrDefault(m.getId(), List.of()));
            return resp;
        }).toList();

        log.info("Query matches: page={}, pageSize={}, total={}", page, pageSize, result.getTotal());
        return new PageResponse<>(items, page, pageSize, result.getTotal());
    }

    /**
     * 批量查询本页对局的参赛者并按对局分组：
     * 一次 IN(match_id) 查询取代逐局查询，控制数据库往返次数
     */
    private Map<Long, List<MatchParticipant>> batchLoadParticipants(List<Long> matchIds) {
        if (matchIds.isEmpty()) {
            // 本页无对局时直接返回空映射，避免无意义的 IN 查询
            return Map.of();
        }
        List<MatchParticipant> participants = matchParticipantMapper.selectList(
                new QueryWrapper<MatchParticipant>().in("match_id", matchIds));
        return participants.stream().collect(Collectors.groupingBy(MatchParticipant::getMatchId));
    }

    /**
     * 为列表项补充本玩家（self）、所在队伍聚合（teamTotals）与队友摘要（teammates）；
     * 若 self 行缺失（异常数据），返回全零占位且不抛错，保证列表接口始终可用
     */
    private void fillMatchExtras(MatchSummaryResponse resp, String selfPuuid,
                                 List<MatchParticipant> participants) {
        // 定位本玩家行：puuid 与主表 self_puuid 一致（null 安全比较）
        MatchParticipant self = participants.stream()
                .filter(p -> Objects.equals(selfPuuid, p.getPuuid()))
                .findFirst().orElse(null);

        if (self == null) {
            // 异常数据兜底：self 行缺失时输出全零占位、teammates 空列表
            log.warn("Self participant missing, use placeholder: gameId={}", resp.getGameId());
            resp.setSelf(placeholderSelf());
            resp.setTeamTotals(placeholderTeamTotals());
            resp.setTeammates(List.of());
            return;
        }

        // 同队成员：与 self 相同 teamId 的参赛者（正常为含 self 共 5 人）
        List<MatchParticipant> teamMembers = participants.stream()
                .filter(p -> Objects.equals(p.getTeamId(), self.getTeamId()))
                .toList();

        resp.setSelf(buildSelf(self));
        resp.setTeamTotals(buildTeamTotals(teamMembers));
        resp.setTeammates(buildTeammates(teamMembers, self));
    }

    /**
     * 组装本玩家个人数据：身份与击杀/死亡/助攻来自直显列，
     * 伤害/经济/补刀/标记字段来自 stats_json 解析（缺失写 0/false）
     */
    private MatchSummaryResponse.SelfSummary buildSelf(MatchParticipant self) {
        MatchSummaryResponse.SelfSummary s = new MatchSummaryResponse.SelfSummary();
        // 身份字段：直显列原样透传
        s.setChampionId(self.getChampionId());
        s.setSummonerName(self.getSummonerName());
        // 直显统计缺失时写 0，保证响应字段非 null
        s.setKills(self.getKills() == null ? 0 : self.getKills());
        s.setDeaths(self.getDeaths() == null ? 0 : self.getDeaths());
        s.setAssists(self.getAssists() == null ? 0 : self.getAssists());
        s.setWin(self.getWin());
        // stats 快照字段：解析 stats_json，缺失写 0/false
        JsonNode stats = parseStatsJson(self.getStatsJson());
        s.setTotalDamage(statInt(stats, "totalDamageDealtToChampions"));
        s.setTotalDamageTaken(statInt(stats, "totalDamageTaken"));
        s.setGoldEarned(statInt(stats, "goldEarned"));
        s.setCs(statInt(stats, "totalMinionsKilled"));
        s.setLargestMultiKill(statInt(stats, "largestMultiKill"));
        s.setTurretKills(statInt(stats, "turretKills"));
        s.setGameEndedInSurrender(statBool(stats, "gameEndedInSurrender"));
        return s;
    }

    /**
     * 聚合队伍统计：对同队参赛者的直显击杀/经济与 stats 伤害求和，
     * 单项缺失视为 0，保证聚合结果非 null
     */
    private MatchSummaryResponse.TeamTotals buildTeamTotals(List<MatchParticipant> teamMembers) {
        MatchSummaryResponse.TeamTotals t = new MatchSummaryResponse.TeamTotals();
        int kills = 0;
        int gold = 0;
        int damage = 0;
        int damageTaken = 0;
        for (MatchParticipant p : teamMembers) {
            // 直显列缺失视为 0
            kills += p.getKills() == null ? 0 : p.getKills();
            gold += p.getGoldEarned() == null ? 0 : p.getGoldEarned();
            // stats 伤害字段从各人 stats_json 解析，缺失视为 0
            JsonNode stats = parseStatsJson(p.getStatsJson());
            damage += statInt(stats, "totalDamageDealtToChampions");
            damageTaken += statInt(stats, "totalDamageTaken");
        }
        t.setKills(kills);
        t.setGold(gold);
        t.setDamage(damage);
        t.setDamageTaken(damageTaken);
        return t;
    }

    /**
     * 组装队友摘要：同队其余参赛者（排除 self），
     * 正常为 4 人，供最近队友聚合与卡片队友展示
     */
    private List<MatchSummaryResponse.Teammate> buildTeammates(
            List<MatchParticipant> teamMembers, MatchParticipant self) {
        return teamMembers.stream()
                .filter(p -> !Objects.equals(p.getPuuid(), self.getPuuid()))
                .map(p -> {
                    MatchSummaryResponse.Teammate t = new MatchSummaryResponse.Teammate();
                    // 队友摘要只需身份与胜负字段，不携带统计明细
                    t.setPuuid(p.getPuuid());
                    t.setSummonerName(p.getSummonerName());
                    t.setChampionId(p.getChampionId());
                    t.setWin(p.getWin());
                    return t;
                })
                .toList();
    }

    /**
     * 解析 stats_json 快照为 JsonNode，供统计字段读取；
     * 空串或解析失败返回 null，调用方按缺失字段写 0/false 处理
     */
    private JsonNode parseStatsJson(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(statsJson);
        } catch (Exception e) {
            // 快照损坏不阻断列表接口：仅记录日志并按缺失字段处理
            log.warn("Failed to parse statsJson, treat as empty: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 读取 stats 数值字段：字段缺失、为 null 或非数字时返回 0
     */
    private int statInt(JsonNode stats, String key) {
        if (stats == null || !stats.has(key) || stats.get(key).isNull()) {
            return 0;
        }
        return stats.get(key).asInt(0);
    }

    /**
     * 读取 stats 布尔字段：字段缺失、为 null 或非法时返回 false
     */
    private boolean statBool(JsonNode stats, String key) {
        if (stats == null || !stats.has(key) || stats.get(key).isNull()) {
            return false;
        }
        return stats.get(key).asBoolean(false);
    }

    /**
     * self 行缺失时的全零占位：保证列表响应字段结构稳定，前端无需判空
     */
    private MatchSummaryResponse.SelfSummary placeholderSelf() {
        MatchSummaryResponse.SelfSummary s = new MatchSummaryResponse.SelfSummary();
        // 全零占位：数值 0、布尔 false、召唤师名空串
        s.setChampionId(0);
        s.setSummonerName("");
        s.setKills(0);
        s.setDeaths(0);
        s.setAssists(0);
        s.setWin(false);
        s.setTotalDamage(0);
        s.setTotalDamageTaken(0);
        s.setGoldEarned(0);
        s.setCs(0);
        s.setLargestMultiKill(0);
        s.setTurretKills(0);
        s.setGameEndedInSurrender(false);
        return s;
    }

    /**
     * 队伍聚合缺失时的全零占位（self 行缺失时使用）
     */
    private MatchSummaryResponse.TeamTotals placeholderTeamTotals() {
        MatchSummaryResponse.TeamTotals t = new MatchSummaryResponse.TeamTotals();
        // 全零占位：四项聚合均写 0
        t.setKills(0);
        t.setGold(0);
        t.setDamage(0);
        t.setDamageTaken(0);
        return t;
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
