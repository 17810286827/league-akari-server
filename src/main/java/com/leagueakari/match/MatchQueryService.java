package com.leagueakari.match;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.match.MatchDetailResponse;
import com.leagueakari.dto.match.MatchSummaryResponse;
import com.leagueakari.dto.match.MvpAwardResponse;
import com.leagueakari.dto.common.PageResponse;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import com.leagueakari.scoring.MatchMvpService;

/**
 * 对局查询服务：列表/详情的视图组装（对局同步子系统的读取半边）
 * <p>职责：分页列表（筛选 + 玩家过滤 + 折叠卡聚合）与详情组装
 * （全量快照透传 + MVP/SVP 称号 + 全员实时评分）。
 * stats_json 解析（缺失补 0、challenges 嵌套）为私有口径，
 * 全局收拢见架构清理 spec 的统计读取门面票据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchQueryService {

    private final MatchMapper matchMapper;
    private final MatchParticipantMapper matchParticipantMapper;
    private final ObjectMapper objectMapper;
    /** MVP/SVP 评选编排：详情接口的全员实时评分调用其纯计算路径 */
    private final MatchMvpService matchMvpService;
    /** MVP/SVP 评选结果查询：详情/列表接口填充 mvp/svp 字段 */
    private final MatchMvpMapper matchMvpMapper;
    /** stats_json 读取门面：缺失补 0/false、challenges 嵌套口径的唯一实现（架构清理 T4） */
    private final ParticipantStatsReader statsReader;

    /**
     * 分页查询对局列表，支持队列与时间范围筛选
     * <p>筛选条件：queueId 精确匹配，startTime/endTime 为 game_creation
     * 时间戳区间，puuid / summonerName 二选一按玩家过滤（仅返回该玩家参与过的对局）；
     * 结果按创建时间倒序，返回精简的列表项 DTO。</p>
     * <p>玩家过滤必填（只允许查询指定玩家）：两者均缺失或空白时直接返回空页，避免暴露全量对局。
     * summonerName 用于数据库按参与者名称精确匹配（如 Riot puuid 与本地对局 ID 体系不一致的场景）。</p>
     */
    public PageResponse<MatchSummaryResponse> pageMatches(
            long page, long pageSize, Integer queueId, String puuid, String summonerName,
            Long startTime, Long endTime) {

        // 权限约束：只能查询指定玩家的对局，未提供任何玩家标识时返回空页
        boolean hasPuuid = puuid != null && !puuid.isBlank();
        boolean hasSummonerName = summonerName != null && !summonerName.isBlank();
        if (!hasPuuid && !hasSummonerName) {
            log.warn("Match list queried without player filter, return empty page");
            return new PageResponse<>(List.of(), page, pageSize, 0);
        }

        // 组装查询条件：可选的队列过滤与时间范围过滤
        QueryWrapper<Match> wrapper = new QueryWrapper<>();
        if (queueId != null) {
            wrapper.eq("queue_id", queueId);
        }
        if (startTime != null) {
            wrapper.ge("game_creation", startTime);
        }
        if (endTime != null) {
            wrapper.le("game_creation", endTime);
        }
        // 按玩家过滤：puuid 优先（精确），否则按召唤师名精确匹配；
        // 通过 match_id IN 缩小主表查询范围；无对局时直接返回空页。
        // 投影必须包含 puuid 列：summonerName 查询路径的查询者视角 puuid
        // 取自本结果（只查 match_id 时 getPuuid() 恒为 null，self 卡片会全部退化为占位）
        QueryWrapper<MatchParticipant> playerWrapper = new QueryWrapper<MatchParticipant>()
                .select("match_id", "puuid");
        if (hasPuuid) {
            playerWrapper.eq("puuid", puuid);
        } else {
            playerWrapper.eq("summoner_name", summonerName);
        }
        List<MatchParticipant> played = matchParticipantMapper.selectList(playerWrapper);
        if (played.isEmpty()) {
            log.info("No matches found for player: puuid={}, summonerName={}", puuid, summonerName);
            return new PageResponse<>(List.of(), page, pageSize, 0);
        }
        List<Long> playedMatchIds = played.stream()
                .map(MatchParticipant::getMatchId)
                .distinct()
                .toList();

        // 查询者视角 puuid：列表的 self 卡片/队友/对手聚合都以此为中心，
        // 而非 match.self_puuid（后者仅表示"该局数据由谁的客户端推送"，与查询者无关；
        // 单客户端部署下所有对局推送者都是同一人，误用会导致任何用户查询都看到推送者视角）
        String viewPuuid = hasPuuid ? puuid : played.get(0).getPuuid();
        wrapper.in("id", playedMatchIds);
        // 新对局排前面，便于客户端展示最近战绩
        wrapper.orderByDesc("game_creation");

        // 分页插件改写 SQL：自动生成 COUNT 与 LIMIT，total 为满足条件的总条数
        Page<Match> result = matchMapper.selectPage(new Page<>(page, pageSize), wrapper);

        // 收集本页对局主键，用于一次性批量查询参赛者，避免逐局查库的 N+1 问题
        List<Long> matchIds = result.getRecords().stream().map(Match::getId).toList();
        // 按 match_id 分组：本页所有参赛者一次查出，供各对局聚合 self/teamTotals/teammates
        Map<Long, List<MatchParticipant>> participantsByMatch = batchLoadParticipants(matchIds);

        // 实体转列表项 DTO：透传精简字段，并补充本玩家数据、队伍聚合与队友摘要
        // 批量加载本页对局的 MVP/SVP 评选记录（matchId IN 一次查询，避免逐局 N+1）
        Map<Long, List<MatchMvp>> awardsByMatch = batchLoadAwards(matchIds);
        List<MatchSummaryResponse> items = result.getRecords().stream().map(m -> {
            MatchSummaryResponse resp = new MatchSummaryResponse();
            resp.setGameId(m.getGameId());
            resp.setGameCreation(m.getGameCreation());
            resp.setGameDuration(m.getGameDuration());
            resp.setGameMode(m.getGameMode());
            resp.setMapId(m.getMapId());
            resp.setQueueId(m.getQueueId());
            resp.setRegion(m.getRegion());
            resp.setWinnerTeamId(m.getWinnerTeamId());
            resp.setSelfPuuid(viewPuuid);
            // 填充本玩家/队伍聚合/队友摘要：以查询者视角为中心，self 行缺失时输出全零占位，不抛错
            fillMatchExtras(resp, viewPuuid,
                    participantsByMatch.getOrDefault(m.getId(), List.of()));
            // 填充 MVP/SVP 称号（折叠卡据此给聚焦玩家挂图标；老数据无记录时保持 null）
            fillSummaryAwards(resp, awardsByMatch.getOrDefault(m.getId(), List.of()),
                    participantsByMatch.getOrDefault(m.getId(), List.of()));
            return resp;
        }).toList();

        log.info("Query matches: page={}, pageSize={}, total={}", page, pageSize, result.getTotal());
        PageResponse<MatchSummaryResponse> resp =
                new PageResponse<>(items, page, pageSize, result.getTotal());
        // 最近对手：从本页对局参与者聚合（查询视角玩家所在队之外的玩家），列表查询时即返回，无需展开详情
        resp.setRecentOpponents(buildRecentOpponents(result.getRecords(), participantsByMatch, viewPuuid));
        return resp;
    }

    /**
     * 查询对局详情（含参赛者列表），不存在抛 BizException(MATCH_NOT_FOUND)
     * <p>主表按 game_id 精确查询，命中后按主键关联参赛者明细；
     * 详情包含 teams_json 与各参赛者的 stats_json 全量快照。</p>
     */
    public MatchDetailResponse getMatchDetail(Long gameId) {
        // 按幂等键 game_id 查询主表记录
        Match match = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", gameId));
        if (match == null) {
            // 未命中：抛出带业务码的业务异常，全局处理器转为统一响应（HTTP 200 + 2001）
            log.warn("Match not found, gameId={}", gameId);
            throw new BizException(ErrorCode.MATCH_NOT_FOUND, "对局不存在: gameId=" + gameId);
        }

        // 按主表主键查询参赛者明细（match_participant.match_id 外键）；
        // 按 id 升序返回，与列表接口 batchLoadParticipants 口径一致，保证展开卡与折叠卡玩家顺序稳定
        List<MatchParticipant> participants = matchParticipantMapper.selectList(
                new QueryWrapper<MatchParticipant>()
                        .eq("match_id", match.getId())
                        .orderByAsc("id"));

        // 实体字段逐一透传到详情 DTO，保证响应契约字段齐全
        MatchDetailResponse resp = new MatchDetailResponse();
        resp.setGameId(match.getGameId());
        resp.setGameCreation(match.getGameCreation());
        resp.setGameDuration(match.getGameDuration());
        resp.setGameMode(match.getGameMode());
        resp.setGameType(match.getGameType());
        resp.setQueueId(match.getQueueId());
        resp.setMapId(match.getMapId());
        resp.setGameVersion(match.getGameVersion());
        resp.setRegion(match.getRegion());
        resp.setRsoPlatformId(match.getRsoPlatformId());
        resp.setDataSource(match.getDataSource());
        resp.setWinnerTeamId(match.getWinnerTeamId());
        resp.setSelfPuuid(match.getSelfPuuid());
        resp.setTeamsJson(match.getTeamsJson());
        resp.setParticipants(participants);
        // 填充 MVP/SVP 称号：查 match_mvp 并关联参赛者档案（老数据无记录时保持 null）
        fillMvpAwards(resp, match.getId(), participants);
        // 全员实时评分：查询时跑评分引擎（老对局同样可算，口径与落库评选一致）
        resp.setPlayerScores(matchMvpService.computeScores(match, participants));
        log.info("Query match detail: gameId={}, participants={}", gameId, participants.size());
        return resp;
    }

    /**
     * 聚合本页对局的"最近对手"：查询视角玩家所在队之外的其他玩家按 puuid 归并，
     * 胜负数按各局 win 累加，昵称/英雄取最后一次出现，按出现次数降序取前 5
     * （与前端 computeRecentOpponents 口径一致，改由后端在列表查询时一次性计算）
     *
     * @param viewPuuid 查询者视角 puuid：以其所在队伍划分敌我，而非对局推送者
     */
    private List<MatchSummaryResponse.RecentOpponent> buildRecentOpponents(
            List<Match> matches, Map<Long, List<MatchParticipant>> participantsByMatch, String viewPuuid) {
        // puuid → 聚合结果（LinkedHashMap 保持首次出现顺序，出现次数相同时排序稳定）
        Map<String, MatchSummaryResponse.RecentOpponent> map = new LinkedHashMap<>();
        Map<String, Integer> appearCount = new HashMap<>();
        for (Match match : matches) {
            List<MatchParticipant> participants =
                    participantsByMatch.getOrDefault(match.getId(), List.of());
            // 定位查询视角玩家所在队伍：该行缺失时跳过本局（异常数据）
            MatchParticipant self = participants.stream()
                    .filter(p -> Objects.equals(viewPuuid, p.getPuuid()))
                    .findFirst()
                    .orElse(null);
            if (self == null) {
                continue;
            }
            for (MatchParticipant p : participants) {
                // 跳过本队成员（含 self 自己）
                if (Objects.equals(p.getTeamId(), self.getTeamId())) {
                    continue;
                }
                MatchSummaryResponse.RecentOpponent agg =
                        map.computeIfAbsent(p.getPuuid(), k -> {
                            MatchSummaryResponse.RecentOpponent o =
                                    new MatchSummaryResponse.RecentOpponent();
                            o.setPuuid(p.getPuuid());
                            o.setWins(0);
                            o.setLosses(0);
                            return o;
                        });
                // 昵称与英雄取最后一次出现
                agg.setSummonerName(p.getSummonerName());
                agg.setChampionId(p.getChampionId());
                if (Boolean.TRUE.equals(p.getWin())) {
                    agg.setWins(agg.getWins() + 1);
                } else {
                    agg.setLosses(agg.getLosses() + 1);
                }
                appearCount.merge(p.getPuuid(), 1, Integer::sum);
            }
        }
        return map.values().stream()
                .sorted((a, b) -> appearCount.get(b.getPuuid()) - appearCount.get(a.getPuuid()))
                .limit(5)
                .toList();
    }

    /**
     * 批量查询本页对局的参赛者并按对局分组：
     * 一次 IN(match_id) 查询取代逐局查询，控制数据库往返次数
     */
    private Map<Long, List<MatchParticipant>> batchLoadParticipants(List<Long> matchIds) {
        if (matchIds.isEmpty()) {
            // 本页无对局时直接返回空映射，避免无意义的 IN 查询
            return Map.of();
        }
        List<MatchParticipant> participants = matchParticipantMapper.selectList(
                new QueryWrapper<MatchParticipant>().in("match_id", matchIds).orderByAsc("id"));
        return participants.stream().collect(Collectors.groupingBy(MatchParticipant::getMatchId));
    }

    /**
     * 为列表项补充本玩家（self）、队伍聚合（teamTotals）、队友摘要（teammates）
     * 与双方 10 人轻量档案（participants）；
     * 若 self 行缺失（异常数据），self/teamTotals/teammates 返回占位且不抛错，保证列表接口始终可用
     */
    private void fillMatchExtras(MatchSummaryResponse resp, String selfPuuid,
                                 List<MatchParticipant> participants) {
        // 10 人全量轻量档案与 self 是否缺失无关，统一由本页参赛者构建（前端以 puuid 区分 self）
        resp.setParticipants(buildParticipants(participants));

        // 定位本玩家行：puuid 与主表 self_puuid 一致（null 安全比较）
        MatchParticipant self = participants.stream()
                .filter(p -> Objects.equals(selfPuuid, p.getPuuid()))
                .findFirst().orElse(null);

        if (self == null) {
            // 异常数据兜底：self 行缺失时输出全零占位、teammates 空列表
            log.warn("Self participant missing, use placeholder: gameId={}", resp.getGameId());
            resp.setSelf(placeholderSelf());
            resp.setTeamTotals(placeholderTeamTotals());
            resp.setTeammates(List.of());
            return;
        }

        // 同队成员：与 self 相同 teamId 的参赛者（正常为含 self 共 5 人）
        List<MatchParticipant> teamMembers = participants.stream()
                .filter(p -> Objects.equals(p.getTeamId(), self.getTeamId()))
                .toList();

        resp.setSelf(buildSelf(self));
        resp.setTeamTotals(buildTeamTotals(teamMembers));
        resp.setTeammates(buildTeammates(teamMembers, self));
    }

    /**
     * 组装本玩家个人数据：身份与击杀/死亡/助攻来自直显列，
     * 伤害/经济/补刀/标记字段来自 stats_json 解析（缺失写 0/false）
     */
    private MatchSummaryResponse.SelfSummary buildSelf(MatchParticipant self) {
        MatchSummaryResponse.SelfSummary s = new MatchSummaryResponse.SelfSummary();
        // 身份字段：直显列原样透传
        s.setChampionId(self.getChampionId());
        s.setSummonerName(self.getSummonerName());
        // 直显统计缺失时写 0，保证响应字段非 null
        s.setKills(self.getKills() == null ? 0 : self.getKills());
        s.setDeaths(self.getDeaths() == null ? 0 : self.getDeaths());
        s.setAssists(self.getAssists() == null ? 0 : self.getAssists());
        s.setWin(self.getWin());
        // stats 快照字段：经门面读取（缺失写 0/false/空列表），不 JsonNode 中转
        String statsJson = self.getStatsJson();
        s.setTotalDamage(statsReader.intVal(statsJson, "totalDamageDealtToChampions"));
        s.setTotalDamageTaken(statsReader.intVal(statsJson, "totalDamageTaken"));
        s.setGoldEarned(statsReader.intVal(statsJson, "goldEarned"));
        s.setCs(statsReader.intVal(statsJson, "totalMinionsKilled"));
        s.setLargestMultiKill(statsReader.intVal(statsJson, "largestMultiKill"));
        s.setTurretKills(statsReader.intVal(statsJson, "turretKills"));
        s.setGameEndedInSurrender(statsReader.boolVal(statsJson, "gameEndedInSurrender"));
        // 折叠卡增强字段：出装/技能/海克斯/符文 + 多杀，全部从 stats_json 提取，缺失写空列表或 0
        s.setItems(statsReader.listVal(statsJson, "item0", "item1", "item2", "item3", "item4", "item5", "item6"));
        s.setSummonerSpells(statsReader.listVal(statsJson, "spell1Id", "spell2Id"));
        s.setAugments(statsReader.listVal(statsJson, "playerAugment1", "playerAugment2", "playerAugment3",
                "playerAugment4", "playerAugment5", "playerAugment6"));
        s.setPerks(buildPerks(statsJson));
        s.setDoubleKills(statsReader.intVal(statsJson, "doubleKills"));
        s.setTripleKills(statsReader.intVal(statsJson, "tripleKills"));
        s.setQuadraKills(statsReader.intVal(statsJson, "quadraKills"));
        s.setPentaKills(statsReader.intVal(statsJson, "pentaKills"));
        return s;
    }

    /**
     * 聚合队伍统计：对同队参赛者的直显击杀/经济与 stats 伤害求和，
     * 单项缺失视为 0，保证聚合结果非 null
     */
    private MatchSummaryResponse.TeamTotals buildTeamTotals(List<MatchParticipant> teamMembers) {
        MatchSummaryResponse.TeamTotals t = new MatchSummaryResponse.TeamTotals();
        int kills = 0;
        int gold = 0;
        int damage = 0;
        int damageTaken = 0;
        for (MatchParticipant p : teamMembers) {
            // 直显列缺失视为 0
            kills += p.getKills() == null ? 0 : p.getKills();
            gold += p.getGoldEarned() == null ? 0 : p.getGoldEarned();
            // stats 伤害字段经门面读取，缺失视为 0
            damage += statsReader.intVal(p.getStatsJson(), "totalDamageDealtToChampions");
            damageTaken += statsReader.intVal(p.getStatsJson(), "totalDamageTaken");
        }
        t.setKills(kills);
        t.setGold(gold);
        t.setDamage(damage);
        t.setDamageTaken(damageTaken);
        return t;
    }

    /**
     * 组装队友摘要：同队其余参赛者（排除 self），
     * 正常为 4 人，供最近队友聚合与卡片队友展示
     */
    private List<MatchSummaryResponse.Teammate> buildTeammates(
            List<MatchParticipant> teamMembers, MatchParticipant self) {
        return teamMembers.stream()
                .filter(p -> !Objects.equals(p.getPuuid(), self.getPuuid()))
                .map(p -> {
                    MatchSummaryResponse.Teammate t = new MatchSummaryResponse.Teammate();
                    // 队友摘要只需身份与胜负字段，不携带统计明细
                    t.setPuuid(p.getPuuid());
                    t.setSummonerName(p.getSummonerName());
                    t.setChampionId(p.getChampionId());
                    t.setWin(p.getWin());
                    return t;
                })
                .toList();
    }

    /**
     * 组装双方 10 人轻量档案：直显列透传 + stats_json 提取出装/技能/海克斯/符文，
     * 含 self 行（前端以 puuid 区分）；stats 缺失时列表字段为空列表、数值写 0
     */
    private List<MatchSummaryResponse.ParticipantLight> buildParticipants(
            List<MatchParticipant> participants) {
        return participants.stream().map(p -> {
            MatchSummaryResponse.ParticipantLight pl = new MatchSummaryResponse.ParticipantLight();
            // 直显列：身份/队伍/分路/胜负与击杀助攻，缺失数值写 0
            pl.setPuuid(p.getPuuid());
            pl.setSummonerName(p.getSummonerName());
            pl.setChampionId(p.getChampionId());
            pl.setTeamId(p.getTeamId());
            pl.setPosition(p.getPosition());
            pl.setWin(p.getWin());
            pl.setKills(p.getKills() == null ? 0 : p.getKills());
            pl.setDeaths(p.getDeaths() == null ? 0 : p.getDeaths());
            pl.setAssists(p.getAssists() == null ? 0 : p.getAssists());
            // stats 快照字段：item0-6 与 spell1Id/spell2Id 在 LCU/SGP 均位于 stats 顶层，读取路径统一
            String statsJson = p.getStatsJson();
            pl.setItems(statsReader.listVal(statsJson, "item0", "item1", "item2", "item3", "item4", "item5", "item6"));
            pl.setSummonerSpells(statsReader.listVal(statsJson, "spell1Id", "spell2Id"));
            pl.setAugments(statsReader.listVal(statsJson, "playerAugment1", "playerAugment2", "playerAugment3",
                    "playerAugment4", "playerAugment5", "playerAugment6"));
            pl.setPerks(buildPerks(statsJson));
            // 折叠卡统计行/雷达图字段：LCU/SGP 字段名一致，缺失写 0
            pl.setTotalDamageDealtToChampions(statsReader.intVal(statsJson, "totalDamageDealtToChampions"));
            pl.setTotalDamageTaken(statsReader.intVal(statsJson, "totalDamageTaken"));
            pl.setTotalHeal(statsReader.intVal(statsJson, "totalHeal"));
            pl.setVisionScore(statsReader.intVal(statsJson, "visionScore"));
            pl.setGoldEarned(statsReader.intVal(statsJson, "goldEarned"));
            // 补刀口径与详情接口一致（小兵 + 野怪），避免折叠卡补兵标签与展开态判定不一致
            pl.setCs(statsReader.intVal(statsJson, "neutralMinionsKilled") + statsReader.intVal(statsJson, "totalMinionsKilled"));
            pl.setTurretKills(statsReader.intVal(statsJson, "turretKills"));
            pl.setWardsPlaced(statsReader.intVal(statsJson, "wardsPlaced"));
            // 折叠卡成就标签字段：多杀/拆塔/护盾/控制读 stats 顶层，
            // 单杀/塔杀/补刀压制/击飞击杀读 SGP challenges（LCU 缺失按 0）
            pl.setTotalDamageToTowers(statsReader.intVal(statsJson, "damageDealtToTurrets"));
            pl.setDoubleKills(statsReader.intVal(statsJson, "doubleKills"));
            pl.setTripleKills(statsReader.intVal(statsJson, "tripleKills"));
            pl.setQuadraKills(statsReader.intVal(statsJson, "quadraKills"));
            pl.setPentaKills(statsReader.intVal(statsJson, "pentaKills"));
            pl.setTotalDamageShieldedOnTeammates(statsReader.intVal(statsJson, "totalDamageShieldedOnTeammates"));
            pl.setTimeCCingOthers(statsReader.intVal(statsJson, "timeCCingOthers"));
            pl.setSoloKills(statsReader.challengeInt(statsJson, "soloKills"));
            pl.setKillsNearEnemyTurret(statsReader.challengeInt(statsJson, "killsNearEnemyTurret"));
            pl.setKillsUnderOwnTurret(statsReader.challengeInt(statsJson, "killsUnderOwnTurret"));
            pl.setMaxCsAdvantageOnLaneOpponent(statsReader.challengeInt(statsJson, "maxCsAdvantageOnLaneOpponent"));
            pl.setKnockEnemyIntoTeamAndKill(statsReader.challengeInt(statsJson, "knockEnemyIntoTeamAndKill"));
            // 召唤师账号等级：顶部玩家信息展示（缺失按 0）
            pl.setSummonerLevel(statsReader.intVal(statsJson, "summonerLevel"));
            // 召唤师头像 ID：顶部玩家头像展示（缺失按 0）
            pl.setProfileIcon(statsReader.intVal(statsJson, "profileIcon"));
            return pl;
        }).toList();
    }

    /**
     * 组装参赛者符文配置，双路径探测（仅 perks 需要兼容两种来源）：
     * 1. SGP 嵌套：stats.perks 为对象，读取 perkIds/perkStyle/perkSubStyle；
     * 2. LCU 平铺：perk0-5 + perkPrimaryStyle + perkSubStyle 位于 stats 顶层。
     * 两条路径字段缺失时均为空列表/0，保证响应结构稳定
     */
    private MatchSummaryResponse.ParticipantPerks buildPerks(String statsJson) {
        MatchSummaryResponse.ParticipantPerks perks = new MatchSummaryResponse.ParticipantPerks();
        JsonNode nested = statsReader.nested(statsReader.node(statsJson), "perks");
        if (nested != null) {
            // SGP 嵌套路径：符文 ID 为数组字段，样式字段为对象内标量
            perks.setPerkIds(statsReader.arrayVal(nested, "perkIds"));
            perks.setPerkStyle(nested.path("perkStyle").asInt(0));
            perks.setPerkSubStyle(nested.path("perkSubStyle").asInt(0));
        } else {
            // LCU 平铺路径：6 颗符文 + 主副系样式均位于 stats 顶层；
            // stats 整体缺失（null/损坏）时 listVal 空列表、intVal 0，
            // 与"缺失写空列表/0"契约一致（placeholderSelf 同款输出）
            perks.setPerkIds(statsReader.listVal(statsJson, "perk0", "perk1", "perk2", "perk3", "perk4", "perk5"));
            perks.setPerkStyle(statsReader.intVal(statsJson, "perkPrimaryStyle"));
            perks.setPerkSubStyle(statsReader.intVal(statsJson, "perkSubStyle"));
        }
        return perks;
    }

    /**
     * self 行缺失时的全零占位：保证列表响应字段结构稳定，前端无需判空
     */
    private MatchSummaryResponse.SelfSummary placeholderSelf() {
        MatchSummaryResponse.SelfSummary s = new MatchSummaryResponse.SelfSummary();
        // 全零占位：数值 0、布尔 false、召唤师名空串、列表字段空列表、符文占位对象
        s.setChampionId(0);
        s.setSummonerName("");
        s.setKills(0);
        s.setDeaths(0);
        s.setAssists(0);
        s.setWin(false);
        s.setTotalDamage(0);
        s.setTotalDamageTaken(0);
        s.setGoldEarned(0);
        s.setCs(0);
        s.setLargestMultiKill(0);
        s.setTurretKills(0);
        s.setGameEndedInSurrender(false);
        s.setItems(List.of());
        s.setSummonerSpells(List.of());
        s.setAugments(List.of());
        // 符文占位：空 perkIds + 样式 0，保持折叠卡渲染结构稳定
        MatchSummaryResponse.ParticipantPerks perks = new MatchSummaryResponse.ParticipantPerks();
        perks.setPerkIds(List.of());
        perks.setPerkStyle(0);
        perks.setPerkSubStyle(0);
        s.setPerks(perks);
        s.setDoubleKills(0);
        s.setTripleKills(0);
        s.setQuadraKills(0);
        s.setPentaKills(0);
        return s;
    }

    /**
     * 队伍聚合缺失时的全零占位（self 行缺失时使用）
     */
    private MatchSummaryResponse.TeamTotals placeholderTeamTotals() {
        MatchSummaryResponse.TeamTotals t = new MatchSummaryResponse.TeamTotals();
        // 全零占位：四项聚合均写 0
        t.setKills(0);
        t.setGold(0);
        t.setDamage(0);
        t.setDamageTaken(0);
        return t;
    }

    /**
     * 填充详情响应的 mvp/svp 字段
     * <p>按 match_id 查评选结果，两条记录分别对应 MVP/SVP；
     * 关联参赛者档案补充 puuid/summonerName/championId。</p>
     */
    private void fillMvpAwards(MatchDetailResponse resp, Long matchId, List<MatchParticipant> participants) {
        List<MatchMvp> awards = matchMvpMapper.selectList(
                new QueryWrapper<MatchMvp>().eq("match_id", matchId));
        if (awards == null || awards.isEmpty()) {
            return;
        }
        applyAwards(awards, participantsByPuuidHolder(participants),
                (type, dto) -> {
                    if ("MVP".equals(type)) {
                        resp.setMvp(dto);
                    } else if ("ACE".equals(type) || "SVP".equals(type)) {
                        resp.setAce(dto);
                    }
                });
    }

    /**
     * 填充列表响应的 mvp/svp 字段（评选记录已由 batchLoadAwards 批量查出）
     */
    private void fillSummaryAwards(MatchSummaryResponse resp, List<MatchMvp> awards,
                                   List<MatchParticipant> participants) {
        if (awards.isEmpty()) {
            return;
        }
        applyAwards(awards, participantsByPuuidHolder(participants),
                (type, dto) -> {
                    if ("MVP".equals(type)) {
                        resp.setMvp(dto);
                    } else if ("ACE".equals(type) || "SVP".equals(type)) {
                        resp.setAce(dto);
                    }
                });
    }

    /**
     * 批量加载本页对局的评选记录（matchId IN 一次查询，避免逐局 N+1）
     */
    private Map<Long, List<MatchMvp>> batchLoadAwards(List<Long> matchIds) {
        if (matchIds == null || matchIds.isEmpty()) {
            return Map.of();
        }
        List<MatchMvp> awards = matchMvpMapper.selectList(
                new QueryWrapper<MatchMvp>().in("match_id", matchIds));
        if (awards == null || awards.isEmpty()) {
            return Map.of();
        }
        return awards.stream().collect(Collectors.groupingBy(MatchMvp::getMatchId));
    }

    /** 参赛者档案索引：participantId → 参赛者（称号持有者档案补充用） */
    private Map<Long, MatchParticipant> participantsByPuuidHolder(List<MatchParticipant> participants) {
        return participants.stream()
                .collect(Collectors.toMap(MatchParticipant::getId, p -> p, (a, b) -> a));
    }

    /**
     * 遍历评选记录组装称号 DTO 并回调分发（详情/列表响应共用）
     */
    private void applyAwards(List<MatchMvp> awards,
                             Map<Long, MatchParticipant> holderById,
                             java.util.function.BiConsumer<String, MvpAwardResponse> setter) {
        for (MatchMvp award : awards) {
            MatchParticipant holder = holderById.get(award.getParticipantId());
            if (holder == null) {
                // 评选记录与参赛者对不上（异常数据）：跳过该称号
                log.warn("MVP award participant missing: matchId={}, participantId={}",
                        award.getMatchId(), award.getParticipantId());
                continue;
            }
            MvpAwardResponse dto = new MvpAwardResponse();
            dto.setParticipantId(award.getParticipantId());
            dto.setPuuid(holder.getPuuid());
            dto.setSummonerName(holder.getSummonerName());
            dto.setChampionId(holder.getChampionId());
            dto.setScore(award.getScore());
            dto.setOpScore(award.getOpScore());
            dto.setGrade(award.getGrade());
            setter.accept(award.getType(), dto);
        }
    }
}
