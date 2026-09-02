-- 局后播报推送状态列
-- 开黑局（车队对局）首次入库后自动向车队群推送战报图与局后锐评（见 docs/spec/post-game-broadcast.md）
-- 状态机：PENDING（待推送）→ SENT（全部送达）/ AI_FAILED（图已发、AI 缺席已提示）/ FAILED（失败，待桌面端补推重试）
ALTER TABLE `match`
    ADD COLUMN `push_status`    VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '局后播报状态：PENDING 待推送 / SENT 已送达 / AI_FAILED 图已发 AI 缺席 / FAILED 失败待补推',
    ADD COLUMN `push_image_at`  DATETIME     NULL COMMENT '战报图发送时间',
    ADD COLUMN `push_comment_at` DATETIME    NULL COMMENT '局后锐评（或 AI 缺席提示）发送时间',
    ADD COLUMN `push_error`     VARCHAR(512) NULL COMMENT '最近一次失败原因';

-- 存量历史对局标记为已送达：避免迁移后把时间窗外的旧局误当"刚结束"播报到群里
UPDATE `match` SET `push_status` = 'SENT'
WHERE `created_at` < NOW() - INTERVAL 1 DAY;
