package com.leagueakari.intel;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.leagueakari.dto.intel.ScoutSyncRequest;
import com.leagueakari.entity.EnemyScoutMatch;
import com.leagueakari.entity.EnemyScoutRank;
import com.leagueakari.mapper.EnemyScoutMatchMapper;
import com.leagueakari.mapper.EnemyScoutRankMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敌方侦察数据 ingest 服务：桌面端选人阶段喂入的敌方段位与历史摘要的幂等落库。
 * <p>职责：</p>
 * <ul>
 *   <li>段位按 puuid 幂等 upsert（一人一行，重复喂入覆盖更新）；</li>
 *   <li>历史摘要按 (match_id, puuid) 幂等写入（重复推送不产生重复行）；</li>
 *   <li>提供缓存命中查询（给定 puuid 列表，返回哪些人在缓存内有历史），供桌面端减量喂与发卡读取。</li>
 * </ul>
 * <p>计算（开黑判定/窗口胜率）不在此类，见 {@link PremadeDetector} / {@link WindowWinRate}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoutIngestService {

    private final EnemyScoutMatchMapper matchMapper;
    private final EnemyScoutRankMapper rankMapper;

    /**
     * 幂等接收敌方侦察数据：段位 upsert + 摘要幂等写入
     *
     * @param request 桌面端喂入的段位与历史摘要
     */
    @Transactional
    public void ingest(ScoutSyncRequest request) {
        // 1) 段位：按 puuid upsert（存在则覆盖，改名/升段更新）
        List<ScoutSyncRequest.PlayerRank> players = request.getPlayers();
        if (players != null) {
            for (ScoutSyncRequest.PlayerRank p : players) {
                upsertRank(p);
            }
            log.info("Scout ingest ranks: count={}", players.size());
        }

        // 2) 历史摘要：按 (match_id, puuid) 先查后插，幂等不重复写
        int inserted = 0;
        int skipped = 0;
        List<ScoutSyncRequest.MatchSummary> matches = request.getMatches();
        if (matches != null) {
            for (ScoutSyncRequest.MatchSummary m : matches) {
                for (ScoutSyncRequest.Participant p : m.getParticipants()) {
                    if (insertMatchRow(m, p)) {
                        inserted++;
                    } else {
                        skipped++;
                    }
                }
            }
        }
        log.info("Scout ingest matches: inserted={}, skipped(already exists)={}", inserted, skipped);
    }

    /** 段位 upsert：按 puuid 查，存在则 update，不存在则 insert */
    private void upsertRank(ScoutSyncRequest.PlayerRank p) {
        Long exists = rankMapper.selectCount(
                new QueryWrapper<EnemyScoutRank>().eq("puuid", p.getPuuid()));
        if (exists != null && exists > 0) {
            // 已存在：覆盖更新（改名/升段）
            rankMapper.update(new EnemyScoutRank(), new UpdateWrapper<EnemyScoutRank>()
                    .set("summoner_name", p.getSummonerName())
                    .set("queue_type", p.getQueueType())
                    .set("tier", p.getTier())
                    .set("rank", p.getRank())
                    .eq("puuid", p.getPuuid()));
            return;
        }
        // 不存在：插入快照
        EnemyScoutRank rank = new EnemyScoutRank();
        rank.setPuuid(p.getPuuid());
        rank.setSummonerName(p.getSummonerName());
        rank.setQueueType(p.getQueueType());
        rank.setTier(p.getTier());
        rank.setRank(p.getRank());
        rank.setCreatedAt(LocalDateTime.now());
        rank.setUpdatedAt(LocalDateTime.now());
        rankMapper.insert(rank);
    }

    /** 摘要行幂等写入：存在返回 false，插入成功返回 true */
    private boolean insertMatchRow(ScoutSyncRequest.MatchSummary m, ScoutSyncRequest.Participant p) {
        Long exists = matchMapper.selectCount(new QueryWrapper<EnemyScoutMatch>()
                .eq("match_id", m.getMatchId())
                .eq("puuid", p.getPuuid()));
        if (exists != null && exists > 0) {
            return false;
        }
        EnemyScoutMatch row = new EnemyScoutMatch();
        row.setMatchId(m.getMatchId());
        row.setPuuid(p.getPuuid());
        row.setSummonerName(p.getSummonerName());
        row.setTeamId(p.getTeamId());
        row.setWin(p.getWin());
        row.setQueueId(m.getQueueId());
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        matchMapper.insert(row);
        return true;
    }

    /**
     * 缓存命中查询：给定一批 puuid，返回每个是否在侦察缓存内有历史摘要行
     *
     * @param puuids 待查询玩家标识列表
     * @return puuid → 是否命中（保持入参顺序，无重复）
     */
    public Map<String, Boolean> queryHit(List<String> puuids) {
        List<String> distinct = puuids.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        // 一次查询：在缓存内有行的 distinct puuid（去重）
        List<EnemyScoutMatch> rows = matchMapper.selectList(
                new QueryWrapper<EnemyScoutMatch>()
                        .select("DISTINCT puuid")
                        .in("puuid", distinct));
        Map<String, Boolean> hitMap = new LinkedHashMap<>();
        for (String puuid : distinct) {
            boolean hit = rows.stream().anyMatch(r -> puuid.equals(r.getPuuid()));
            hitMap.put(puuid, hit);
        }
        return hitMap;
    }
}
