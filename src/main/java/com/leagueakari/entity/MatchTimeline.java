package com.leagueakari.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对局时间线实体，与 V2__match_timeline.sql 的 match_timeline 表一一对应
 */
@Data
@TableName("match_timeline")
public class MatchTimeline {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** LCU 对局 ID，幂等键 */
    private Long gameId;

    /** 时间线 frames 数组全量（JSON 字符串，原样存储） */
    private String framesJson;

    /** 落库时间 */
    private LocalDateTime createdAt;
}
