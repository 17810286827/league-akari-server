package com.leagueakari.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 周报 AI 锐评持久化实体（工单 #39 / ADR 0007）：
 * 已结束的周内容不可变——首次生成后落库，后续请求直接返回不再调 AI。
 * 唯一键 week_label 兜底并发首次生成（后写者发现已存在则丢弃）。
 */
@Data
@TableName("weekly_report_ai")
public class WeeklyReportAi {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 周标签（周一 ~ 周日），唯一键 */
    private String weekLabel;

    /** 锐评完整正文 */
    private String comment;

    /** 周结束时间（次周一 00:00 epoch 毫秒）——当前周判定用 */
    private Long weekEndMs;

    /** 生成时间 */
    private LocalDateTime createdAt;
}
