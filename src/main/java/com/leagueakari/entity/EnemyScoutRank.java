package com.leagueakari.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敌方侦察段位快照实体（对应 enemy_scout_rank 表）：
 * 桌面端选人阶段喂入的敌方玩家段位，情报卡"段位"展示原料。
 * 按 puuid 幂等（一人一行，重复喂入覆盖更新段位）。
 */
@Data
@TableName("enemy_scout_rank")
public class EnemyScoutRank {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 玩家唯一标识（腾讯侧 puuid） */
    private String puuid;

    /** 召唤师名快照（展示用，允许为空） */
    private String summonerName;

    /** 段位所属队列（如 RANKED_SOLO_5x5） */
    private String queueType;

    /** 大段位（如 DIAMOND；未定级/无段位时为 null，展示"未知"） */
    private String tier;

    /** 小段（如 I/II/III/IV） */
    private String rank;

    /** 首次入库时间 */
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    private LocalDateTime updatedAt;
}
