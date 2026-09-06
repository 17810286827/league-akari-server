package com.leagueakari.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.dto.replay.ReplayResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.leagueakari.match.MatchTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时间线复盘聚合服务（工单 #35 / spec #29）：
 * 装载对局 + 参赛者 + 时间线 → 解析视角（self 所在队）→ 委托
 * {@link TurningPointEngine} 确定性计算复盘数据。
 * <p>降级口径：无时间线（或快照损坏）返回 available=false 的空响应——
 * 前端显示提示而非空白曲线，**不抛 2002**（时间线缺失是常态而非错误，
 * 历史回填的局普遍没有时间线）。对局不存在仍抛 2001（与详情接口口径一致）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayService {

    private final MatchMapper matchMapper;
    private final MatchParticipantMapper participantMapper;
    private final MatchTimelineService timelineService;
    private final TurningPointEngine turningPointEngine;
    private final GameDataService gameDataService;
    /** stats_json 读取门面：缺失补 0 口径的唯一实现（AI 摘要加厚 spec #43 沿用） */
    private final ParticipantStatsReader statsReader;
    private final ObjectMapper objectMapper;

    /**
     * 计算指定对局的时间线复盘数据
     *
     * @param gameId 对局 ID（LCU）
     * @return 复盘响应；无时间线时 available=false（各集合为空）
     * @throws BizException 对局不存在（2001）
     */
    public ReplayResponse replay(Long gameId) {
        long startTime = System.currentTimeMillis();
        // 对局存在性校验（与详情接口同口径）：不存在抛 2001
        Match match = matchMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Match>()
                        .eq("game_id", gameId));
        if (match == null) {
            throw new BizException(ErrorCode.MATCH_NOT_FOUND, "对局不存在: gameId=" + gameId);
        }

        // 参赛者按上报顺序（id 升序）装载——"上报数组顺序 = 局内序号 1..N"
        List<MatchParticipant> participants = participantMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MatchParticipant>()
                        .eq("match_id", match.getId())
                        .orderByAsc("id"));

        // 时间线装载：缺失/损坏返回 null（getTimeline 内部已容错）
        Object frames = timelineService.getTimeline(gameId);
        if (frames == null) {
            // 降级：无时间线是常态（历史回填的局普遍缺失），available=false 而非报错
            log.info("Replay degraded: timeline missing, gameId={}, participants={}",
                    gameId, participants.size());
            return ReplayResponse.builder()
                    .available(false)
                    .goldDiffSeries(List.of())
                    .killEvents(List.of())
                    .turningPoints(List.of())
                    .build();
        }

        // 视角解析：self（推送者）所在队伍为"我方"；解析不出时引擎回退 100
        Integer perspectiveTeamId = participants.stream()
                .filter(p -> p.getPuuid() != null && p.getPuuid().equals(match.getSelfPuuid()))
                .map(MatchParticipant::getTeamId)
                .findFirst()
                .orElse(null);

        // 局内序号 → 参与者信息（英雄 ID 在引擎调用前转中文名，避免模型/展示层猜 ID）
        Map<Integer, TurningPointEngine.ParticipantInfo> slotInfo = new HashMap<>();
        for (int i = 0; i < participants.size(); i++) {
            MatchParticipant p = participants.get(i);
            slotInfo.put(i + 1, new TurningPointEngine.ParticipantInfo(
                    p.getSummonerName() == null ? "" : p.getSummonerName(),
                    gameDataService.championName(p.getChampionId() == null ? 0 : p.getChampionId()),
                    p.getChampionId() == null ? 0 : p.getChampionId(),
                    p.getTeamId() == null ? 0 : p.getTeamId()));
        }

        // 纯函数计算（JsonNode 转换与 WeeklyReportService 的时间线解析口径一致）
        JsonNode framesNode = objectMapper.valueToTree(frames);
        ReplayResponse response = turningPointEngine.compute(framesNode, slotInfo, perspectiveTeamId);
        // 对局 ID 回填（AI 摘要组装时回查玩家 stats 用）
        response.setGameId(gameId);
        log.info("Replay computed: gameId={}, perspective={}, frames={}, killEvents={}, turningPoints={}, "
                        + "elapsed={}ms",
                gameId, response.getPerspectiveTeamId(), response.getGoldDiffSeries().size(),
                response.getKillEvents().size(), response.getTurningPoints().size(),
                System.currentTimeMillis() - startTime);
        return response;
    }

    /**
     * 双方玩家的个人 stats 概要（AI 加厚 spec #43）：KDA/伤害/经济/装备（中文名），
     * 供复盘叙述评价"谁在转折点里干了什么"；缺失字段跳过（LCU/SGP 与 Riot 两类局并存）。
     * 视角（ally 标记）与 replay() 同口径：self 所在队为我方。
     *
     * @param gameId 对局 ID（LCU）
     * @return 玩家概要列表（短键：name/champ/ally/kda/dmg/gold/items）
     * @throws BizException 对局不存在（2001）
     */
    public List<Map<String, Object>> playerStats(Long gameId) {
        Match match = matchMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Match>()
                        .eq("game_id", gameId));
        if (match == null) {
            throw new BizException(ErrorCode.MATCH_NOT_FOUND, "对局不存在: gameId=" + gameId);
        }
        List<MatchParticipant> participants = participantMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MatchParticipant>()
                        .eq("match_id", match.getId())
                        .orderByAsc("id"));
        // 视角队伍：self 所在队（与 replay() 同判定）
        Integer perspectiveTeamId = participants.stream()
                .filter(p -> p.getPuuid() != null && p.getPuuid().equals(match.getSelfPuuid()))
                .map(MatchParticipant::getTeamId)
                .findFirst()
                .orElse(null);

        List<Map<String, Object>> out = new ArrayList<>();
        for (MatchParticipant p : participants) {
            Map<String, Object> player = new LinkedHashMap<>();
            player.put("name", p.getSummonerName() == null ? "" : p.getSummonerName());
            player.put("champ", gameDataService.championName(
                    p.getChampionId() == null ? 0 : p.getChampionId()));
            player.put("ally", perspectiveTeamId != null
                    && perspectiveTeamId.equals(p.getTeamId()));
            player.put("kda", List.of(p.getKills() == null ? 0 : p.getKills(),
                    p.getDeaths() == null ? 0 : p.getDeaths(),
                    p.getAssists() == null ? 0 : p.getAssists()));
            player.put("dmg", (int) statsReader.doubleVal(p.getStatsJson(),
                    "totalDamageDealtToChampions"));
            player.put("gold", (int) statsReader.doubleVal(p.getStatsJson(), "goldEarned"));
            // 出装 7 槽转中文名（"收集者+无尽但伤害占比垫底"式点评的素材）
            try {
                JsonNode stats = objectMapper.readTree(
                        p.getStatsJson() == null ? "{}" : p.getStatsJson());
                List<String> items = new ArrayList<>();
                for (int i = 0; i < 7; i++) {
                    if (stats.has("item" + i) && !stats.get("item" + i).isNull()) {
                        items.add(gameDataService.itemName(stats.get("item" + i).asInt()));
                    }
                }
                if (!items.isEmpty()) {
                    player.put("items", items);
                }
            } catch (Exception e) {
                log.warn("Parse statsJson for replay player stats failed: name={}, error={}",
                        p.getSummonerName(), e.getMessage());
            }
            out.add(player);
        }
        log.debug("Replay player stats assembled: gameId={}, players={}", gameId, out.size());
        return out;
    }
}
