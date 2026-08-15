-- 对局主表（match 是 MySQL 保留字，表名需反引号）
CREATE TABLE `match` (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    game_id           BIGINT UNSIGNED NOT NULL COMMENT 'LCU 对局 ID，幂等键',
    game_creation     BIGINT UNSIGNED NOT NULL COMMENT '对局创建时间戳（ms）',
    game_duration     INT UNSIGNED    NOT NULL COMMENT '对局时长（秒）',
    game_mode         VARCHAR(32)     NOT NULL COMMENT '模式，如 CLASSIC / CHERRY',
    game_type         VARCHAR(32)     NOT NULL COMMENT '类型，如 MATCHED_GAME',
    queue_id          INT             NOT NULL COMMENT '队列 ID',
    map_id            INT             NOT NULL COMMENT '地图 ID',
    game_version      VARCHAR(32)     NOT NULL COMMENT '游戏版本',
    region            VARCHAR(16)     NOT NULL COMMENT '地区，如 na1',
    rso_platform_id   VARCHAR(32)     NOT NULL COMMENT '区服，腾讯服如 SG2',
    data_source       VARCHAR(8)      NOT NULL COMMENT '数据源：lcu / sgp',
    winner_team_id    INT             NULL COMMENT '获胜队伍 ID',
    self_puuid        VARCHAR(78)     NOT NULL COMMENT '记录本局的玩家 puuid',
    teams_json        JSON            NULL COMMENT '队伍级统计全量快照',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_match_game_id (game_id),
    KEY idx_match_game_creation (game_creation),
    KEY idx_match_queue_id (queue_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对局主表';

-- 参赛者明细表
CREATE TABLE match_participant (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    match_id         BIGINT UNSIGNED NOT NULL COMMENT '所属对局（match.id）',
    puuid            VARCHAR(78)     NOT NULL COMMENT '玩家 puuid',
    summoner_name    VARCHAR(64)     NOT NULL COMMENT '召唤师名',
    champion_id      INT             NOT NULL COMMENT '英雄 ID',
    team_id          INT             NOT NULL COMMENT '队伍 ID',
    position         VARCHAR(16)     NULL COMMENT '分路',
    kills            INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '击杀（直显用）',
    deaths           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '死亡（直显用）',
    assists          INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '助攻（直显用）',
    win              TINYINT(1)      NOT NULL COMMENT '是否获胜',
    gold_earned      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '获得金币（直显用）',
    cs               INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '补刀数 = stats.totalMinionsKilled（直显用）',
    items            JSON            NULL COMMENT '出装（物品 ID 数组，直显用）',
    summoner_spells  JSON            NULL COMMENT '召唤师技能（2 个 ID，直显用）',
    stats_json       JSON            NOT NULL COMMENT 'stats 全量快照（伤害/承伤/视野等全部原始字段）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_match_participant (match_id, puuid),
    KEY idx_participant_puuid (puuid),
    CONSTRAINT fk_participant_match FOREIGN KEY (match_id) REFERENCES `match` (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '参赛者明细表';
