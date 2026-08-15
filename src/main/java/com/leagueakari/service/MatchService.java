package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MatchSyncRequest;
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
     * 幂等保存对局：game_id 已存在则跳过，否则连同参赛者一起入库
     */
    @Transactional
    public void saveMatch(MatchSyncRequest request) {
        Long gameId = request.getGameId();
        Long exists = matchMapper.selectCount(
                new QueryWrapper<Match>().eq("game_id", gameId));
        if (exists != null && exists > 0) {
            log.info("Match already exists, skip sync: gameId={}", gameId);
            return;
        }

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
        matchMapper.insert(match);

        for (ParticipantSyncRequest p : request.getParticipants()) {
            MatchParticipant participant = new MatchParticipant();
            participant.setMatchId(match.getId());
            participant.setPuuid(p.getPuuid());
            participant.setSummonerName(p.getSummonerName());
            participant.setChampionId(p.getChampionId());
            participant.setTeamId(p.getTeamId());
            participant.setPosition(p.getPosition());
            participant.setKills(p.getKills() == null ? 0 : p.getKills());
            participant.setDeaths(p.getDeaths() == null ? 0 : p.getDeaths());
            participant.setAssists(p.getAssists() == null ? 0 : p.getAssists());
            participant.setWin(p.getWin());
            participant.setGoldEarned(p.getGoldEarned() == null ? 0 : p.getGoldEarned());
            participant.setCs(p.getCs() == null ? 0 : p.getCs());
            participant.setItems(writeJson(p.getItems()));
            participant.setSummonerSpells(writeJson(p.getSummonerSpells()));
            participant.setStatsJson(writeJson(p.getStats()));
            matchParticipantMapper.insert(participant);
        }

        log.info("Match saved: gameId={}, participants={}", gameId, request.getParticipants().size());
    }

    /** 对象转 JSON 字符串；null 或空集合返回 null */
    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("Failed to serialize value to JSON", e);
            return null;
        }
    }
}
