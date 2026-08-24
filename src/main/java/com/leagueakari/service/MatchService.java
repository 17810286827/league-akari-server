package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.MatchDetailResponse;
import com.leagueakari.dto.MatchSummaryResponse;
import com.leagueakari.dto.MatchSyncRequest;
import com.leagueakari.dto.MvpAwardResponse;
import com.leagueakari.dto.PageResponse;
import com.leagueakari.dto.ParticipantSyncRequest;
import com.leagueakari.dto.TeamSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
    /** MVP/SVP 评选编排：参与者落库后触发评选落库 */
    private final MatchMvpService matchMvpService;
    /** MVP/SVP 评选结果查询：详情接口填充 mvp/svp 字段 */
    private final MatchMvpMapper matchMvpMapper;

    /**
     * 幂等保存对局（先查后插）：
     * 1. 按 game_id 查重，已存在则直接跳过，避免重复入库；
     * 2. 不存在则写入 match 主表，并逐条写入参赛者明细。
     */
    @Transactional
    public void saveMatch(MatchSyncRequest request) {
        // 幂等检查：先查后插，以 game_id 为唯一键判断该对局是否已同步
        Long gameId = request.getGameId();
        Long exists = matchMapper.selectCount(
                new QueryWrapper<Match>().eq("game_id", gameId));
        // 对局已存在：直接返回，不产生任何写入（调用方无需感知）
        if (exists != null && exists > 0) {
            log.info("Match already exists, skip sync: gameId={}", gameId);
            return;
        }

        // 组装 match 主表记录：字段与实体一一对应，teams 整体序列化为 teams_json
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
        // 主表插入后 id 自动回填（AUTO 主键），供参赛者关联 match_id
        try {
            matchMapper.insert(match);
        } catch (DuplicateKeyException e) {
            // 并发兜底：两个请求同时通过"先查后插"的幂等检查，后插入者撞 game_id 唯一键。
            // 异常已在方法内吞掉、不向事务边界传播，事务不会标记回滚，视为幂等成功直接返回
            log.info("Match concurrently inserted, skip sync: gameId={}", gameId);
            return;
        }

        // 逐条组装参赛者：直显字段缺失时写 0，stats 全量透传存入 stats_json
        List<MatchParticipant> savedParticipants = new ArrayList<>(request.getParticipants().size());
        for (ParticipantSyncRequest p : request.getParticipants()) {
            // 基础字段：玩家身份、英雄与队伍归属
            MatchParticipant participant = new MatchParticipant();
            participant.setMatchId(match.getId());
            participant.setPuuid(p.getPuuid());
            participant.setSummonerName(p.getSummonerName());
            participant.setChampionId(p.getChampionId());
            participant.setTeamId(p.getTeamId());
            participant.setPosition(p.getPosition());
            // 直显统计字段：kills/deaths/assists 等缺失时写 0，保证下游渲染不出现 null
            participant.setKills(p.getKills() == null ? 0 : p.getKills());
            participant.setDeaths(p.getDeaths() == null ? 0 : p.getDeaths());
            participant.setAssists(p.getAssists() == null ? 0 : p.getAssists());
            participant.setWin(p.getWin());
            participant.setGoldEarned(p.getGoldEarned() == null ? 0 : p.getGoldEarned());
            participant.setCs(p.getCs() == null ? 0 : p.getCs());
            // 出装/召唤师技能/stats 统一走 JSON 序列化，保证原始快照完整
            participant.setItems(writeJson(p.getItems()));
            participant.setSummonerSpells(writeJson(p.getSummonerSpells()));
            participant.setStatsJson(writeJson(p.getStats()));
            matchParticipantMapper.insert(participant);
            // 收集落库实体（id 已回填），供 MVP/SVP 评选使用
            savedParticipants.add(participant);
        }

        // 参与者全部落库后触发 MVP/SVP 评选：同事务内写 match_mvp
        matchMvpService.evaluateAndSave(match, savedParticipants);

        log.info("Match saved: gameId={}, participants={}", gameId, request.getParticipants().size());
    }

    /**
     * 对象转 JSON 字符串，用于 teams_json / items / summonerSpells / stats_json 等快照列；
     * 序列化失败仅记录日志并返回 null，不阻断保存流程
     */
    private String writeJson(Object value) {
        if (value == null) {
            // null 或空集合直接返回 null，对应快照列落库为 NULL
            return null;
        }
        try {
            // 通过 Jackson 序列化为 JSON 字符串
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // 序列化失败不抛出：快照列允许为 NULL，避免单点异常中断整局保存
            log.error("Failed to serialize value to JSON", e);
            return null;
        }
    }

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
        // stats 快照字段：解析 stats_json，缺失写 0/false
        JsonNode stats = parseStatsJson(self.getStatsJson());
        s.setTotalDamage(statInt(stats, "totalDamageDealtToChampions"));
        s.setTotalDamageTaken(statInt(stats, "totalDamageTaken"));
        s.setGoldEarned(statInt(stats, "goldEarned"));
        s.setCs(statInt(stats, "totalMinionsKilled"));
        s.setLargestMultiKill(statInt(stats, "largestMultiKill"));
        s.setTurretKills(statInt(stats, "turretKills"));
        s.setGameEndedInSurrender(statBool(stats, "gameEndedInSurrender"));
        // 折叠卡增强字段：出装/技能/海克斯/符文 + 多杀，全部从 stats_json 提取，缺失写空列表或 0
        s.setItems(statList(stats, "item0", "item1", "item2", "item3", "item4", "item5", "item6"));
        s.setSummonerSpells(statList(stats, "spell1Id", "spell2Id"));
        s.setAugments(statList(stats, "playerAugment1", "playerAugment2", "playerAugment3",
                "playerAugment4", "playerAugment5", "playerAugment6"));
        s.setPerks(buildPerks(stats));
        s.setDoubleKills(statInt(stats, "doubleKills"));
        s.setTripleKills(statInt(stats, "tripleKills"));
        s.setQuadraKills(statInt(stats, "quadraKills"));
        s.setPentaKills(statInt(stats, "pentaKills"));
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
            // stats 伤害字段从各人 stats_json 解析，缺失视为 0
            JsonNode stats = parseStatsJson(p.getStatsJson());
            damage += statInt(stats, "totalDamageDealtToChampions");
            damageTaken += statInt(stats, "totalDamageTaken");
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
            JsonNode stats = parseStatsJson(p.getStatsJson());
            pl.setItems(statList(stats, "item0", "item1", "item2", "item3", "item4", "item5", "item6"));
            pl.setSummonerSpells(statList(stats, "spell1Id", "spell2Id"));
            pl.setAugments(statList(stats, "playerAugment1", "playerAugment2", "playerAugment3",
                    "playerAugment4", "playerAugment5", "playerAugment6"));
            pl.setPerks(buildPerks(stats));
            // 折叠卡统计行/雷达图字段：LCU/SGP 字段名一致，缺失写 0
            pl.setTotalDamageDealtToChampions(statInt(stats, "totalDamageDealtToChampions"));
            pl.setTotalDamageTaken(statInt(stats, "totalDamageTaken"));
            pl.setTotalHeal(statInt(stats, "totalHeal"));
            pl.setVisionScore(statInt(stats, "visionScore"));
            pl.setGoldEarned(statInt(stats, "goldEarned"));
            // 补刀口径与详情接口一致（小兵 + 野怪），避免折叠卡补兵标签与展开态判定不一致
            pl.setCs(statInt(stats, "neutralMinionsKilled") + statInt(stats, "totalMinionsKilled"));
            pl.setTurretKills(statInt(stats, "turretKills"));
            pl.setWardsPlaced(statInt(stats, "wardsPlaced"));
            // 折叠卡成就标签字段：多杀/拆塔/护盾/控制读 stats 顶层，
            // 单杀/塔杀/补刀压制/击飞击杀读 SGP challenges（LCU 缺失按 0）
            pl.setTotalDamageToTowers(statInt(stats, "damageDealtToTurrets"));
            pl.setDoubleKills(statInt(stats, "doubleKills"));
            pl.setTripleKills(statInt(stats, "tripleKills"));
            pl.setQuadraKills(statInt(stats, "quadraKills"));
            pl.setPentaKills(statInt(stats, "pentaKills"));
            pl.setTotalDamageShieldedOnTeammates(statInt(stats, "totalDamageShieldedOnTeammates"));
            pl.setTimeCCingOthers(statInt(stats, "timeCCingOthers"));
            pl.setSoloKills(statChallengeInt(stats, "soloKills"));
            pl.setKillsNearEnemyTurret(statChallengeInt(stats, "killsNearEnemyTurret"));
            pl.setKillsUnderOwnTurret(statChallengeInt(stats, "killsUnderOwnTurret"));
            pl.setMaxCsAdvantageOnLaneOpponent(statChallengeInt(stats, "maxCsAdvantageOnLaneOpponent"));
            pl.setKnockEnemyIntoTeamAndKill(statChallengeInt(stats, "knockEnemyIntoTeamAndKill"));
            // 召唤师账号等级：顶部玩家信息展示（缺失按 0）
            pl.setSummonerLevel(statInt(stats, "summonerLevel"));
            // 召唤师头像 ID：顶部玩家头像展示（缺失按 0）
            pl.setProfileIcon(statInt(stats, "profileIcon"));
            return pl;
        }).toList();
    }

    /**
     * 组装参赛者符文配置，双路径探测（仅 perks 需要兼容两种来源）：
     * 1. SGP 嵌套：stats.perks 为对象，读取 perkIds/perkStyle/perkSubStyle；
     * 2. LCU 平铺：perk0-5 + perkPrimaryStyle + perkSubStyle 位于 stats 顶层。
     * 两条路径字段缺失时均为空列表/0，保证响应结构稳定
     */
    private MatchSummaryResponse.ParticipantPerks buildPerks(JsonNode stats) {
        MatchSummaryResponse.ParticipantPerks perks = new MatchSummaryResponse.ParticipantPerks();
        if (stats == null) {
            // stats 整体缺失兜底：与"缺失写空列表/0"契约一致（placeholderSelf 同款输出），
            // 空 perkIds + 样式 0，保证折叠卡渲染结构稳定而非输出 null
            perks.setPerkIds(List.of());
            perks.setPerkStyle(0);
            perks.setPerkSubStyle(0);
            return perks;
        }
        JsonNode nested = stats.get("perks");
        if (nested != null && nested.isObject()) {
            // SGP 嵌套路径：符文 ID 为数组字段，样式字段为对象内标量
            perks.setPerkIds(statIntArray(nested, "perkIds"));
            perks.setPerkStyle(statInt(nested, "perkStyle"));
            perks.setPerkSubStyle(statInt(nested, "perkSubStyle"));
        } else {
            // LCU 平铺路径：6 颗符文 + 主副系样式均位于 stats 顶层
            perks.setPerkIds(statList(stats, "perk0", "perk1", "perk2", "perk3", "perk4", "perk5"));
            perks.setPerkStyle(statInt(stats, "perkPrimaryStyle"));
            perks.setPerkSubStyle(statInt(stats, "perkSubStyle"));
        }
        return perks;
    }

    /**
     * 解析 stats_json 快照为 JsonNode，供统计字段读取；
     * 空串或解析失败返回 null，调用方按缺失字段写 0/false 处理
     */
    private JsonNode parseStatsJson(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(statsJson);
        } catch (Exception e) {
            // 快照损坏不阻断列表接口：仅记录日志并按缺失字段处理
            log.warn("Failed to parse statsJson, treat as empty: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 读取 stats 数值字段：字段缺失、为 null 或非数字时返回 0
     */
    private int statInt(JsonNode stats, String key) {
        if (stats == null || !stats.has(key) || stats.get(key).isNull()) {
            return 0;
        }
        return stats.get(key).asInt(0);
    }

    /**
     * 读取 stats.challenges 数值字段（SGP 独有挑战数据，LCU 缺失时为 0）：
     * challenges 对象或字段缺失、为 null 时返回 0，保证响应结构稳定
     */
    private int statChallengeInt(JsonNode stats, String key) {
        if (stats == null || !stats.has("challenges") || !stats.get("challenges").has(key)) {
            return 0;
        }
        JsonNode value = stats.get("challenges").get(key);
        if (value.isNull()) {
            return 0;
        }
        return value.asInt(0);
    }

    /**
     * 读取 stats 布尔字段：字段缺失、为 null 或非法时返回 false
     */
    private boolean statBool(JsonNode stats, String key) {
        if (stats == null || !stats.has(key) || stats.get(key).isNull()) {
            return false;
        }
        return stats.get(key).asBoolean(false);
    }

    /**
     * 按连续键名读取 stats 整数列表（如 item0-6、playerAugment1-6、perk0-5）：
     * 按传入键顺序取值，缺失/为 null 的键跳过，stats 缺失时返回空列表
     */
    private List<Integer> statList(JsonNode stats, String... keys) {
        List<Integer> values = new ArrayList<>();
        if (stats == null) {
            return values;
        }
        for (String key : keys) {
            if (stats.has(key) && !stats.get(key).isNull()) {
                values.add(stats.get(key).asInt(0));
            }
        }
        return values;
    }

    /**
     * 读取 stats 数组字段为整数列表（如 SGP 嵌套 perks.perkIds）：
     * 字段缺失或非数组时返回空列表
     */
    private List<Integer> statIntArray(JsonNode stats, String key) {
        List<Integer> values = new ArrayList<>();
        if (stats == null || !stats.has(key) || !stats.get(key).isArray()) {
            return values;
        }
        stats.get(key).forEach(node -> values.add(node.asInt(0)));
        return values;
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
     * 查询对局详情（含参赛者列表），不存在抛 MatchNotFoundException
     * <p>主表按 game_id 精确查询，命中后按主键关联参赛者明细；
     * 详情包含 teams_json 与各参赛者的 stats_json 全量快照。</p>
     */
    public MatchDetailResponse getMatchDetail(Long gameId) {
        // 按幂等键 game_id 查询主表记录
        Match match = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", gameId));
        if (match == null) {
            // 未命中：抛出领域异常，由全局异常处理器转为 404
            log.warn("Match not found, gameId={}", gameId);
            throw new MatchNotFoundException(gameId);
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
