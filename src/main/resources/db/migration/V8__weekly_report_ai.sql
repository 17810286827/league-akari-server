-- V8：周报 AI 锐评持久化（工单 #39 / ADR 0007）
-- 已结束的周内容不可变：锐评首次生成后落库，后续请求直接返回不再调 AI
CREATE TABLE `weekly_report_ai` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `week_label`   VARCHAR(64)  NOT NULL COMMENT '周标签（周一日期 ~ 周日日期，如 2026-08-24 ~ 2026-08-30），唯一键',
    `comment`      TEXT         NOT NULL COMMENT '锐评完整正文（首次流式生成完成后落库）',
    `week_end_ms`  BIGINT       NOT NULL COMMENT '周结束时间（次周一 00:00 epoch 毫秒）——当前周判定与历史周检索用',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_week_label` (`week_label`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '周报 AI 锐评持久化（历史周秒开，ADR 0007）'
