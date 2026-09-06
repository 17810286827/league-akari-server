package com.leagueakari.riot;

import com.leagueakari.common.exception.ErrorCode;

import com.leagueakari.common.exception.BizException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.dto.match.MatchSyncRequest;
import com.leagueakari.dto.match.ParticipantSyncRequest;
import com.leagueakari.dto.match.TeamSyncRequest;
import com.leagueakari.entity.Match;
import com.leagueakari.mapper.MatchMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import com.leagueakari.match.MatchIngestService;
import com.leagueakari.match.MatchTimelineService;
import com.leagueakari.team.TeamRosterService;

/**
 * Riot 对局历史回填服务（外部 I/O 接缝）：
 * 按 roster 成员逐人拉取 MATCH-V5 历史对局 → 转换为同步请求 → 复用 saveMatch 幂等入库
 * （入库时自动触发 MVP 评分与基线累计，与客户端同步路径完全一致）。
 * <p>时间线补拉（工单 #34）：对局入库后拉取 MATCH-V5 timeline 并解包 info.frames 存储
 * （与 LCU/SGP 裸帧数组格式统一，下游复盘引擎无感）；已入库但缺时间线的对局在幂等
 * 预检查命中时补拉（不重复拉详情，省配额）；时间线已存在的跳过（跨成员共享对局不重复拉）。
 * 时间线拉取失败只记 warn 不阻断回填（时间线是增强数据，缺了详情仍在）。</p>
 * <p>限流：所有 Riot 请求经 {@link RiotRateLimiter}（个人 Key 约 100 请求/2 分钟，留余量）。
 * <p>幂等：入库前先查 game_id 是否存在，已入库直接跳过详情拉取，省 Riot 配额；
 * 重复触发回填无副作用。</p>
 */
@Slf4j
@Service
public class RiotMatchHistoryService {

    /** Riot API Key */
    private final String apiKey;

    /** MATCH-V5 域名（台服走 sea 路由） */
    private final String matchDomain;

    /** 回填对局的 region 标记 */
    private final String matchRegion;

    /** 每页拉取的对局 ID 数（Riot 上限 100） */
    private final int pageSize;

    /** 单成员最大回填对局数（防失控；默认 200 可覆盖近 10 周历史） */
    private final int maxMatchesPerMember;

    private final ObjectMapper objectMapper;
    private final MatchIngestService matchIngestService;
    private final MatchMapper matchMapper;
    private final TeamRosterService rosterService;
    /** 时间线补拉出口（工单 #34）：存在性检查与帧存储 */
    private final MatchTimelineService matchTimelineService;
    /** Riot API 统一出口：token + 限流 + 状态码语义三合一（架构清理 T6） */
    private final RiotHttpClient riotHttpClient;
    private final Executor backfillExecutor;

    public RiotMatchHistoryService(
            @Value("${riot.api-key:}") String apiKey,
            @Value("${riot.match-domain:https://sea.api.riotgames.com}") String matchDomain,
            @Value("${riot.match-region:TW2}") String matchRegion,
            @Value("${riot.backfill-page-size:100}") int pageSize,
            @Value("${riot.backfill-max-matches:200}") int maxMatchesPerMember,
            ObjectMapper objectMapper,
            MatchIngestService matchIngestService,
            MatchMapper matchMapper,
            TeamRosterService rosterService,
            MatchTimelineService matchTimelineService,
            RiotHttpClient riotHttpClient,
            Executor backfillExecutor) {
        this.apiKey = apiKey;
        this.matchDomain = matchDomain;
        this.matchRegion = matchRegion;
        this.pageSize = pageSize;
        this.maxMatchesPerMember = maxMatchesPerMember;
        this.objectMapper = objectMapper;
        this.matchIngestService = matchIngestService;
        this.matchMapper = matchMapper;
        this.rosterService = rosterService;
        this.matchTimelineService = matchTimelineService;
        this.riotHttpClient = riotHttpClient;
        this.backfillExecutor = backfillExecutor;
    }

    /** 回填运行标记：防止并发触发（回填为单实例串行任务） */
    private volatile boolean running = false;

    /**
     * 触发全量回填（异步）：对 roster 全部成员逐人回填历史对局。
     * 正在运行时返回 false（不重复触发）；roster 未配置抛参数异常（400 语义）
     *
     * @return 是否成功启动
     * @throws BizException 车队名单未配置（1101）、Riot API Key 未配置（4001）
     */
    public boolean startBackfill() {
        requireApiKey();
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        synchronized (this) {
            if (running) {
                log.warn("Backfill already running, ignore trigger");
                return false;
            }
            running = true;
        }
        log.info("Backfill started: members={}", roster.size());
        // 回填在专用单线程执行器中异步执行；running 标记由执行体内复位。
        // 提交失败（执行器已关闭等）必须复位标记并上抛，否则后续回填永久无法触发
        try {
            backfillExecutor.execute(() -> {
                try {
                    runBackfillSync();
                } finally {
                    running = false;
                }
            });
        } catch (Exception e) {
            running = false;
            log.error("Backfill submit failed: {}", e.getMessage());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "回填任务提交失败：" + e.getMessage());
        }
        return true;
    }

    /**
     * 同步执行全量回填（roster 全部成员）：单成员失败记日志后继续，不阻断整体
     *
     * @return 成功入库（含首次跳过后的新增）的对局总数
     */
    public int runBackfillSync() {
        requireApiKey();
        List<TeamRosterService.RosterMember> roster = rosterService.requireMembers();
        int total = 0;
        for (TeamRosterService.RosterMember member : roster) {
            try {
                // 回填使用 Riot 全局 puuid（MATCH-V5 按它索引；腾讯 UUID 查不到）
                int synced = backfillMember(member.backfillPuuid());
                total += synced;
                log.info("Backfill member done: {}, synced={}", member.getRiotId(), synced);
            } catch (Exception e) {
                // 单成员失败（改名被回收/Riot 异常）：记录后继续下一个，不阻断整体
                log.error("Backfill member failed: {}, error={}", member.getRiotId(), e.getMessage());
            }
        }
        log.info("Backfill all members done: total={}", total);
        return total;
    }

    /**
     * 回填单个成员的历史对局：分页拉取 ID 列表 → 逐局（幂等预检查后）取详情入库
     *
     * @param puuid 成员 puuid
     * @return 新入库的对局数（已在库的跳过不计）
     */
    public int backfillMember(String puuid) {
        int synced = 0;
        for (int start = 0; start < maxMatchesPerMember; start += pageSize) {
            List<String> ids = fetchMatchIds(puuid, start);
            if (ids.isEmpty()) {
                break;
            }
            for (String matchId : ids) {
                Long gameId = numericGameId(matchId);
                // 幂等预检查：game_id 已在库则跳过详情拉取（MATCH-V5 的 matchId 含 gameId 尾缀，
                // 但不同区格式有差异，稳妥做法是以数字部分估算不可靠——直接跳过预检查场景仅保留
                // saveMatch 的最终幂等；这里预检查针对已精确入库的 gameId）
                if (gameId != null && existsByGameId(gameId)) {
                    log.info("Backfill skip (already stored): puuid={}, gameId={}", puuid, gameId);
                    // 时间线补拉（工单 #34）：对局在库但时间线缺失 → 只补拉 timeline（不重复拉详情）；
                    // 跨成员共享对局由时间线存在性检查去重（第二个成员经过时已存在，零调用）
                    fetchTimelineIfMissing(matchId, gameId);
                    continue;
                }
                MatchSyncRequest request = fetchAndConvert(matchId, puuid);
                matchIngestService.saveMatch(request);
                // 新入库对局顺带拉时间线：让回填的局立即支持复盘（时间线是增强数据，
                // 拉取失败不阻断回填——记 warn，详情已在库）
                if (request.getGameId() != null) {
                    fetchTimelineIfMissing(matchId, request.getGameId());
                }
                synced++;
            }
            if (ids.size() < pageSize) {
                break;
            }
        }
        return synced;
    }

    /**
     * 补拉对局时间线（工单 #34）：时间线已存在则跳过（幂等）；缺失时拉取
     * MATCH-V5 /match/v5/matches/{matchId}/timeline，解包 info.frames 后存储——
     * 与 LCU/SGP 推送的裸帧数组格式统一，下游（复盘引擎/名场面抽取）无感适配。
     * 拉取失败只记 warn 不上抛（时间线缺失可接受，不阻断回填主流程）
     */
    private void fetchTimelineIfMissing(String matchId, Long gameId) {
        try {
            // 幂等：时间线已存在直接跳过（跨成员共享对局的天然去重）
            if (matchTimelineService.getTimeline(gameId) != null) {
                return;
            }
            URI uri = new URIBuilder(matchDomain)
                    .setPathSegments("match", "v5", "matches", matchId, "timeline")
                    .build();
            String body = riotHttpClient.get(uri);
            // 帧格式适配：MATCH-V5 把帧数组包裹在 info 下，LCU/SGP 是裸数组——
            // 这里解包后以统一形态入库（存储层只认裸帧数组）
            JsonNode frames = objectMapper.readTree(body).path("info").path("frames");
            if (!frames.isArray() || frames.isEmpty()) {
                log.warn("Backfill timeline empty: matchId={}, gameId={}", matchId, gameId);
                return;
            }
            List<Map<String, Object>> framesList = objectMapper.convertValue(frames,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            matchTimelineService.saveTimeline(gameId, framesList);
            log.info("Backfill timeline stored: matchId={}, gameId={}, frames={}",
                    matchId, gameId, framesList.size());
        } catch (Exception e) {
            // 时间线是增强数据：拉取失败（部分区服/队列无 timeline 权限）不阻断回填
            log.warn("Backfill timeline failed (match kept without timeline): matchId={}, gameId={}, error={}",
                    matchId, gameId, e.getMessage());
        }
    }

    /** game_id 是否已入库（幂等预检查，避免重复消耗 Riot 配额） */
    private boolean existsByGameId(Long gameId) {
        Long count = matchMapper.selectCount(new QueryWrapper<Match>().eq("game_id", gameId));
        return count != null && count > 0;
    }

    /** 从 MATCH-V5 matchId（如 "TW2_123456"）提取数字 gameId；无法解析返回 null（不预检查） */
    private Long numericGameId(String matchId) {
        int underscore = matchId == null ? -1 : matchId.lastIndexOf('_');
        if (underscore < 0 || underscore == matchId.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(matchId.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 校验 API Key，未配置抛状态异常（503 语义） */
    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(ErrorCode.RIOT_API_KEY_MISSING, "Riot API Key 未配置，无法回填历史对局");
        }
    }

    /** 拉取一页对局 ID 列表（按 start 分页） */
    private List<String> fetchMatchIds(String puuid, int start) {
        try {
            URI uri = new URIBuilder(matchDomain)
                    .setPathSegments("match", "v5", "matches", "by-puuid", puuid, "ids")
                    .addParameter("start", String.valueOf(start))
                    .addParameter("count", String.valueOf(pageSize))
                    .build();
            String body = riotHttpClient.get(uri);
            return objectMapper.readValue(body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (BizException e) {
            // Riot 出口已翻译的业务异常：原样上抛
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch match ids: puuid={}, start={}, error={}", puuid, start, e.getMessage());
            throw new BizException(ErrorCode.RIOT_API_ERROR, "拉取对局列表失败：" + e.getMessage(), e);
        }
    }

    /** 拉取单场对局详情并转换为同步请求（selfPuuid 记为该成员，dataSource=riot-api） */
    private MatchSyncRequest fetchAndConvert(String matchId, String puuid) {
        try {
            URI uri = new URIBuilder(matchDomain)
                    .setPathSegments("match", "v5", "matches", matchId)
                    .build();
            String body = riotHttpClient.get(uri);
            JsonNode info = objectMapper.readTree(body).path("info");
            MatchSyncRequest request = new MatchSyncRequest();
            request.setTeams(new ArrayList<>());
            request.setParticipants(new ArrayList<>());
            request.setGameId(info.path("gameId").asLong());
            request.setGameCreation(info.path("gameCreation").asLong());
            request.setGameDuration(info.path("gameDuration").asInt());
            request.setGameMode(info.path("gameMode").asText(null));
            request.setGameType(info.path("gameType").asText(null));
            request.setQueueId(info.path("queueId").asInt());
            request.setMapId(info.path("mapId").asInt());
            request.setGameVersion(info.path("gameVersion").asText(null));
            request.setRegion(matchRegion);
            request.setDataSource("riot-api");
            request.setSelfPuuid(puuid);
            // 胜方队伍 ID：从 teams 的 win 标记推断
            for (JsonNode team : info.path("teams")) {
                if (team.path("win").asBoolean(false)) {
                    request.setWinnerTeamId(team.path("teamId").asInt());
                }
                request.getTeams().add(toTeamSync(team));
            }
            for (JsonNode participant : info.path("participants")) {
                request.getParticipants().add(toParticipantSync(participant));
            }
            return request;
        } catch (BizException e) {
            // Riot 出口已翻译的业务异常：原样上抛
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch/convert match: id={}, error={}", matchId, e.getMessage());
            throw new BizException(ErrorCode.RIOT_API_ERROR, "拉取对局详情失败：" + e.getMessage(), e);
        }
    }

    /** MATCH-V5 team → 队伍级统计（指标从 objectives 展开） */
    private TeamSyncRequest toTeamSync(JsonNode team) {
        TeamSyncRequest t = new TeamSyncRequest();
        t.setTeamId(team.path("teamId").asInt());
        t.setWin(team.path("win").asBoolean(false));
        JsonNode obj = team.path("objectives");
        t.setTowerKills(obj.path("tower").path("kills").asInt());
        t.setInhibitorKills(obj.path("inhibitor").path("kills").asInt());
        t.setBaronKills(obj.path("baron").path("kills").asInt());
        t.setDragonKills(obj.path("dragon").path("kills").asInt());
        t.setRiftHeraldKills(obj.path("riftHerald").path("kills").asInt());
        t.setFirstBlood(obj.path("champion").path("first").asBoolean(false));
        t.setFirstTower(obj.path("tower").path("first").asBoolean(false));
        return t;
    }

    /** MATCH-V5 participant → 参赛者同步请求（stats 全量透传） */
    private ParticipantSyncRequest toParticipantSync(JsonNode participant) {
        ParticipantSyncRequest p = new ParticipantSyncRequest();
        p.setPuuid(participant.path("puuid").asText(null));
        // 召唤师名：riotId 拼接（历史数据缺 riotId 时回退 summonerName）
        String gameName = participant.path("riotIdGameName").asText("");
        String tagLine = participant.path("riotIdTagline").asText("");
        String summonerName = gameName.isEmpty() && tagLine.isEmpty()
                ? participant.path("summonerName").asText("")
                : gameName + "#" + tagLine;
        p.setSummonerName(summonerName);
        p.setChampionId(participant.path("championId").asInt());
        p.setTeamId(participant.path("teamId").asInt());
        p.setPosition(participant.path("teamPosition").asText(null));
        p.setKills(participant.path("kills").asInt());
        p.setDeaths(participant.path("deaths").asInt());
        p.setAssists(participant.path("assists").asInt());
        p.setWin(participant.path("win").asBoolean(false));
        p.setGoldEarned(participant.path("goldEarned").asInt());
        p.setCs(participant.path("totalMinionsKilled").asInt()
                + participant.path("neutralMinionsKilled").asInt());
        // 出装：item0-6 中非 0 槽位（0 为空槽）
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            int item = participant.path("item" + i).asInt(0);
            if (item != 0) {
                items.add(item);
            }
        }
        p.setItems(items);
        p.setSummonerSpells(List.of(
                participant.path("summoner1Id").asInt(),
                participant.path("summoner2Id").asInt()));
        // stats 全量透传（原始字段名与 LCU/SGP 一致，评分引擎直接可用）
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = objectMapper.convertValue(participant, Map.class);
        p.setStats(stats);
        return p;
    }

}
