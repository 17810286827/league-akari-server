-- 敌方情报推送去重表：以游戏开始信号携带的 gameId 为幂等键，保证同一局只推一次情报卡。
-- 与局后播报（match.push_status）不同：情报推送触发于"游戏开始"，此时对局尚未经 match-sync
-- 入库，故需独立去重表；对局结束后 match-sync 落库是另一条链路，两者互不依赖。
-- 数据链路：桌面端 Gameflow 进入 InProgress 时 POST 开始信号（gameId + 双方阵营 + 上报者）
-- → 本表按 gameId 首次插入成功才发卡（并发安全：唯一键兜底），重复信号跳过。
CREATE TABLE IF NOT EXISTS `intel_game_start` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `game_id`       BIGINT UNSIGNED NOT NULL COMMENT '对局标识（游戏开始后的 gameId，去重幂等键）',
    `blue_puids`    JSON            NULL COMMENT '蓝色方（teamId 100）玩家 puuid 数组',
    `red_puids`     JSON            NULL COMMENT '红色方（teamId 200）玩家 puuid 数组',
    `reporter_puuid` VARCHAR(78)    NULL COMMENT '上报开始信号的桌面端玩家 puuid（参考信号，非锚定依据）',
    `friendly_team_id` INT          NULL COMMENT '我方所在队伍（roster 多数派判定结果：100 蓝 / 200 红；未命中为 null）',
    `push_status`   VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '情报卡推送状态: PENDING待推/SENT已送达/FAILED失败',
    `push_error`    VARCHAR(512)    NULL COMMENT '最近一次失败原因',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次收到信号时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_intel_game_start_game` (`game_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '敌方情报推送去重与状态表：游戏开始信号（gameId 幂等）';
