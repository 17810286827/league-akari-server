package com.leagueakari.service;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一局摘要（Fleet Game Summary）：车队视角的单局全量事实中间表示。
 * <p>主队判定（车队成员多数所在队）、比分（双方击杀合计）、车队成员置前 + 击杀降序、
 * 称号语义（主队 MVP/尽力，对方 MVP）等口径只存在这一处——战报图与局后锐评
 * 是它的两个投影，投影层不得重新判定/重算/重排（曾导致战报图比分恒显 0:0 的口径漂移）。</p>
 * <p>强类型而非 Map：字段缺失在编译期暴露，杜绝"漏填静默失败"；
 * AI JSON 的省 token 键名缩写（dmg/taken 等）留在 AI 投影层。</p>
 */
@Data
@Builder
public class FleetGameSummary {

    /** 车队名（team.name 配置） */
    private String teamName;

    /** 车队视角是否胜利（主队 = 车队成员多数所在队） */
    private boolean win;

    /** 主队队伍 ID（100 蓝方 / 200 红方；无成员数据时回退胜方） */
    private int mainTeamId;

    /** 比分 = 双方击杀合计（主队 : 对方） */
    private int mainScore;

    private int otherScore;

    /** 队列 ID（原始值，展示格式化留给投影层） */
    private Integer queueId;

    /** 对局时长（秒，原始值） */
    private Integer gameDurationSeconds;

    /** 对局创建时刻（epoch 毫秒，投影层转北京时间展示） */
    private Long gameCreationMs;

    /** 主队（车队侧）5 行：车队成员置前 + 行内按击杀降序 */
    private List<Row> mainTeam;

    /** 对方 5 行：行内按击杀降序 */
    private List<Row> otherTeam;

    // ===== 资源快照（来自 teams_json，-1/null 表示无数据不展示） =====

    /** 主队推塔数；-1 = 无数据 */
    private int mainTowerKills;

    private int otherTowerKills;

    /** 主队小龙数；-1 = 无数据 */
    private int mainDragonKills;

    private int otherDragonKills;

    /** 主队大龙数；-1 = 无数据 */
    private int mainBaronKills;

    private int otherBaronKills;

    /** 主队是否拿一血；null = 无数据 */
    private Boolean mainFirstBlood;

    private Boolean otherFirstBlood;

    // ===== 全局合计（战报图三指标的分母） =====

    /** 全 10 人伤害合计（输出占比分母） */
    private double totalDamage;

    /** 全 10 人承伤合计（承伤占比分母） */
    private double totalDamageTaken;

    /** 参赛者行：一名玩家的车队视角事实 */
    @Data
    @Builder
    public static class Row {
        /** 参赛者 ID（称号/评分关联键） */
        private Long participantId;
        /** 召唤师名 */
        private String summonerName;
        /** 英雄 ID（头像取色留给战报图投影） */
        private Integer championId;
        /** 英雄中文名（组装时经 GameDataService 解析；查询失败为"英雄{id}"占位） */
        private String championName;
        /** 击杀（行内排序键） */
        private int kills;
        private int deaths;
        private int assists;
        /** 对英雄总伤害（stats_json，缺失补 0） */
        private int damage;
        /** 承受总伤害（stats_json，缺失补 0） */
        private int damageTaken;
        /** 获得金币（stats_json，缺失补 0） */
        private int gold;
        /** 是否车队成员（主队置前依据） */
        private boolean member;
        /** 是否所在队获胜（主队行 = summary.win；对方行 = !summary.win） */
        private boolean win;
        /**
         * 称号：MVP（胜方最佳，双方都标）/ 尽力（ACE=败方最佳，仅主队标）。
         * null = 无称号
         */
        private String title;
        /** OP Score（称号行展示；无评选记录为 null） */
        private Double opScore;
    }
}
