package com.leagueakari.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
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

import java.util.HashMap;
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
                    p.getTeamId() == null ? 0 : p.getTeamId()));
        }

        // 纯函数计算（JsonNode 转换与 WeeklyReportService 的时间线解析口径一致）
        JsonNode framesNode = objectMapper.valueToTree(frames);
        ReplayResponse response = turningPointEngine.compute(framesNode, slotInfo, perspectiveTeamId);
        log.info("Replay computed: gameId={}, perspective={}, frames={}, killEvents={}, turningPoints={}, "
                        + "elapsed={}ms",
                gameId, response.getPerspectiveTeamId(), response.getGoldDiffSeries().size(),
                response.getKillEvents().size(), response.getTurningPoints().size(),
                System.currentTimeMillis() - startTime);
        return response;
    }
}
