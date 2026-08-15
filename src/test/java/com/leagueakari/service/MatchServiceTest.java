package com.leagueakari.service;

import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.ParticipantSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MatchService 幂等保存单元测试
 * <p>验证两条核心契约：
 * 1. game_id 不存在时插入 match 与参赛者；
 * 2. game_id 已存在时跳过，不产生任何写入。
 */
@ExtendWith(MockitoExtension.class)
class MatchServiceTest {

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchParticipantMapper matchParticipantMapper;

    /** 真实 Jackson 实例（spy），验证 teamsJson/statsJson 序列化路径 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MatchService matchService;

    /**
     * 构造一个合法的对局同步请求：单名参赛者，带原始 stats 对象
     */
    private MatchSyncRequest buildRequest(long gameId) {
        MatchSyncRequest req = new MatchSyncRequest();
        // 幂等键：LCU 对局 ID，服务端据此查重
        req.setGameId(gameId);
        // 主表直显字段（模式/时长/队列等），与 V1__init.sql 列一一对应
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

        // 参赛者：kills/deaths/assists 等直显字段齐全
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
        // 原始 stats 全量对象（与 LCU/SGP 字段名一致），整体存入 stats_json
        p.setStats(Map.of("totalDamageDealtToChampions", 25430));
        req.setParticipants(List.of(p));
        return req;
    }

    /**
     * 用例：game_id 不存在时，应插入 match 主表与参赛者各一次
     */
    @Test
    void saveMatch_insertsWhenGameIdAbsent() {
        // 模拟查重结果：0 表示该对局不存在
        when(matchMapper.selectCount(any())).thenReturn(0L);

        // 执行被测方法：幂等保存
        matchService.saveMatch(buildRequest(1000000001L));

        // 断言 match 与参赛者均只插入一次
        verify(matchMapper, times(1)).insert(any(Match.class));
        verify(matchParticipantMapper, times(1)).insert(any(MatchParticipant.class));
    }

    /**
     * 用例：game_id 已存在时，幂等跳过，任何 mapper 都不应产生写入
     */
    @Test
    void saveMatch_skipsWhenGameIdExists() {
        // 模拟查重结果：1 表示该对局已入库
        when(matchMapper.selectCount(any())).thenReturn(1L);

        // 执行被测方法：幂等保存
        matchService.saveMatch(buildRequest(1000000002L));

        // 断言幂等跳过：match 与参赛者均未插入
        verify(matchMapper, never()).insert(any(Match.class));
        verify(matchParticipantMapper, never()).insert(any(MatchParticipant.class));
    }
}
