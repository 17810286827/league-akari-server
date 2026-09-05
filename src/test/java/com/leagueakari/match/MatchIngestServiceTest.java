package com.leagueakari.match;

import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.ParticipantSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.leagueakari.scoring.MatchMvpService;

/**
 * MatchIngestService 幂等入库单元测试（对局同步子系统的写入半边）
 * <p>验证核心契约：
 * 1. game_id 不存在时插入 match 与参赛者，并触发 MVP 评选与基线累积；
 * 2. game_id 已存在或并发撞键时跳过，不产生任何写入、不触发任何下游。</p>
 */
@ExtendWith(MockitoExtension.class)
class MatchIngestServiceTest {

    @Mock
    private MatchMapper matchMapper;

    @Mock
    private MatchParticipantMapper matchParticipantMapper;

    /** MVP/SVP 评选编排服务：saveMatch 参与者落库后触发 */
    @Mock
    private MatchMvpService matchMvpService;

    /** 真实 Jackson 实例（spy），验证 teamsJson/statsJson 序列化路径 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    /** 事件发布器 mock：事件化后 saveMatch 会在事务内发布"对局已同步"事件 */
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MatchIngestService matchIngestService;

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
        matchIngestService.saveMatch(buildRequest(1000000001L));

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
        matchIngestService.saveMatch(buildRequest(1000000002L));

        // 断言幂等跳过：match 与参赛者均未插入，也不触发 MVP 评选
        verify(matchMapper, never()).insert(any(Match.class));
        verify(matchParticipantMapper, never()).insert(any(MatchParticipant.class));
        verify(matchMvpService, never()).evaluateAndSave(any(), any());
    }

    /**
     * 用例：新对局保存后应触发 MVP/SVP 评选，
     * 传入的参与者列表与落库实体一致（含回填后的 id）
     */
    @Test
    void saveMatch_triggersMvpEvaluationAfterInsert() {
        // 模拟查重结果：0 表示该对局不存在
        when(matchMapper.selectCount(any())).thenReturn(0L);
        // 模拟插入回填主键：match.id=1、participant.id=101
        doAnswer(inv -> {
            Match m = inv.getArgument(0);
            m.setId(1L);
            return 1;
        }).when(matchMapper).insert(any(Match.class));
        doAnswer(inv -> {
            MatchParticipant p = inv.getArgument(0);
            p.setId(101L);
            return 1;
        }).when(matchParticipantMapper).insert(any(MatchParticipant.class));

        // 执行被测方法：幂等保存
        matchIngestService.saveMatch(buildRequest(1000000005L));

        // 断言：参与者落库后触发评选，传入对局实体与参与者列表
        ArgumentCaptor<List<MatchParticipant>> captor = ArgumentCaptor.captor();
        verify(matchMvpService).evaluateAndSave(any(Match.class), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        // 参与者 id 已回填（评选落库依赖该 id 关联 match_mvp.participant_id）
        assertThat(captor.getValue().get(0).getId()).isEqualTo(101L);
    }

    /**
     * 用例：首插成功时照常触发 MVP 评选（原 saveMatch 返回 true 的场景，
     * 拆分后首插语义由推送状态机承载，这里保留"下游触发"断言）
     */
    @Test
    void saveMatch_triggersDownstreamWhenFirstInserted() {
        // 模拟查重结果：0 表示该对局不存在，走首次插入路径
        when(matchMapper.selectCount(any())).thenReturn(0L);

        // 执行被测方法：幂等保存
        matchIngestService.saveMatch(buildRequest(1000000020L));

        // 首插语义：触发 MVP 评选（下游编排由推送状态机门控，本服务不承诺首插返回值）
        verify(matchMvpService).evaluateAndSave(any(), any());
    }

    /**
     * 用例：game_id 已存在（幂等跳过）时不产生任何写入与下游触发
     * （原 saveMatch 返回 false 的场景，拆分后契约改为"零副作用"断言）
     */
    @Test
    void saveMatch_noWritesWhenAlreadyExists() {
        // 模拟查重结果：1 表示该对局已入库
        when(matchMapper.selectCount(any())).thenReturn(1L);

        // 执行被测方法：幂等保存
        matchIngestService.saveMatch(buildRequest(1000000021L));

        // 幂等语义：不产生写入
        verify(matchMapper, never()).insert(any(Match.class));
    }

    /**
     * 用例：并发穿透撞唯一键（DuplicateKeyException 被吞掉）时零副作用，
     * 与幂等语义一致——不写参与者、不触发评选（原 saveMatch 返回 false 的场景）
     */
    @Test
    void saveMatch_noWritesWhenConcurrentDuplicate() {
        // 模拟查重结果：0 通过幂等检查，但 insert 撞 game_id 唯一键（并发另一请求已插入）
        when(matchMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("duplicate game_id")).when(matchMapper).insert(any(Match.class));

        // 执行被测方法：幂等保存，异常被吞掉按幂等成功返回
        matchIngestService.saveMatch(buildRequest(1000000022L));

        // 并发兜底语义：不写参与者/不触发评选
        verify(matchParticipantMapper, never()).insert(any(MatchParticipant.class));
        verify(matchMvpService, never()).evaluateAndSave(any(), any());
    }
}
