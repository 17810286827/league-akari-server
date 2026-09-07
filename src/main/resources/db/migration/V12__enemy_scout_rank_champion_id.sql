-- 敌方侦察段位快照表扩展：新增 champion_id 字段，承载选人阶段锁定的英雄 ID，
-- 供情报卡渲染英雄头像（ChampionIconService 按 championId 拉 CommunityDragon 头像）
-- 与英雄名（GameDataService.championName）。桌面端喂段位时随 puuid 一并上报。
ALTER TABLE `enemy_scout_rank`
    ADD COLUMN `champion_id` INT NULL COMMENT '选人阶段锁定的英雄 ID（情报卡英雄头像/英雄名原料；未锁定/无数据为 NULL）' AFTER `rank`;
