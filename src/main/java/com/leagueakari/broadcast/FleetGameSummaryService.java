package com.leagueakari.broadcast;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.leagueakari.gamedata.GameDataService;
import com.leagueakari.common.stats.ParticipantStatsReader;
import com.leagueakari.team.TeamRosterService;

/**
 * 一局摘要组装器：把一局对局（主表 + 参赛者 + 成员身份集合 + 评选记录）
 * 聚合为车队视角的强类型 {@link FleetGameSummary}。
 * <p>全项目的"车队视角一局摘要"口径唯一实现：主队判定（车队成员多数所在队）、
 * 比分 = 双方击杀合计、车队成员置前 + 击杀降序、称号语义（主队 MVP/尽力、对方 MVP）。
 * 战报图（ReportImageProjector）与局后锐评（PostGameSummaryBuilder）消费本摘要做纯投影，
 * 不再各自重新判定——曾因两套实现口径漂移导致战报图比分恒显 0:0（commit 36af3b9）。</p>
 * <p>stats_json 解析（缺失补 0）为私有口径；全局读取门面见架构清理 spec 的统计门面票据。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FleetGameSummaryService {

    private final GameDataService gameDataService;
    private final ObjectMapper objectMapper;
    private final TeamProperties teamProperties;
    /** stats_json 读取门面：缺失补 0 口径的唯一实现（架构清理 T4） */
    private final ParticipantStatsReader statsReader;

    /**
     * 组装一局摘要
     *
     * @param match         对局主表（胜负/队列/时长/资源快照）
     * @param participants  全部参赛者（含路人）
     * @param memberByPuuid 车队成员身份集合（puuid → 成员，跨两种 puuid 体系）
     * @param awards        MVP/ACE 评选记录（type 字段值 MVP/ACE）
     */
    public FleetGameSummary build(Match match, List<MatchParticipant> participants,
                                  Map<String, TeamRosterService.RosterMember> memberByPuuid,
                                  List<MatchMvp> awards) {
        // 主队判定：车队成员多数所在队；无成员取胜方（口径与战报图/锐评历史实现一致）
        int mainTeamId = mainTeamIdOf(match, participants, memberByPuuid);
        boolean win = match.getWinnerTeamId() != null && match.getWinnerTeamId() == mainTeamId;
        int otherTeamId = mainTeamId == 100 ? 200 : 100;

        // 称号索引：participantId → 评选记录（MVP 双方都标；ACE=败方最佳，仅主队标"尽力"）
        Map<Long, MatchMvp> awardByParticipant = new HashMap<>();
        for (MatchMvp award : awards) {
            if (award.getParticipantId() != null && award.getType() != null) {
                awardByParticipant.put(award.getParticipantId(), award);
            }
        }

        // 全 10 人伤害/承伤合计（战报图占比分母，跨双方口径）
        double totalDamage = 0;
        double totalTaken = 0;
        for (MatchParticipant p : participants) {
            totalDamage += statsReader.intVal(p.getStatsJson(), "totalDamageDealtToChampions");
            totalTaken += statsReader.intVal(p.getStatsJson(), "totalDamageTaken");
        }

        // 双列组装：主队（车队成员置前）与对方，行内按击杀降序
        List<FleetGameSummary.Row> mainRows = buildRows(participants, mainTeamId, true,
                win, memberByPuuid, awardByParticipant);
        List<FleetGameSummary.Row> otherRows = buildRows(participants, otherTeamId, false,
                !win, memberByPuuid, awardByParticipant);

        FleetGameSummary summary = FleetGameSummary.builder()
                .teamName(teamProperties.getName())
                .win(win)
                .mainTeamId(mainTeamId)
                .mainScore(teamKills(participants, mainTeamId))
                .otherScore(teamKills(participants, otherTeamId))
                .queueId(match.getQueueId())
                .gameDurationSeconds(match.getGameDuration())
                .gameCreationMs(match.getGameCreation())
                .mainTeam(mainRows)
                .otherTeam(otherRows)
                .totalDamage(totalDamage)
                .totalDamageTaken(totalTaken)
                .build();

        // 资源快照：从 teams_json 解析双方塔/龙/大龙/一血（缺失保持无数据语义）
        fillResources(summary, match.getTeamsJson());

        log.debug("一局摘要组装完成：mainTeamId={}, score={}:{}, rows={}",
                mainTeamId, summary.getMainScore(), summary.getOtherScore(), mainRows.size());
        return summary;
    }

    /** 车队主队判定：车队成员多数所在队伍；无成员数据回退胜方 */
    private int mainTeamIdOf(Match match, List<MatchParticipant> participants,
                             Map<String, TeamRosterService.RosterMember> memberByPuuid) {
        Map<Integer, Long> teamCount = new LinkedHashMap<>();
        for (MatchParticipant p : participants) {
            if (memberByPuuid.containsKey(p.getPuuid()) && p.getTeamId() != null) {
                teamCount.merge(p.getTeamId(), 1L, Long::sum);
            }
        }
        return teamCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(match.getWinnerTeamId() == null ? 100 : match.getWinnerTeamId());
    }

    /** 组装一队 5 行：行内按击杀降序；主队车队成员置前（保持相对击杀序） */
    private List<FleetGameSummary.Row> buildRows(List<MatchParticipant> participants, int teamId,
                                                 boolean isMain, boolean rowWin,
                                                 Map<String, TeamRosterService.RosterMember> memberByPuuid,
                                                 Map<Long, MatchMvp> awardByParticipant) {
        List<MatchParticipant> team = participants.stream()
                .filter(p -> p.getTeamId() != null && p.getTeamId() == teamId)
                .sorted(Comparator.comparingInt(
                        (MatchParticipant p) -> p.getKills() == null ? 0 : p.getKills()).reversed())
                .toList();
        // 主队：车队成员排前面（同队内仍按击杀降序），其后路人
        List<MatchParticipant> ordered = new ArrayList<>();
        if (isMain) {
            ordered.addAll(team.stream()
                    .filter(p -> memberByPuuid.containsKey(p.getPuuid())).toList());
            ordered.addAll(team.stream()
                    .filter(p -> !memberByPuuid.containsKey(p.getPuuid())).toList());
        } else {
            ordered.addAll(team);
        }

        List<FleetGameSummary.Row> rows = new ArrayList<>();
        for (MatchParticipant p : ordered) {
            MatchMvp award = awardByParticipant.get(p.getId());
            rows.add(FleetGameSummary.Row.builder()
                    .participantId(p.getId())
                    .summonerName(p.getSummonerName())
                    .championId(p.getChampionId() == null ? 0 : p.getChampionId())
                    .championName(safeChampionName(p.getChampionId()))
                    .kills(p.getKills() == null ? 0 : p.getKills())
                    .deaths(p.getDeaths() == null ? 0 : p.getDeaths())
                    .assists(p.getAssists() == null ? 0 : p.getAssists())
                    .damage(statsReader.intVal(p.getStatsJson(), "totalDamageDealtToChampions"))
                    .damageTaken(statsReader.intVal(p.getStatsJson(), "totalDamageTaken"))
                    .gold(statsReader.intVal(p.getStatsJson(), "goldEarned"))
                    .member(isMain && memberByPuuid.containsKey(p.getPuuid()))
                    .win(rowWin)
                    .title(titleOf(award, isMain))
                    .opScore(award == null || award.getOpScore() == null
                            ? null : award.getOpScore().doubleValue())
                    .build());
        }
        return rows;
    }

    /** 称号语义：MVP 双方都标；ACE（败方最佳）仅主队（车队侧）标"尽力" */
    private String titleOf(MatchMvp award, boolean isMain) {
        if (award == null) {
            return null;
        }
        if ("MVP".equals(award.getType())) {
            return "MVP";
        }
        if ("ACE".equals(award.getType()) && isMain) {
            return "尽力";
        }
        return null;
    }

    /** 一队击杀合计（比分口径，主队 : 对方） */
    private int teamKills(List<MatchParticipant> participants, int teamId) {
        return participants.stream()
                .filter(p -> p.getTeamId() != null && p.getTeamId() == teamId)
                .mapToInt(p -> p.getKills() == null ? 0 : p.getKills())
                .sum();
    }

    /** 资源与一血：解析 teams_json（[{teamId, towerKills, dragonKills, baronKills, firstBlood}]） */
    private void fillResources(FleetGameSummary s, String teamsJson) {
        // 无快照/解析失败时资源槽位统一为 -1（渲染规格的"无数据不展示"语义在组装层锁定）
        s.setMainTowerKills(-1);
        s.setOtherTowerKills(-1);
        s.setMainDragonKills(-1);
        s.setOtherDragonKills(-1);
        s.setMainBaronKills(-1);
        s.setOtherBaronKills(-1);
        if (teamsJson == null || teamsJson.isBlank()) {
            return;
        }
        try {
            JsonNode teams = objectMapper.readTree(teamsJson);
            if (!teams.isArray()) {
                return;
            }
            for (JsonNode t : teams) {
                boolean isMain = t.path("teamId").asInt(-1) == s.getMainTeamId();
                // 按队伍归属写入对应槽位（主队/对方各一份数据）
                if (isMain) {
                    s.setMainTowerKills(t.path("towerKills").asInt(-1));
                    s.setMainDragonKills(t.path("dragonKills").asInt(-1));
                    s.setMainBaronKills(t.path("baronKills").asInt(-1));
                    if (t.hasNonNull("firstBlood")) {
                        s.setMainFirstBlood(t.path("firstBlood").asBoolean());
                    }
                } else {
                    s.setOtherTowerKills(t.path("towerKills").asInt(-1));
                    s.setOtherDragonKills(t.path("dragonKills").asInt(-1));
                    s.setOtherBaronKills(t.path("baronKills").asInt(-1));
                    // 对方一血 = !主队一血，摘要只保留主队槽位（渲染规格无对方独立字段）
                }
            }
        } catch (Exception e) {
            log.warn("Parse teamsJson failed, resources hidden: {}", e.getMessage());
        }
    }

    /** 英雄中文名：数据缺失/查询失败返回占位，不让摘要出现 null */
    private String safeChampionName(Integer championId) {
        if (championId == null) {
            return "?";
        }
        try {
            String name = gameDataService.championName(championId);
            return name == null ? "英雄" + championId : name;
        } catch (Exception e) {
            log.warn("Champion name lookup failed: championId={}", championId, e);
            return "英雄" + championId;
        }
    }
}
