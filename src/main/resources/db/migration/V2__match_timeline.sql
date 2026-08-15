-- 对局时间线表：frames 全量 JSON 入库，game_id 唯一键承担幂等兜底
CREATE TABLE match_timeline (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    game_id     BIGINT UNSIGNED NOT NULL COMMENT 'LCU 对局 ID，幂等键',
    frames_json JSON            NOT NULL COMMENT '时间线 frames 数组全量（原样存储）',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_timeline_game_id (game_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对局时间线表';
