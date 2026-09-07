-- 敌方侦察数据表：桌面端选人阶段经 SGP 拉取的敌方对局摘要，落库作开黑判定与窗口胜率的侦察缓存。
-- 只存"共现判定所需的摘要行"（对局 + 玩家 + 队伍 + 胜负），不落详情；再遇同一批人时命中缓存秒出卡
-- （决策见 docs/adr/0011）。
-- 数据链路：桌面端选人阶段 POST 摘要（每局约 10 行，一次选人约 1000 行）→ 本表幂等写入。
-- 幂等键 = (match_id, puuid)：同一对局同一玩家只保留一行，重复推送不产生重复数据。
CREATE TABLE IF NOT EXISTS `enemy_scout_match` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    `match_id`      VARCHAR(32)     NOT NULL COMMENT '对局标识（SGP 对局 ID，字符串存储避免精度丢失）',
    `puuid`         VARCHAR(78)     NOT NULL COMMENT '玩家唯一标识（腾讯侧 puuid，与 riot_account.puuid 同语义）',
    `summoner_name` VARCHAR(64)     NULL COMMENT '召唤师名快照（SGP 摘要直读，展示用，允许为空）',
    `team_id`       INT             NOT NULL COMMENT '该玩家所在队伍：100=蓝色方/200=红色方',
    `win`           TINYINT(1)      NOT NULL COMMENT '该玩家本局是否获胜（0=负/1=胜）',
    `queue_id`      INT             NULL COMMENT '本局队列 ID（SGP 局级字段，窗口统计备用）',
    `game_start_at` DATETIME        NULL COMMENT '对局开始时刻（摘要 gameStartTimestamp 转库内时区）',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次入库时间',
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_enemy_scout_match_player` (`match_id`, `puuid`),
    -- 开黑判定主查询：按玩家取历史对局（puuid 定位），同局同队共现判定
    KEY `idx_enemy_scout_puuid` (`puuid`, `win`),
    -- 对局维度清理/去重用
    KEY `idx_enemy_scout_match` (`match_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '敌方侦察摘要缓存：桌面端选人阶段喂入的敌方对局行（开黑判定/窗口胜率原料）';
