package com.leagueakari.broadcast;

import com.leagueakari.gamedata.GameDataService;
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
 * <p>v4（AI 加厚 spec #43）：补全一局摘要里现成但被丢弃的字段——资源对比
 * （塔/龙/大龙/一血）、各人 opScore、占比分母（全队伤害/承伤合计）；
 * 队列名转换改经 GameDataService 统一出口（原硬编码 switch 收编）。</p>
 */
@Component
public class PostGameSummaryBuilder {

    /** 队列名转换统一出口（spec #43 收编：新增队列只改 GameDataService 一处） */
    private final GameDataService gameDataService;

    public PostGameSummaryBuilder(GameDataService gameDataService) {
        this.gameDataService = gameDataService;
    }

    /**
     * 投影为锐评输入摘要（Map，由调用方序列化后发给 AI）。
     * <p>顶层键：result（胜利/败北）、score（"32:19"）、meta（队列 · 时长）、
     * teamName、resources（资源对比文案，无数据不出现）、totalDmg/totalTaken
     * （占比分母）、mainTeam/otherTeam（行数组）。行内键与省 token 缩写
     * （dmg/taken/gold）为 v2 既有 AI 契约——提示词按这些键引用，改名需同步提示词。</p>
     *
     * @param summary 一局摘要（车队视角全量事实，口径唯一实现见 FleetGameSummaryService）
     */
    public Map<String, Object> build(FleetGameSummary summary) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", summary.isWin() ? "胜利" : "败北");
        out.put("score", summary.getMainScore() + ":" + summary.getOtherScore());
        out.put("meta", gameDataService.queueName(summary.getQueueId()) + " · "
                + formatDuration(summary.getGameDurationSeconds()));
        out.put("teamName", summary.getTeamName());
        // 资源对比（spec #43）：塔/龙/大龙/一血——一血只有主队槽位（对方一血 = !主队一血）
        String resources = resourceLine(summary);
        if (resources != null) {
            out.put("resources", resources);
        }
        // 占比分母（spec #43）：全 10 人伤害/承伤合计——行内 dmg ÷ totalDmg 即伤害占比
        out.put("totalDmg", (int) summary.getTotalDamage());
        out.put("totalTaken", (int) summary.getTotalDamageTaken());
        out.put("mainTeam", projectRows(summary.getMainTeam()));
        out.put("otherTeam", projectRows(summary.getOtherTeam()));
        return out;
    }

    /** 摘要行 → AI JSON 行：kda 拼串、数值字段用省 token 缩写、称号/opScore 语义透传 */
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
            // opScore（spec #43）：评分与锐评不再脱节；无评选记录不携带键（缺失跳过）
            if (r.getOpScore() != null) {
                row.put("opScore", r.getOpScore());
            }
            if (r.getTitle() != null) {
                row.put("title", r.getTitle());
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 资源对比文案（"塔 7:3 · 小龙 3:1 · 大龙 1:0 · 一血我方/对方"）：
     * 任意槽位无数据（-1/null）时整体不投影——部分缺失拼出的残句比没有更误导
     *
     * @return 可读文案；资源数据不完整返回 null（投影层省略该键）
     */
    private String resourceLine(FleetGameSummary s) {
        if (s.getMainTowerKills() < 0 || s.getOtherTowerKills() < 0
                || s.getMainDragonKills() < 0 || s.getOtherDragonKills() < 0
                || s.getMainBaronKills() < 0 || s.getOtherBaronKills() < 0
                || s.getMainFirstBlood() == null) {
            return null;
        }
        String fb = s.getMainFirstBlood() ? "一血我方" : "一血对方";
        return "塔 " + s.getMainTowerKills() + ":" + s.getOtherTowerKills()
                + " · 小龙 " + s.getMainDragonKills() + ":" + s.getOtherDragonKills()
                + " · 大龙 " + s.getMainBaronKills() + ":" + s.getOtherBaronKills()
                + " · " + fb;
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
