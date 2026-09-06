package com.leagueakari.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.leagueakari.config.ReplayProperties;
import com.leagueakari.dto.replay.ReplayResponse;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 转折点规则引擎（纯函数，工单 #35 / ADR 0008）：
 * 输入时间线 frames + 局内序号参与者信息 + 视角队伍，输出复盘数据
 * （经济差序列、击杀事件、关键转折点）。**确定性提取**——同一输入永远同一输出，
 * 可从 frames 复算验证；不依赖 AI（AI 叙述是独立工单 T5 #40）。
 * <p>转折点候选类型（type 常量）：</p>
 * <ul>
 *   <li>{@link #TYPE_FIRST_BLOOD} 一血：首个 CHAMPION_KILL</li>
 *   <li>{@link #TYPE_TEAM_WIPE} 团灭：{@code replay.wipe-window-ms} 时间窗内一支队伍
 *       全部成员各自阵亡一次（时间线帧粒度约 1 分钟，窗口默认 12s）</li>
 *   <li>{@link #TYPE_BARON} 大龙：ELITE_MONSTER_KILL(monsterType=BARON) 或 BARON_KILL</li>
 *   <li>{@link #TYPE_GOLD_DIFF_EXTREME} 经济差极值：全场绝对值最大的帧经济差
 *       （低于 {@code replay.extreme-min-gold-diff} 门槛不提取）</li>
 *   <li>{@link #TYPE_GOLD_LEAD_CHANGE} 经济反超时刻：帧序列经济差符号翻转的帧</li>
 * </ul>
 * <p>局内序号映射约定与 WeeklyReportService 一致：帧的 participantFrames 键与事件的
 * killerId/victimId 都是局内序号（1..N），"上报数组顺序 = 局内序号"。</p>
 */
@Service
@RequiredArgsConstructor
public class TurningPointEngine {

    /** 转折点类型：一血 */
    public static final String TYPE_FIRST_BLOOD = "FIRST_BLOOD";

    /** 转折点类型：团灭 */
    public static final String TYPE_TEAM_WIPE = "TEAM_WIPE";

    /** 转折点类型：大龙 */
    public static final String TYPE_BARON = "BARON";

    /** 转折点类型：经济差极值 */
    public static final String TYPE_GOLD_DIFF_EXTREME = "GOLD_DIFF_EXTREME";

    /** 转折点类型：经济反超时刻 */
    public static final String TYPE_GOLD_LEAD_CHANGE = "GOLD_LEAD_CHANGE";

    /** 规则参数（团灭时间窗、极值门槛），yml 可调 */
    private final ReplayProperties properties;

    /** 参与者信息（局内序号 → 身份，Lombok @Value 不可变对象） */
    @Value
    public static class ParticipantInfo {

        /** 召唤师名 */
        String name;

        /** 英雄中文名 */
        String championName;

        /** 英雄 ID（头像 spec #44：响应透传给前端渲染头像） */
        int championId;

        /** 队伍 ID（100/200） */
        int teamId;
    }

    /** 击杀时间轴条目（团灭窗口判定用）：阵亡时刻 + 被击杀者序号（Lombok @Value 不可变对象） */
    @Value
    private static class DeathRecord {

        /** 阵亡时刻（毫秒，对局内时间） */
        long timestampMs;

        /** 被击杀者局内序号 */
        int victimSlot;
    }

    /**
     * 计算复盘数据（纯函数：同输入同输出）
     *
     * @param frames            时间线 frames（JsonNode 数组，原样快照）
     * @param slotInfo          局内序号 → 参与者信息（调用方按上报顺序构造）
     * @param perspectiveTeamId 视角队伍 ID（"我方"）；null 时回退 100
     * @return 复盘响应（无帧时各集合为空，不抛异常）
     */
    public ReplayResponse compute(JsonNode frames, Map<Integer, ParticipantInfo> slotInfo,
            Integer perspectiveTeamId) {
        // 视角回退：解析不出 self 队伍时默认 100（红/蓝侧，曲线只是符号相对视角）
        int perspective = perspectiveTeamId != null ? perspectiveTeamId : 100;
        int enemy = perspective == 100 ? 200 : 100;

        List<ReplayResponse.GoldDiffPoint> series = new ArrayList<>();
        List<ReplayResponse.KillEvent> killEvents = new ArrayList<>();
        List<ReplayResponse.TurningPoint> points = new ArrayList<>();
        // 击杀时间轴（团灭窗口判定原料）
        List<DeathRecord> deaths = new ArrayList<>();
        boolean firstBloodSeen = false;

        if (frames != null && frames.isArray()) {
            for (JsonNode frame : frames) {
                long ts = frame.path("timestamp").asLong(0);
                // ---- 经济差：按队伍聚合 totalGold（我方 - 敌方）----
                double perspectiveGold = 0;
                double enemyGold = 0;
                JsonNode pfs = frame.path("participantFrames");
                if (pfs.isObject()) {
                    for (Map.Entry<String, JsonNode> entry : fieldsOf(pfs)) {
                        int slot = parseSlot(entry.getKey());
                        if (slot <= 0) {
                            continue;
                        }
                        ParticipantInfo info = slotInfo.get(slot);
                        if (info == null) {
                            continue;
                        }
                        double gold = entry.getValue().path("totalGold").asDouble(0);
                        if (info.getTeamId() == perspective) {
                            perspectiveGold += gold;
                        } else {
                            enemyGold += gold;
                        }
                    }
                }
                double goldDiff = perspectiveGold - enemyGold;
                series.add(ReplayResponse.GoldDiffPoint.builder()
                        .timestampMs(ts).goldDiff(goldDiff).build());

                // ---- 事件扫描：击杀收集 + 一血 + 团灭原料 + 大龙 ----
                for (JsonNode event : frame.path("events")) {
                    String type = event.path("type").asText("");
                    long eventTs = event.path("timestamp").asLong(ts);
                    switch (type) {
                        case "CHAMPION_KILL" -> {
                            int killerId = event.path("killerId").asInt(0);
                            int victimId = event.path("victimId").asInt(0);
                            ParticipantInfo killer = slotInfo.get(killerId);
                            ParticipantInfo victim = slotInfo.get(victimId);
                            killEvents.add(ReplayResponse.KillEvent.builder()
                                    .timestampMs(eventTs)
                                    .killerName(killer == null ? "" : killer.getName())
                                    .killerChampion(killer == null ? "" : killer.getChampionName())
                                    .killerChampionId(killer == null ? null : killer.getChampionId())
                                    .victimName(victim == null ? "" : victim.getName())
                                    .victimChampion(victim == null ? "" : victim.getChampionName())
                                    .victimChampionId(victim == null ? null : victim.getChampionId())
                                    .killerIsPerspective(killer != null && killer.getTeamId() == perspective)
                                    .build());
                            // 一血：首个英雄击杀
                            if (!firstBloodSeen && victim != null) {
                                firstBloodSeen = true;
                                points.add(buildPoint(TYPE_FIRST_BLOOD, eventTs, lastDiff(series),
                                        "一血",
                                        (killer != null && killer.getTeamId() == perspective ? "我方 " : "敌方 ")
                                                + (killer == null ? "未知" : killer.getChampionName())
                                                + " 击杀 " + (victim == null ? "未知" : victim.getName()),
                                        involvedOf(perspective, killer, victim)));
                            }
                            // 团灭原料：被击杀者序号进时间轴
                            if (victimId > 0) {
                                deaths.add(new DeathRecord(eventTs, victimId));
                            }
                        }
                        case "ELITE_MONSTER_KILL", "BARON_KILL" -> {
                            // 大龙：ELITE_MONSTER_KILL.monsterType=BARON 或 BARON_KILL 直写
                            boolean isBaron = "BARON_KILL".equals(type)
                                    || "BARON".equals(event.path("monsterType").asText(""));
                            if (isBaron) {
                                int killerId = event.path("killerId").asInt(0);
                                ParticipantInfo killer = slotInfo.get(killerId);
                                boolean byPerspective = killer != null && killer.getTeamId() == perspective;
                                points.add(buildPoint(TYPE_BARON, eventTs, lastDiff(series),
                                        "大龙",
                                        (byPerspective ? "我方 " : "敌方 ")
                                                + (killer == null ? "未知" : killer.getName())
                                                + " 收下大龙",
                                        involvedOf(perspective, killer)));
                            }
                        }
                        default -> {
                            // 其余事件类型（推塔/插眼/升级等）与转折点无关，跳过
                        }
                    }
                }
            }
        }

        // ---- 团灭判定（滑动时间窗）----
        points.addAll(detectTeamWipes(deaths, slotInfo, perspective, series));

        // ---- 经济差极值（绝对值最大的帧，低于门槛不提取）----
        ReplayResponse.GoldDiffPoint extreme = series.stream()
                .max(Comparator.comparingDouble(p -> Math.abs(p.getGoldDiff())))
                .orElse(null);
        if (extreme != null && Math.abs(extreme.getGoldDiff()) >= properties.getExtremeMinGoldDiff()) {
            String side = extreme.getGoldDiff() > 0 ? "领先" : "落后";
            points.add(buildPoint(TYPE_GOLD_DIFF_EXTREME, extreme.getTimestampMs(), extreme.getGoldDiff(),
                    "经济差极值",
                    "我方" + side + " " + Math.round(Math.abs(extreme.getGoldDiff())) + " 金币（全场最大差距）",
                    List.of()));
        }

        // ---- 经济反超：帧序列经济差符号翻转 ----
        for (int i = 1; i < series.size(); i++) {
            double prev = series.get(i - 1).getGoldDiff();
            double curr = series.get(i).getGoldDiff();
            if (prev == 0 || curr == 0 || prev > 0 == curr > 0) {
                continue;
            }
            boolean nowLeading = curr > 0;
            points.add(buildPoint(TYPE_GOLD_LEAD_CHANGE, series.get(i).getTimestampMs(), curr,
                    "经济反超",
                    nowLeading ? "我方反超，领先 " + Math.round(curr) + " 金币"
                            : "我方被反超，落后 " + Math.abs(Math.round(curr)) + " 金币",
                    List.of()));
        }

        // 全部转折点按时间稳定排序（同刻保持提取顺序）
        points.sort(Comparator.comparingLong(ReplayResponse.TurningPoint::getTimestampMs));

        return ReplayResponse.builder()
                .available(true)
                .perspectiveTeamId(perspective)
                .goldDiffSeries(series)
                .killEvents(killEvents)
                .turningPoints(points)
                .build();
    }

    /**
     * 团灭判定：滑动时间窗扫描阵亡时间轴——窗口（{@code replay.wipe-window-ms}）内
     * 一支队伍的<b>全部成员</b>各自阵亡 ≥1 次即判定团灭；判定后重置窗口状态
     * （同一波不重复判定，下一波从空清单重新累计）。
     * <p>出窗规则：逐条淘汰"时刻早于当前阵亡 − 窗口"的记录（只移除过期者，
     * 不整清累计——整清会把仍在窗内的早期阵亡误逐出，导致跨帧团灭漏判）。
     * 时间线帧粒度约 1 分钟，团灭常挤在同一帧内（时间戳可能相同），窗口默认 12s
     * 同时覆盖同刻与跨帧两种形态</p>
     */
    private List<ReplayResponse.TurningPoint> detectTeamWipes(List<DeathRecord> deaths,
            Map<Integer, ParticipantInfo> slotInfo, int perspective,
            List<ReplayResponse.GoldDiffPoint> series) {
        List<ReplayResponse.TurningPoint> wipes = new ArrayList<>();
        if (deaths.isEmpty()) {
            return wipes;
        }
        // 各队伍的全部成员序号（判定"全员"的基准）
        Map<Integer, Set<Integer>> membersByTeam = new HashMap<>();
        slotInfo.forEach((slot, info) -> membersByTeam
                .computeIfAbsent(info.getTeamId(), k -> new HashSet<>()).add(slot));
        // 窗口内阵亡队列（时间升序，出窗从队首淘汰）与各队已阵亡成员累计
        ArrayDeque<DeathRecord> window = new ArrayDeque<>();
        Map<Integer, Set<Integer>> deadByTeam = new HashMap<>();
        long windowMs = properties.getWipeWindowMs();

        for (DeathRecord death : deaths) {
            // 逐条淘汰过期阵亡：只移除出窗者（修复点：不能整清，否则跨帧团灭漏判）
            while (!window.isEmpty() && death.getTimestampMs() - window.peekFirst().getTimestampMs() > windowMs) {
                DeathRecord expired = window.pollFirst();
                ParticipantInfo expiredVictim = slotInfo.get(expired.getVictimSlot());
                if (expiredVictim != null) {
                    Set<Integer> dead = deadByTeam.get(expiredVictim.getTeamId());
                    if (dead != null) {
                        dead.remove(expired.getVictimSlot());
                    }
                }
            }
            window.addLast(death);
            ParticipantInfo victim = slotInfo.get(death.getVictimSlot());
            if (victim == null) {
                continue;
            }
            Set<Integer> dead = deadByTeam.computeIfAbsent(victim.getTeamId(), k -> new HashSet<>());
            dead.add(death.getVictimSlot());
            // 该队全员在本窗口内阵亡 → 团灭
            Set<Integer> members = membersByTeam.get(victim.getTeamId());
            if (members != null && dead.containsAll(members)) {
                boolean perspectiveWiped = victim.getTeamId() == perspective;
                String side = perspectiveWiped ? "我方" : "敌方";
                // 涉及成员：被团灭的全员
                List<ReplayResponse.InvolvedPlayer> wiped = members.stream()
                        .map(slotInfo::get)
                        .filter(java.util.Objects::nonNull)
                        .map(p -> ReplayResponse.InvolvedPlayer.builder()
                                .name(p.getName()).championName(p.getChampionName())
                                .championId(p.getChampionId())
                                .perspective(p.getTeamId() == perspective)
                                .build())
                        .toList();
                // 经济差：找 ≤ 该时刻的最近帧
                double diff = diffAtOrBefore(series, death.getTimestampMs());
                wipes.add(buildPoint(TYPE_TEAM_WIPE, death.getTimestampMs(), diff,
                        "团灭",
                        side + "被团灭（" + String.join("、", wiped.stream()
                                .map(ReplayResponse.InvolvedPlayer::getName).toList()) + "）",
                        wiped));
                // 重置窗口状态（同波不重复判定；下一波重新累计）
                window.clear();
                deadByTeam.clear();
            }
        }
        return wipes;
    }

    /** 序列中 ≤ 指定时刻的最近一帧经济差（无更早帧返回 0） */
    private double diffAtOrBefore(List<ReplayResponse.GoldDiffPoint> series, long ts) {
        double diff = 0;
        for (ReplayResponse.GoldDiffPoint p : series) {
            if (p.getTimestampMs() <= ts) {
                diff = p.getGoldDiff();
            } else {
                break;
            }
        }
        return diff;
    }

    /** 构造转折点 */
    private ReplayResponse.TurningPoint buildPoint(String type, long ts, double goldDiff,
            String title, String detail, List<ReplayResponse.InvolvedPlayer> involved) {
        return ReplayResponse.TurningPoint.builder()
                .type(type).timestampMs(ts).goldDiff(goldDiff)
                .title(title).detail(detail).involved(involved)
                .build();
    }

    /** 涉及成员列表（过滤 null，标记是否我方） */
    private List<ReplayResponse.InvolvedPlayer> involvedOf(int perspective, ParticipantInfo... players) {
        List<ReplayResponse.InvolvedPlayer> out = new ArrayList<>();
        for (ParticipantInfo p : players) {
            if (p != null) {
                out.add(ReplayResponse.InvolvedPlayer.builder()
                        .name(p.getName()).championName(p.getChampionName())
                        .championId(p.getChampionId())
                        .perspective(p.getTeamId() == perspective)
                        .build());
            }
        }
        return out;
    }

    /** 序列最后一个经济差（事件时刻无帧快照时近似取最近帧） */
    private double lastDiff(List<ReplayResponse.GoldDiffPoint> series) {
        return series.isEmpty() ? 0 : series.get(series.size() - 1).getGoldDiff();
    }

    /** 帧键（"1".."10"）解析为局内序号；非数字返回 -1 */
    private int parseSlot(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** JsonNode 对象字段转可迭代集合 */
    private Iterable<Map.Entry<String, JsonNode>> fieldsOf(JsonNode object) {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        object.fields().forEachRemaining(out::add);
        return out;
    }
}
