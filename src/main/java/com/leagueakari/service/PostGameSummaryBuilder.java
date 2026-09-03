package com.leagueakari.service;

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

/**
 * 局后锐评输入摘要构建器：把一局对局聚合成 AI 可点评的紧凑 JSON。
 * <p>v2（锐评升级）：从"只有车队成员 KDA"升级为<b>双方 10 人全量可见</b>——
 * 每人含 召唤师名/英雄/KDA/伤害/承伤/金币/胜负/车队成员标记/称号（主队 MVP、尽力，对方 MVP），
 * 顶层含胜负与比分。对手可见后，AI 才能"以数据为准"评对位、服对面、嘴硬都有人物锚点。
 * 数据源：match_participant 行级字段 + stats_json（Riot v5 键名，与 BroadcastCoordinator 同口径）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostGameSummaryBuilder {

    private final GameDataService gameDataService;
    private final ObjectMapper objectMapper;
    private final TeamProperties teamProperties;

    /**
     * 组装锐评输入摘要
     *
     * @param match          对局主表（胜负/队列/时长）
     * @param participants   全部参赛者（含路人）
     * @param memberByPuuid  车队成员身份集合（puuid → 成员）
     * @param awards         MVP/ACE 评选结果（type 字段值 MVP/ACE）
     * @return 摘要 JSON 结构（Map，由调用方序列化后发给 AI）
     */
    public Map<String, Object> build(Match match, List<MatchParticipant> participants,
                                     Map<String, TeamRosterService.RosterMember> memberByPuuid,
                                     List<MatchMvp> awards) {
        int mainTeamId = mainTeamIdOf(match, participants, memberByPuuid);
        boolean win = match.getWinnerTeamId() != null && match.getWinnerTeamId() == mainTeamId;
        int otherTeamId = mainTeamId == 100 ? 200 : 100;

        // 称号索引：participantId → 原始称号（MVP；ACE 语义=败方最佳，仅主队标"尽力"）
        Map<Long, String> typeByParticipant = new HashMap<>();
        for (MatchMvp award : awards) {
            if (award.getParticipantId() != null && award.getType() != null) {
                typeByParticipant.put(award.getParticipantId(), award.getType());
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("result", win ? "胜利" : "败北");
        summary.put("score", teamKills(participants, mainTeamId) + ":"
                + teamKills(participants, otherTeamId));
        summary.put("meta", queueName(match.getQueueId()) + " · "
                + formatDuration(match.getGameDuration()));
        summary.put("teamName", teamProperties.getName());
        summary.put("mainTeam", buildRows(participants, mainTeamId, true, win,
                memberByPuuid, typeByParticipant));
        summary.put("otherTeam", buildRows(participants, otherTeamId, false, win,
                memberByPuuid, typeByParticipant));
        return summary;
    }

    /** 组装一队 5 行：车队成员置前、行内按击杀降序（与战报图阵容一致，便于对照） */
    private List<Map<String, Object>> buildRows(List<MatchParticipant> participants, int teamId,
                                                boolean isMain, boolean win,
                                                Map<String, TeamRosterService.RosterMember> memberByPuuid,
                                                Map<Long, String> typeByParticipant) {
        List<MatchParticipant> team = participants.stream()
                .filter(p -> p.getTeamId() != null && p.getTeamId() == teamId)
                .sorted(Comparator.comparingInt(
                        (MatchParticipant p) -> p.getKills() == null ? 0 : p.getKills()).reversed())
                .toList();
        List<MatchParticipant> ordered = new ArrayList<>();
        if (isMain) {
            ordered.addAll(team.stream().filter(p -> memberByPuuid.containsKey(p.getPuuid())).toList());
            ordered.addAll(team.stream().filter(p -> !memberByPuuid.containsKey(p.getPuuid())).toList());
        } else {
            ordered.addAll(team);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MatchParticipant p : ordered) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.getSummonerName());
            row.put("champion", safeChampionName(p.getChampionId()));
            row.put("win", isMain ? win : !win);
            row.put("member", isMain && memberByPuuid.containsKey(p.getPuuid()));
            row.put("kda", (p.getKills() == null ? 0 : p.getKills()) + "/"
                    + (p.getDeaths() == null ? 0 : p.getDeaths()) + "/"
                    + (p.getAssists() == null ? 0 : p.getAssists()));
            row.put("dmg", statInt(p.getStatsJson(), "totalDamageDealtToChampions"));
            row.put("taken", statInt(p.getStatsJson(), "totalDamageTaken"));
            row.put("gold", statInt(p.getStatsJson(), "goldEarned"));
            // 称号：MVP 双方都标；ACE 语义=败方最佳，只在主队(车队侧)标"尽力"
            String type = typeByParticipant.get(p.getId());
            if ("MVP".equals(type)) {
                row.put("title", "MVP");
            } else if ("ACE".equals(type) && isMain) {
                row.put("title", "尽力");
            }
            rows.add(row);
        }
        return rows;
    }

    /** 车队主队判定（与战报图同口径）：车队成员多数所在队伍；无成员取胜方 */
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

    /** 一队击杀合计（用于比分） */
    private int teamKills(List<MatchParticipant> participants, int teamId) {
        return participants.stream()
                .filter(p -> p.getTeamId() != null && p.getTeamId() == teamId)
                .mapToInt(p -> p.getKills() == null ? 0 : p.getKills())
                .sum();
    }

    /**
     * 读取 stats_json 数值字段：缺失/非数字返回 0
     * （与 BroadcastCoordinator.statInt 同口径；stats 解析暂未抽公共，后续可统一）
     */
    private int statInt(String statsJson, String key) {
        if (statsJson == null || statsJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode stats = objectMapper.readTree(statsJson);
            if (stats.has(key) && !stats.get(key).isNull()) {
                return stats.get(key).asInt(0);
            }
        } catch (Exception e) {
            log.warn("Parse statsJson failed: {}", e.getMessage());
        }
        return 0;
    }

    /** 英雄中文名：查询失败返回占位（不让摘要出现 null） */
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

    /** 常用队列中文名（缺失回退数字） */
    private String queueName(Integer queueId) {
        if (queueId == null) {
            return "对局";
        }
        return switch (queueId) {
            case 420 -> "单双排";
            case 430 -> "匹配";
            case 440 -> "灵活组排";
            case 450 -> "极地大乱斗";
            case 1700 -> "斗魂竞技场";
            case 2400, 2410, 2450 -> "海克斯乱斗";
            default -> "队列" + queueId;
        };
    }

    /** 对局时长：秒 → "28分42秒" */
    private String formatDuration(Integer durationSeconds) {
        if (durationSeconds == null) {
            return "--";
        }
        int minutes = durationSeconds / 60;
        int seconds = durationSeconds % 60;
        return minutes + "分" + String.format("%02d", seconds) + "秒";
    }
}
