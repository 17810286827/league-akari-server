package com.leagueakari.broadcast;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局后锐评的 AI 投影：一局摘要（FleetGameSummary）→ AI 可点评的紧凑 JSON Map。
 * <p>v3（架构清理候选2）：从"自行组装摘要"改造为"消费一局摘要的纯投影"——
 * 主队判定/比分/排序/称号等口径由 FleetGameSummaryService 唯一承载，本类只做
 * 对象 → Map 的格式转换。省 token 的键名缩写（dmg/taken 等）属于 AI 序列化格式，
 * 留在本层不污染领域模型。JSON 键名契约与 v2 保持一致（提示词与消费方零改动）。</p>
 */
@Component
public class PostGameSummaryBuilder {

    /**
     * 投影为锐评输入摘要（Map，由调用方序列化后发给 AI）。
     * <p>顶层键：result（胜利/败北）、score（"32:19"）、meta（队列 · 时长）、
     * teamName、mainTeam/otherTeam（行数组）。行内键与省 token 缩写（dmg/taken/gold）
     * 为 v2 既有 AI 契约——提示词按这些键引用，改名需同步提示词。</p>
     *
     * @param summary 一局摘要（车队视角全量事实，口径唯一实现见 FleetGameSummaryService）
     */
    public Map<String, Object> build(FleetGameSummary summary) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", summary.isWin() ? "胜利" : "败北");
        out.put("score", summary.getMainScore() + ":" + summary.getOtherScore());
        out.put("meta", queueName(summary.getQueueId()) + " · "
                + formatDuration(summary.getGameDurationSeconds()));
        out.put("teamName", summary.getTeamName());
        out.put("mainTeam", projectRows(summary.getMainTeam()));
        out.put("otherTeam", projectRows(summary.getOtherTeam()));
        return out;
    }

    /** 摘要行 → AI JSON 行：kda 拼串、数值字段用省 token 缩写、称号语义透传 */
    private List<Map<String, Object>> projectRows(List<FleetGameSummary.Row> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FleetGameSummary.Row r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", r.getSummonerName());
            row.put("champion", r.getChampionName());
            row.put("win", r.isWin());
            row.put("member", r.isMember());
            row.put("kda", r.getKills() + "/" + r.getDeaths() + "/" + r.getAssists());
            row.put("dmg", r.getDamage());
            row.put("taken", r.getDamageTaken());
            row.put("gold", r.getGold());
            if (r.getTitle() != null) {
                row.put("title", r.getTitle());
            }
            out.add(row);
        }
        return out;
    }

    /** 常用队列中文名（缺失回退数字）：战报 meta 行与 AI 摘要共用文案，两边格式一致便于对照 */
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
