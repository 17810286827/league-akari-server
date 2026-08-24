-- 评分系统改造 V2：op.gg 风格 OP Score 基线表 + match_mvp 扩展列
-- 对应 spec: .scratch/op-score-scoring/spec.md
-- 版本号：2（scoring_version = 2），旧算法为版本 1（默认）

-- 1. 评分基线统计表：按英雄累积的每分钟维度均值
-- 同步新对局时 INSERT ... ON DUPLICATE KEY UPDATE 累加
CREATE TABLE IF NOT EXISTS `scoring_baseline` (
    `champion_id`     INT UNSIGNED    NOT NULL                COMMENT '英雄 ID',
    `sample_count`    INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '样本量（该英雄出现的对局数）',
    `sum_damage`      DOUBLE          NOT NULL DEFAULT 0      COMMENT 'damage 每分钟值累计和',
    `sum_kda`         DOUBLE          NOT NULL DEFAULT 0      COMMENT 'kda 累计和（无量纲）',
    `sum_gold`        DOUBLE          NOT NULL DEFAULT 0      COMMENT 'gold 每分钟值累计和',
    `sum_tank`        DOUBLE          NOT NULL DEFAULT 0      COMMENT 'tank 每分钟值累计和',
    `sum_heal_shield` DOUBLE          NOT NULL DEFAULT 0      COMMENT 'healShield 每分钟值累计和',
    `sum_cc`          DOUBLE          NOT NULL DEFAULT 0      COMMENT 'cc 每分钟值累计和',
    `sum_turret`      DOUBLE          NOT NULL DEFAULT 0      COMMENT 'turret 每分钟值累计和',
    `updated_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`champion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评分基线统计：按英雄累积的每分钟维度均值，用于 OP Score 基线比较';

-- 2. match_mvp 扩展：评分版本号、OP Score、文字等级
ALTER TABLE `match_mvp`
    ADD COLUMN `scoring_version` INT UNSIGNED NOT NULL DEFAULT 2  COMMENT '评分算法版本号（1=旧队内归一化，2=OpScore）' AFTER `type`,
    ADD COLUMN `op_score`        DECIMAL(4,1)    NULL            COMMENT 'OP Score（0-10，一位小数）' AFTER `score`,
    ADD COLUMN `grade`           VARCHAR(8)      NULL            COMMENT '文字等级（完美/卓越/优秀/良好/一般/偏低/较差/糟糕）' AFTER `op_score`;