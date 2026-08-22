-- Riot 账号缓存表：按名搜索结果持久化，查库替代 Riot API（puuid 终身不变）
CREATE TABLE riot_account (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    puuid           VARCHAR(78)     NOT NULL COMMENT '拳头账号唯一标识（终身不变，一人一行的幂等键）',
    game_name       VARCHAR(64)     NOT NULL COMMENT '昵称（# 前部分；玩家改名后按 puuid 更新此字段）',
    tag_line        VARCHAR(24)     NOT NULL COMMENT '尾号（# 后部分，如 iKun）',
    summoner_level  INT             NULL COMMENT '召唤师等级（Summoner-V4 快照，低频变化允许略旧）',
    profile_icon_id INT             NULL COMMENT '召唤师头像 ID（Summoner-V4，用于拼头像 URL）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次入库时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (id),
    -- puuid 唯一：改名场景按 puuid 覆盖更新名字，保证一人一行
    UNIQUE KEY uk_riot_account_puuid (puuid),
    -- 按名查询索引：不设唯一（Riot 名字可释放后再被他人占用，历史上可能短暂出现同名多行）
    KEY idx_riot_account_name (game_name, tag_line)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Riot 账号缓存表（按名搜索结果持久化）';
