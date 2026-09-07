-- 敌方侦察段位快照表：桌面端选人阶段喂入的敌方玩家段位，落库作情报卡"段位"展示原料。
-- 与 enemy_scout_match（对局摘要行）不同，段位是"每玩家一条"的快照，按 puuid 幂等覆盖更新。
-- 数据链路：桌面端选人阶段 POST 段位（SGP leagues-ledge 直读）→ 本表 upsert；
-- 游戏开始发卡时按 puuid 读取，缺则标注"未知"（决策见 docs/adr/0011）。
CREATE TABLE IF NOT EXISTS `enemy_scout_rank` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `puuid`         VARCHAR(78)     NOT NULL COMMENT '玩家唯一标识（腾讯侧 puuid，与 enemy_scout_match.puuid 同语义）',
    `summoner_name` VARCHAR(64)     NULL COMMENT '召唤师名快照（展示用，允许为空）',
    `queue_type`    VARCHAR(32)     NULL COMMENT '段位所属队列（如 RANKED_SOLO_5x5；SGP rankedStats 队列标识）',
    `tier`          VARCHAR(32)     NULL COMMENT '大段位（如 DIAMOND；未定级/无段位时为 NULL，展示"未知"）',
    `rank`          VARCHAR(8)      NULL COMMENT '小段（如 I/II/III/IV）',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次入库时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_enemy_scout_rank_puuid` (`puuid`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '敌方侦察段位快照缓存：选人阶段喂入的敌方玩家段位（情报卡展示原料）';
