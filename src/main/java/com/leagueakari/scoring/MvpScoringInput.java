package com.leagueakari.scoring;

import lombok.Builder;
import lombok.Data;

/**
 * 评分引擎输入：一名参与者的评分所需的全部原始数据
 * <p>字段取自 match_participant 直显列与 stats_json（challenges 内字段以 raw 浮点计）。</p>
 */
@Data
@Builder
public class MvpScoringInput {

    /** match_participant.id，用于回填 MVP/SVP 记录 */
    private Long participantId;

    /** champion_id，用于查英雄职业 */
    private Integer championId;

    /** 队伍 ID（100 蓝方 / 200 红方） */
    private Integer teamId;

    /** 是否获胜：胜方选 MVP，负方选 SVP */
    private Boolean win;

    /**
     * 是否大乱斗系对局（按 queueId 推导，判定依据与理由见 MatchMvpService 的 ARAM_QUEUE_IDS 常量注释）。
     * <p>大乱斗修正入口：该模式下辅助的视野维度权重视为 0。</p>
     */
    private boolean aramMode;

    // ===== 评分维度原始值 =====

    /** 对英雄总伤害（totalDamageDealtToChampions） */
    private Double totalDamageDealtToChampions;

    /** 击杀（kills） */
    private Integer kills;

    /** 死亡（deaths） */
    private Integer deaths;

    /** 助攻（assists） */
    private Integer assists;

    /** 获得金币（goldEarned） */
    private Integer goldEarned;

    /** 承受总伤害（totalDamageTaken） */
    private Double totalDamageTaken;

    /** 视野得分（visionScore） */
    private Double visionScore;

    /** 治疗量（totalHeal） */
    private Double totalHeal;

    /** 对友军护盾量（totalDamageShieldedOnTeammates） */
    private Double totalDamageShieldedOnTeammates;

    /** 控制他人时长秒（timeCCingOthers） */
    private Double timeCCingOthers;

    /** 对塔伤害（damageDealtToTurrets） */
    private Double damageDealtToTurrets;

    /** 双杀次数（doubleKills） */
    private Integer doubleKills;

    /** 三杀次数（tripleKills） */
    private Integer tripleKills;

    /** 四杀次数（quadraKills） */
    private Integer quadraKills;

    /** 五杀次数（pentaKills） */
    private Integer pentaKills;

    /** 对局时长（秒），用于经济/补刀的每分钟换算 */
    private Integer gameDurationSeconds;
}