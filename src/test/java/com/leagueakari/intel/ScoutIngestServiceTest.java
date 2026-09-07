package com.leagueakari.intel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leagueakari.dto.intel.ScoutSyncRequest;
import com.leagueakari.entity.EnemyScoutMatch;
import com.leagueakari.entity.EnemyScoutRank;
import com.leagueakari.mapper.EnemyScoutMatchMapper;
import com.leagueakari.mapper.EnemyScoutRankMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 敌方侦察数据 ingest 服务单测（Mock Mapper，无 Spring/DB 依赖）：
 * 锁定契约——段位按 puuid upsert、摘要行幂等写入、缓存命中查询。
 */
@ExtendWith(MockitoExtension.class)
class ScoutIngestServiceTest {

    @Mock
    private EnemyScoutMatchMapper matchMapper;
    @Mock
    private EnemyScoutRankMapper rankMapper;

    private ScoutIngestService service;

    @BeforeEach
    void setUp() {
        service = new ScoutIngestService(matchMapper, rankMapper);
    }

    /** 构造一份含 2 名玩家段位 + 1 局摘要的入参 */
    private ScoutSyncRequest request() {
        ScoutSyncRequest req = new ScoutSyncRequest();

        ScoutSyncRequest.PlayerRank p1 = new ScoutSyncRequest.PlayerRank();
        p1.setPuuid("p1");
        p1.setSummonerName("敌方甲");
        p1.setQueueType("RANKED_SOLO_5x5");
        p1.setTier("DIAMOND");
        p1.setRank("III");
        ScoutSyncRequest.PlayerRank p2 = new ScoutSyncRequest.PlayerRank();
        p2.setPuuid("p2");
        p2.setSummonerName("敌方乙");
        p2.setQueueType("RANKED_SOLO_5x5");
        p2.setTier("GOLD");
        p2.setRank("I");
        req.setPlayers(List.of(p1, p2));

        ScoutSyncRequest.MatchSummary m = new ScoutSyncRequest.MatchSummary();
        m.setMatchId("123456");
        m.setQueueId(420);
        ScoutSyncRequest.Participant pa = new ScoutSyncRequest.Participant();
        pa.setPuuid("p1");
        pa.setSummonerName("敌方甲");
        pa.setTeamId(100);
        pa.setWin(true);
        ScoutSyncRequest.Participant pb = new ScoutSyncRequest.Participant();
        pb.setPuuid("p2");
        pb.setSummonerName("敌方乙");
        pb.setTeamId(100);
        pb.setWin(true);
        m.setParticipants(List.of(pa, pb));
        req.setMatches(List.of(m));
        return req;
    }

    @Test
    void 正常落库_段位upsert_摘要写入() {
        // 段位 upsert：首次无历史行，走 insert
        when(rankMapper.selectCount(any())).thenReturn(0L);
        // 摘要幂等：先查再插
        when(matchMapper.selectCount(any())).thenReturn(0L);

        service.ingest(request());

        // 段位：2 名玩家各 upsert 一次
        verify(rankMapper, times(2)).insert(any(EnemyScoutRank.class));
        // 摘要：1 局 2 名参与者 → 2 行插入
        verify(matchMapper, times(2)).insert(any(EnemyScoutMatch.class));
    }

    @Test
    void 段位重复喂入_覆盖更新() {
        // 段位已存在 → 走 update
        when(rankMapper.selectCount(any())).thenReturn(1L);
        when(rankMapper.update(any(), any())).thenReturn(1);

        service.ingest(request());

        verify(rankMapper, times(2)).update(any(EnemyScoutRank.class), any());
        verify(rankMapper, never()).insert(any(EnemyScoutRank.class));
    }

    @Test
    void 摘要重复喂入_幂等不重复写() {
        when(rankMapper.selectCount(any())).thenReturn(0L);
        // 摘要已存在 → 跳过插入
        when(matchMapper.selectCount(any())).thenReturn(1L);

        service.ingest(request());

        verify(matchMapper, never()).insert(any(EnemyScoutMatch.class));
    }

    @Test
    void 部分玩家已有历史_仅插入缺失行() {
        when(rankMapper.selectCount(any())).thenReturn(0L);
        // 第一行存在（返回 1），第二行不存在（返回 0）→ 只插第二行
        when(matchMapper.selectCount(any())).thenReturn(1L, 0L);

        service.ingest(request());

        // 2 名参与者，仅 1 行缺失 → 只 insert 1 次
        verify(matchMapper, times(1)).insert(any(EnemyScoutMatch.class));
    }

    @Test
    void 缓存命中查询_返回逐puuid命中() {
        // p1 有历史行，p2 无
        when(matchMapper.selectList(any())).thenReturn(List.of(row("p1")));

        Map<String, Boolean> hit = service.queryHit(List.of("p1", "p2"));

        assertThat(hit).containsExactly(Map.entry("p1", true), Map.entry("p2", false));
    }

    /** 造一行摘要实体 */
    private EnemyScoutMatch row(String puuid) {
        EnemyScoutMatch m = new EnemyScoutMatch();
        m.setPuuid(puuid);
        return m;
    }
}
