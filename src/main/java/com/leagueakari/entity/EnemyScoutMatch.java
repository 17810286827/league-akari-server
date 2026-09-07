package com.leagueakari.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敌方侦察摘要缓存实体（对应 enemy_scout_match 表）：
 * 桌面端选人阶段喂入的敌方对局摘要行，开黑判定与窗口胜率的原料。
 * 幂等键 (match_id, puuid)：同一对局同一玩家仅一行，重复推送不重复写。
 */
@Data
@TableName("enemy_scout_match")
public class EnemyScoutMatch {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对局标识（SGP 对局 ID，字符串存储避免精度丢失） */
    private String matchId;

    /** 玩家唯一标识（腾讯侧 puuid，与 riot_account.puuid 同语义） */
    private String puuid;

    /** 召唤师名快照（SGP 摘要直读，展示用，允许为空） */
    private String summonerName;

    /** 该玩家所在队伍：100=蓝色方/200=红色方 */
    private Integer teamId;

    /** 该玩家本局是否获胜（0=负/1=胜） */
    private Boolean win;

    /** 本局队列 ID（SGP 局级字段，窗口统计备用） */
    private Integer queueId;

    /** 对局开始时刻（摘要 gameStartTimestamp 转库内时区） */
    private LocalDateTime gameStartAt;

    /** 首次入库时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
