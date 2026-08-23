-- MVP/SVP 评选结果表
-- 对局同步时计算评选，每场对局最多两条记录（MVP + SVP）
CREATE TABLE IF NOT EXISTS `match_mvp` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY  COMMENT '主键',
    `match_id`          BIGINT UNSIGNED NOT NULL                            COMMENT '所属对局（match.id）',
    `participant_id`    BIGINT UNSIGNED NOT NULL                            COMMENT '获得称号的参与者（match_participant.id）',
    `type`              VARCHAR(8)      NOT NULL                            COMMENT '称号类型：MVP / SVP',
    `score`             DECIMAL(10,2)   NOT NULL                            COMMENT '归一化总分（0-100）',
    `score_detail_json` JSON            NULL                                COMMENT '评分明细：{ "维度名": { "raw": 原始值, "score": 归一化得分 } }',
    `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP   COMMENT '记录创建时间',
    UNIQUE KEY `uk_match_mvp` (`match_id`, `type`),
    KEY `idx_match_mvp_participant` (`participant_id`),
    CONSTRAINT `fk_match_mvp_match` FOREIGN KEY (`match_id`) REFERENCES `match` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_match_mvp_participant` FOREIGN KEY (`participant_id`) REFERENCES `match_participant` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MVP/SVP 评选结果';