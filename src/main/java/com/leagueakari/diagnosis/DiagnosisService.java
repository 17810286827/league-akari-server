package com.leagueakari.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.dto.diagnosis.DiagnosisResponse;
import com.leagueakari.entity.ChampionClass;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.mapper.ChampionClassMapper;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对局诊断服务（工单 #36 / spec #30）：用 stats_json 中闲置字段回答"这局短板在哪"。
 * <p>五个诊断维度（超出战报图三指标——输出占比/承伤占比/伤害转化）：</p>
 * <ul>
 *   <li>{@link #DIM_VISION} 视野得分：插眼/排眼综合分（stats.visionScore）</li>
 *   <li>{@link #DIM_CC_TIME} 控制时长：控制他人秒数（stats.timeCCingOthers）</li>
 *   <li>{@link #DIM_DAMAGE_CONVERSION} 伤害转化：伤害/经济（越高越会花钱）</li>
 *   <li>{@link #DIM_OBJECTIVE_DAMAGE} 资源伤害：对防御塔/史诗野怪伤害</li>
 *   <li>{@link #DIM_GOLD_SHARE} 团队经济占比：本人金币/队伍总金币</li>
 * </ul>
 * <p>短板判定（败局才高亮）：队内末位 + 低于队均 × {@link #WEAK_RATIO}（0.6 倍）。
 * 职业豁免：辅助（SUPPORT）的视野/控制不判短板——辅助的伤害转化/经济占比天然低，
 * 但视野/控制恰是其主业，队内横向比较无意义。</p>
 * <p>与 OP Score 评分体系完全隔离：纯读取计算，不写库、不影响评分。</p>
 * <p>读取口径：经 {@link ParticipantStatsReader} 门面（统计读取已归一，不新开解析路径）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisService {

    /** 维度键：视野得分 */
    public static final String DIM_VISION = "vision";

    /** 维度键：控制时长（秒） */
    public static final String DIM_CC_TIME = "ccTime";

    /** 维度键：伤害转化（伤害/经济） */
    public static final String DIM_DAMAGE_CONVERSION = "damageConversion";

    /** 维度键：资源伤害（对塔/史诗野怪） */
    public static final String DIM_OBJECTIVE_DAMAGE = "objectiveDamage";

    /** 维度键：团队经济占比（0-1） */
    public static final String DIM_GOLD_SHARE = "goldShare";

    /** 短板判定比例：低于队均 × 该值且队内末位才判短板（避免轻微落后误报） */
    private static final double WEAK_RATIO = 0.6;

    /** 辅助职业的豁免维度（视野/控制是主业，队内横向比较无意义） */
    private static final List<String> SUPPORT_EXEMPT = List.of(DIM_VISION, DIM_CC_TIME);

    private final MatchMapper matchMapper;
    private final MatchParticipantMapper participantMapper;
    private final ChampionClassMapper championClassMapper;
    private final ParticipantStatsReader statsReader;
    private final GameDataService gameDataService;
    private final ObjectMapper objectMapper;

    /**
     * 诊断指定对局（视角 = self 所在队）
     *
     * @param gameId 对局 ID（LCU）
     * @return 诊断响应（全员维度列表）
     * @throws BizException 对局不存在（2001）
     */
    public DiagnosisResponse diagnose(Long gameId) {
        long startTime = System.currentTimeMillis();
        Match match = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", gameId));
        if (match == null) {
            throw new BizException(ErrorCode.MATCH_NOT_FOUND, "对局不存在: gameId=" + gameId);
        }
        List<MatchParticipant> participants = participantMapper.selectList(
                new QueryWrapper<MatchParticipant>()
                        .eq("match_id", match.getId())
                        .orderByAsc("id"));
        // 职业映射（championId → 职业名，缺表回退 FIGHTER）
        Map<Integer, String> classByChampion = championClassMapper.selectList(null).stream()
                .collect(Collectors.toMap(ChampionClass::getChampionId,
                        ChampionClass::getClassName, (a, b) -> a));

        // 视角 = self 所在队；无 self（异常数据）时取 100 队
        Integer perspectiveTeamId = participants.stream()
                .filter(p -> p.getPuuid() != null && p.getPuuid().equals(match.getSelfPuuid()))
                .map(MatchParticipant::getTeamId)
                .findFirst()
                .orElse(100);
        boolean win = match.getWinnerTeamId() != null
                && match.getWinnerTeamId().equals(perspectiveTeamId);

        // ---- 维度原始值计算（全员，含敌方——前端按队伍过滤）----
        // 队伍总金币（经济占比分母）
        Map<Integer, Double> teamGold = new HashMap<>();
        for (MatchParticipant p : participants) {
            double gold = statsReader.doubleVal(p.getStatsJson(), "goldEarned");
            teamGold.merge(p.getTeamId() == null ? 0 : p.getTeamId(), gold, Double::sum);
        }
        // 每名玩家的五维原始值
        Map<Long, Map<String, Double>> rawByPlayerId = new HashMap<>();
        for (MatchParticipant p : participants) {
            Map<String, Double> raw = new HashMap<>();
            String stats = p.getStatsJson();
            double damage = statsReader.doubleVal(stats, "totalDamageDealtToChampions");
            double gold = statsReader.doubleVal(stats, "goldEarned");
            raw.put(DIM_VISION, statsReader.doubleVal(stats, "visionScore"));
            raw.put(DIM_CC_TIME, statsReader.doubleVal(stats, "timeCCingOthers"));
            raw.put(DIM_DAMAGE_CONVERSION, gold > 0 ? damage / gold : 0);
            raw.put(DIM_OBJECTIVE_DAMAGE, statsReader.doubleVal(stats, "damageDealtToObjectives"));
            double teamTotal = teamGold.getOrDefault(p.getTeamId() == null ? 0 : p.getTeamId(), 0.0);
            raw.put(DIM_GOLD_SHARE, teamTotal > 0 ? gold / teamTotal : 0);
            rawByPlayerId.put(p.getId(), raw);
        }

        // ---- 队内位次与短板判定（按队伍分别排名）----
        List<DiagnosisResponse.PlayerDiagnosis> players = new ArrayList<>();
        Map<Integer, List<MatchParticipant>> byTeam = participants.stream()
                .collect(Collectors.groupingBy(p -> p.getTeamId() == null ? 0 : p.getTeamId()));
        for (MatchParticipant p : participants) {
            List<MatchParticipant> teammates = byTeam.getOrDefault(
                    p.getTeamId() == null ? 0 : p.getTeamId(), List.of(p));
            String championClass = classByChampion.getOrDefault(p.getChampionId(), "FIGHTER");
            boolean support = "SUPPORT".equals(championClass);
            List<DiagnosisResponse.Dimension> dimensions = new ArrayList<>();
            for (String key : List.of(DIM_VISION, DIM_CC_TIME, DIM_DAMAGE_CONVERSION,
                    DIM_OBJECTIVE_DAMAGE, DIM_GOLD_SHARE)) {
                double raw = rawByPlayerId.getOrDefault(p.getId(), Map.of()).getOrDefault(key, 0.0);
                // 队内位次（降序，1 = 最高）：位次 = 比我高的人数 + 1
                long higher = teammates.stream()
                        .filter(t -> rawByPlayerId.getOrDefault(t.getId(), Map.of())
                                .getOrDefault(key, 0.0) > raw)
                        .count();
                int rank = (int) higher + 1;
                double avg = teammates.stream()
                        .mapToDouble(t -> rawByPlayerId.getOrDefault(t.getId(), Map.of())
                                .getOrDefault(key, 0.0))
                        .average().orElse(0);
                // 短板：败局 + 队内末位 + 低于队均×WEAK_RATIO；辅助豁免维度恒 false
                boolean weak = !win
                        && rank == teammates.size()
                        && teammates.size() > 1
                        && raw < avg * WEAK_RATIO
                        && !(support && SUPPORT_EXEMPT.contains(key));
                dimensions.add(DiagnosisResponse.Dimension.builder()
                        .key(key)
                        .label(labelOf(key))
                        .rawValue(round2(raw))
                        .teamRank(rank)
                        .teamAverage(round2(avg))
                        .weak(weak)
                        .build());
            }
            players.add(DiagnosisResponse.PlayerDiagnosis.builder()
                    .name(p.getSummonerName() == null ? "" : p.getSummonerName())
                    .championName(gameDataService.championName(
                            p.getChampionId() == null ? 0 : p.getChampionId()))
                    .championClass(championClass)
                    .teamId(p.getTeamId())
                    .dimensions(dimensions)
                    .build());
        }

        log.info("Diagnosis computed: gameId={}, win={}, players={}, elapsed={}ms",
                gameId, win, players.size(), System.currentTimeMillis() - startTime);
        return DiagnosisResponse.builder()
                .win(win)
                .perspectiveTeamId(perspectiveTeamId)
                .players(players)
                .build();
    }

    /** 维度键 → 中文名 */
    private String labelOf(String key) {
        return switch (key) {
            case DIM_VISION -> "视野得分";
            case DIM_CC_TIME -> "控制时长";
            case DIM_DAMAGE_CONVERSION -> "伤害转化";
            case DIM_OBJECTIVE_DAMAGE -> "资源伤害";
            case DIM_GOLD_SHARE -> "团队经济占比";
            default -> key;
        };
    }

    /** 保留两位小数（展示友好） */
    private double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
