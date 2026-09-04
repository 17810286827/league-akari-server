package com.leagueakari.broadcast;

import com.leagueakari.reportimage.ReportImageData;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 战报图投影：一局摘要（FleetGameSummary）→ 战报图渲染规格（ReportImageData）。
 * <p>纯投影——只做格式化与字段映射，不重新判定主队/重算比分/重排阵容
 * （历史 bug：组装散在协调器时漏填比分导致图上恒显 0 : 0，commit 36af3b9）。
 * 渲染规格（ReportImageData）定义归 reportimage 包，本类只负责填充。</p>
 */
@Component
public class ReportImageProjector {

    /** 三指标口径注释：输出/承伤占比为全 10 人口径，伤转 = 伤害 ÷ 经济 */
    public ReportImageData project(FleetGameSummary s) {
        ReportImageData d = new ReportImageData();
        d.teamName = s.getTeamName();
        d.win = s.isWin();
        d.resultLabel = s.isWin() ? "VICTORY · 胜利" : "DEFEAT · 败北";
        d.metaLine = queueName(s.getQueueId()) + " · " + formatDuration(s.getGameDurationSeconds())
                + " · " + formatGameTime(s.getGameCreationMs());

        // 比分直接取摘要（双方击杀合计），漏填会立刻在此处暴露为 0 而非静默
        d.mainScore = s.getMainScore();
        d.otherScore = s.getOtherScore();

        // 资源与一血：摘要已解析，-1/null 语义原样透传（渲染器不展示无数据格）
        d.mainTower = s.getMainTowerKills();
        d.otherTower = s.getOtherTowerKills();
        d.mainDragon = s.getMainDragonKills();
        d.otherDragon = s.getOtherDragonKills();
        d.mainBaron = s.getMainBaronKills();
        d.otherBaron = s.getOtherBaronKills();
        d.mainFirstBlood = s.getMainFirstBlood();

        // 双列阵容：摘要行 → 渲染行（排序与置前已在摘要定型，投影只转格式）
        d.mainTeam = toRenderRows(s.getMainTeam(), s.getTotalDamage(), s.getTotalDamageTaken());
        d.otherTeam = toRenderRows(s.getOtherTeam(), s.getTotalDamage(), s.getTotalDamageTaken());

        // 焦点卡：车队内 MVP → 尽力（ACE）→ 默认队内击杀最高（后两者 titleTag 为空则卡上无徽章）
        ReportImageData.Player hero = d.mainTeam.stream()
                .filter(p -> "MVP".equals(p.titleTag))
                .findFirst()
                .orElse(d.mainTeam.stream()
                        .filter(p -> "尽力".equals(p.titleTag)).findFirst().orElse(null));
        if (hero == null && !d.mainTeam.isEmpty()) {
            hero = d.mainTeam.get(0); // mainTeam 已按击杀降序（车队成员置前）
        }
        d.hero = hero;

        d.footerLeft = s.getTeamName();
        d.footerRight = "LEAGUE AKARI 对局战报";
        return d;
    }

    /** 摘要行 → 渲染行：三指标（占比/伤转）在此计算，称号与 opScore 透传 */
    private List<ReportImageData.Player> toRenderRows(List<FleetGameSummary.Row> rows,
                                                      double totalDamage, double totalTaken) {
        List<ReportImageData.Player> out = new ArrayList<>();
        for (FleetGameSummary.Row r : rows) {
            ReportImageData.Player row = new ReportImageData.Player();
            row.summonerName = r.getSummonerName();
            row.championName = r.getChampionName();
            row.championId = r.getChampionId();
            row.kills = r.getKills();
            row.deaths = r.getDeaths();
            row.assists = r.getAssists();
            // 输出/承伤占比：全 10 人口径（分母为 0 时占比记 0，防除零）
            row.damageShare = totalDamage > 0 ? r.getDamage() / totalDamage : 0;
            row.damageTakenShare = totalTaken > 0 ? r.getDamageTaken() / totalTaken : 0;
            // 伤害转化率：伤害 ÷ 经济（经济为 0 时记 0）
            row.damagePerGold = r.getGold() > 0 ? r.getDamage() / (double) r.getGold() : 0;
            row.titleTag = r.getTitle();
            row.opScore = r.getOpScore() == null ? -1 : r.getOpScore();
            out.add(row);
        }
        return out;
    }

    /** 对局创建时间 → "08-30 21:47"（北京时间，对齐群里看图的直觉） */
    private String formatGameTime(Long gameCreationMs) {
        if (gameCreationMs == null) {
            return "--";
        }
        LocalDateTime time = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(gameCreationMs),
                ZoneId.of("Asia/Shanghai"));
        return String.format("%02d-%02d %02d:%02d", time.getMonthValue(), time.getDayOfMonth(),
                time.getHour(), time.getMinute());
    }

    /** 常用队列中文名（缺失回退数字），供战报文本可读展示 */
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
