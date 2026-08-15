package com.leagueakari.service;

import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.ParticipantSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MatchService 幂等保存单元测试：验证 game_id 不存在时插入、已存在时跳过
 */
@ExtendWith(MockitoExtension.class)
class MatchServiceTest {

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchParticipantMapper matchParticipantMapper;

    @InjectMocks
    private MatchService matchService;

    private MatchSyncRequest buildRequest(long gameId) {
        MatchSyncRequest req = new MatchSyncRequest();
        req.setGameId(gameId);
        req.setGameCreation(1720000000000L);
        req.setGameDuration(1830);
        req.setGameMode("CLASSIC");
        req.setGameType("MATCHED_GAME");
        req.setQueueId(420);
        req.setMapId(11);
        req.setGameVersion("25.4.1");
        req.setRegion("na1");
        req.setRsoPlatformId("");
        req.setDataSource("lcu");
        req.setWinnerTeamId(100);
        req.setSelfPuuid("self-puuid-1");

        ParticipantSyncRequest p = new ParticipantSyncRequest();
        p.setPuuid("player-1");
        p.setSummonerName("PlayerOne");
        p.setChampionId(103);
        p.setTeamId(100);
        p.setKills(5);
        p.setDeaths(3);
        p.setAssists(8);
        p.setWin(true);
        p.setGoldEarned(12800);
        p.setCs(210);
        p.setStats(Map.of("totalDamageDealtToChampions", 25430));
        req.setParticipants(List.of(p));
        return req;
    }

    @Test
    void saveMatch_insertsWhenGameIdAbsent() {
        when(matchMapper.selectCount(any())).thenReturn(0L);

        matchService.saveMatch(buildRequest(1000000001L));

        verify(matchMapper, times(1)).insert(any(Match.class));
        verify(matchParticipantMapper, times(1)).insert(any());
    }

    @Test
    void saveMatch_skipsWhenGameIdExists() {
        when(matchMapper.selectCount(any())).thenReturn(1L);

        matchService.saveMatch(buildRequest(1000000002L));

        verify(matchMapper, never()).insert(any());
        verify(matchParticipantMapper, never()).insert(any());
    }
}
