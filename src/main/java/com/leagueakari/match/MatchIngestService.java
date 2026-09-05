package com.leagueakari.match;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.ParticipantSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.leagueakari.scoring.MatchMvpService;

/**
 * 对局摄取服务：幂等入库管道（对局同步子系统的写入半边）
 * <p>职责：先查后插幂等保存对局主表与参赛者明细 → 参与者落库后同事务触发
 * MVP/SVP 评选与评分基线累积。"仅首次入库触发"的下游语义（如局后播报）
 * 由推送状态机唯一承载，本服务不对外承诺首插语义。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchIngestService {

    private final MatchMapper matchMapper;
    private final MatchParticipantMapper matchParticipantMapper;
    private final ObjectMapper objectMapper;
    /** MVP/SVP 评选编排：参与者落库后触发评选落库与基线累积 */
    private final MatchMvpService matchMvpService;
    /** 事件发布器：落库事务内发布"对局已同步"事件，提交后由局后播报协调器消费 */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 幂等保存对局（先查后插）：
     * 1. 按 game_id 查重，已存在则直接跳过，避免重复入库；
     * 2. 不存在则写入 match 主表，并逐条写入参赛者明细；
     * 3. 参与者全部落库后，同事务触发 MVP/SVP 评选与评分基线累积。
     * <p>重复推送同一 game_id 不产生任何写入，调用方无需感知幂等细节。</p>
     */
    @Transactional
    public void saveMatch(MatchSyncRequest request) {
        // 对局已同步事件：在事务内发布、提交后（AFTER_COMMIT）投递——每次同步都发布
        //（含幂等跳过），是否播报由推送状态机判定；事务回滚时事件自动丢弃
        eventPublisher.publishEvent(new MatchSavedEvent(request.getGameId()));
        // 幂等检查：先查后插，以 game_id 为唯一键判断该对局是否已同步
        Long gameId = request.getGameId();
        Long exists = matchMapper.selectCount(
                new QueryWrapper<Match>().eq("game_id", gameId));
        // 对局已存在：直接返回，不产生任何写入
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
        List<MatchParticipant> savedParticipants = new ArrayList<>(request.getParticipants().size());
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
            // 收集落库实体（id 已回填），供 MVP/SVP 评选使用
            savedParticipants.add(participant);
        }

        // 参与者全部落库后触发 MVP/SVP 评选：同事务内写 match_mvp
        matchMvpService.evaluateAndSave(match, savedParticipants);
        // 同一首存路径累积评分基线（scoring_baseline 随对局同步积累，重复推送因幂等查重不会二次累加）
        matchMvpService.collectBaselines(match, savedParticipants);

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
}
