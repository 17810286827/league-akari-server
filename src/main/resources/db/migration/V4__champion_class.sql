-- 英雄职业分类表
-- 用于 MVP/SVP 评选时按职业差异化评分
-- 数据来源：Riot Data Dragon 16.16.1 champion tags（主职业取其第一个 tag）
CREATE TABLE IF NOT EXISTS `champion_class` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY  COMMENT '主键',
    `champion_id`  INT             NOT NULL                            COMMENT '英雄 ID（幂等键）',
    `class_name`   VARCHAR(32)     NOT NULL                            COMMENT '英雄职业：ADC/MAGE/TANK/ASSASSIN/FIGHTER/SUPPORT',
    `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP   COMMENT '记录创建时间',
    UNIQUE KEY `uk_champion_class_champion_id` (`champion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='英雄职业分类';

-- 射手（28 个）
INSERT IGNORE INTO `champion_class` (`champion_id`, `class_name`) VALUES
(15, 'ADC'),  -- 战争女神
(17, 'ADC'),  -- 迅捷斥候
(18, 'ADC'),  -- 麦林炮手
(21, 'ADC'),  -- 赏金猎人
(22, 'ADC'),  -- 寒冰射手
(29, 'ADC'),  -- 瘟疫之源
(42, 'ADC'),  -- 英勇投弹手
(51, 'ADC'),  -- 皮城女警
(67, 'ADC'),  -- 暗夜猎手
(81, 'ADC'),  -- 探险家
(96, 'ADC'),  -- 深渊巨口
(104, 'ADC'),  -- 法外狂徒
(110, 'ADC'),  -- 惩戒之箭
(119, 'ADC'),  -- 荣耀行刑官
(133, 'ADC'),  -- 德玛西亚之翼
(145, 'ADC'),  -- 虚空之女
(166, 'ADC'),  -- 影哨
(202, 'ADC'),  -- 戏命师
(203, 'ADC'),  -- 永猎双子
(221, 'ADC'),  -- 祖安花火
(222, 'ADC'),  -- 暴走萝莉
(236, 'ADC'),  -- 圣枪游侠
(360, 'ADC'),  -- 沙漠玫瑰
(429, 'ADC'),  -- 复仇之矛
(498, 'ADC'),  -- 逆羽
(523, 'ADC'),  -- 残月之肃
(804, 'ADC'),  -- 不破之誓
(901, 'ADC');  -- 炽炎雏龙

-- 法师（36 个）
INSERT IGNORE INTO `champion_class` (`champion_id`, `class_name`) VALUES
(1, 'MAGE'),  -- 黑暗之女
(4, 'MAGE'),  -- 卡牌大师
(8, 'MAGE'),  -- 猩红收割者
(9, 'MAGE'),  -- 远古恐惧
(10, 'MAGE'),  -- 正义天使
(13, 'MAGE'),  -- 符文法师
(30, 'MAGE'),  -- 死亡颂唱者
(34, 'MAGE'),  -- 冰晶凤凰
(43, 'MAGE'),  -- 天启者
(45, 'MAGE'),  -- 邪恶小法师
(50, 'MAGE'),  -- 诺克萨斯统领
(61, 'MAGE'),  -- 发条魔灵
(63, 'MAGE'),  -- 复仇焰魂
(69, 'MAGE'),  -- 魔蛇之拥
(74, 'MAGE'),  -- 大发明家
(85, 'MAGE'),  -- 狂暴之心
(90, 'MAGE'),  -- 虚空先知
(99, 'MAGE'),  -- 光辉女郎
(101, 'MAGE'),  -- 远古巫灵
(103, 'MAGE'),  -- 九尾妖狐
(112, 'MAGE'),  -- 奥术先驱
(115, 'MAGE'),  -- 爆破鬼才
(127, 'MAGE'),  -- 冰霜女巫
(134, 'MAGE'),  -- 暗黑元首
(136, 'MAGE'),  -- 铸星龙王
(142, 'MAGE'),  -- 暮光星灵
(143, 'MAGE'),  -- 荆棘之兴
(161, 'MAGE'),  -- 虚空之眼
(163, 'MAGE'),  -- 岩雀
(268, 'MAGE'),  -- 沙漠皇帝
(517, 'MAGE'),  -- 解脱者
(518, 'MAGE'),  -- 万花通灵
(711, 'MAGE'),  -- 愁云使者
(800, 'MAGE'),  -- 流光镜影
(893, 'MAGE'),  -- 双界灵兔
(910, 'MAGE');  -- 异画师

-- 坦克（24 个）
INSERT IGNORE INTO `champion_class` (`champion_id`, `class_name`) VALUES
(3, 'TANK'),  -- 正义巨像
(12, 'TANK'),  -- 牛头酋长
(14, 'TANK'),  -- 亡灵战神
(20, 'TANK'),  -- 雪原双子
(27, 'TANK'),  -- 炼金术士
(31, 'TANK'),  -- 虚空恐惧
(32, 'TANK'),  -- 殇之木乃伊
(33, 'TANK'),  -- 披甲龙龟
(36, 'TANK'),  -- 祖安狂人
(53, 'TANK'),  -- 蒸汽机器人
(54, 'TANK'),  -- 熔岩巨兽
(57, 'TANK'),  -- 扭曲树精
(72, 'TANK'),  -- 上古领主
(78, 'TANK'),  -- 圣锤之毅
(89, 'TANK'),  -- 曙光女神
(98, 'TANK'),  -- 暮光之眼
(111, 'TANK'),  -- 深海泰坦
(113, 'TANK'),  -- 北地之怒
(154, 'TANK'),  -- 生化魔人
(201, 'TANK'),  -- 弗雷尔卓德之心
(223, 'TANK'),  -- 河流之王
(516, 'TANK'),  -- 山隐之焰
(526, 'TANK'),  -- 镕铁少女
(897, 'TANK');  -- 纳祖芒荣耀

-- 刺客（17 个）
INSERT IGNORE INTO `champion_class` (`champion_id`, `class_name`) VALUES
(7, 'ASSASSIN'),  -- 诡术妖姬
(28, 'ASSASSIN'),  -- 痛苦之拥
(35, 'ASSASSIN'),  -- 恶魔小丑
(38, 'ASSASSIN'),  -- 虚空行者
(55, 'ASSASSIN'),  -- 不祥之刃
(60, 'ASSASSIN'),  -- 蜘蛛女皇
(76, 'ASSASSIN'),  -- 狂野女猎手
(84, 'ASSASSIN'),  -- 离群之刺
(91, 'ASSASSIN'),  -- 刀锋之影
(105, 'ASSASSIN'),  -- 潮汐海灵
(107, 'ASSASSIN'),  -- 傲之追猎者
(121, 'ASSASSIN'),  -- 虚空掠夺者
(238, 'ASSASSIN'),  -- 影流之主
(245, 'ASSASSIN'),  -- 时间刺客
(246, 'ASSASSIN'),  -- 元素女皇
(805, 'ASSASSIN'),  -- 灰烬驱魔人
(950, 'ASSASSIN');  -- 百裂冥犬

-- 战士（50 个）
INSERT IGNORE INTO `champion_class` (`champion_id`, `class_name`) VALUES
(2, 'FIGHTER'),  -- 狂战士
(5, 'FIGHTER'),  -- 德邦总管
(6, 'FIGHTER'),  -- 无畏战车
(11, 'FIGHTER'),  -- 无极剑圣
(19, 'FIGHTER'),  -- 祖安怒兽
(23, 'FIGHTER'),  -- 蛮族之王
(24, 'FIGHTER'),  -- 武器大师
(39, 'FIGHTER'),  -- 刀锋舞者
(41, 'FIGHTER'),  -- 海洋之灾
(48, 'FIGHTER'),  -- 巨魔之王
(56, 'FIGHTER'),  -- 永恒梦魇
(58, 'FIGHTER'),  -- 荒漠屠夫
(59, 'FIGHTER'),  -- 德玛西亚皇子
(62, 'FIGHTER'),  -- 齐天大圣
(64, 'FIGHTER'),  -- 盲僧
(68, 'FIGHTER'),  -- 机械公敌
(75, 'FIGHTER'),  -- 沙漠死神
(77, 'FIGHTER'),  -- 兽灵行者
(79, 'FIGHTER'),  -- 酒桶
(80, 'FIGHTER'),  -- 不屈之枪
(82, 'FIGHTER'),  -- 铁铠冥魂
(83, 'FIGHTER'),  -- 牧魂人
(86, 'FIGHTER'),  -- 德玛西亚之力
(92, 'FIGHTER'),  -- 放逐之刃
(102, 'FIGHTER'),  -- 龙血武姬
(106, 'FIGHTER'),  -- 不灭狂雷
(114, 'FIGHTER'),  -- 无双剑姬
(120, 'FIGHTER'),  -- 战争之影
(122, 'FIGHTER'),  -- 诺克萨斯之手
(126, 'FIGHTER'),  -- 未来守护者
(131, 'FIGHTER'),  -- 皎月女神
(141, 'FIGHTER'),  -- 影流之镰
(150, 'FIGHTER'),  -- 迷失之牙
(157, 'FIGHTER'),  -- 疾风剑豪
(164, 'FIGHTER'),  -- 青钢影
(200, 'FIGHTER'),  -- 虚空女皇
(233, 'FIGHTER'),  -- 狂厄蔷薇
(234, 'FIGHTER'),  -- 破败之王
(240, 'FIGHTER'),  -- 暴怒骑士
(254, 'FIGHTER'),  -- 皮城执法官
(266, 'FIGHTER'),  -- 暗裔剑魔
(420, 'FIGHTER'),  -- 海兽祭司
(421, 'FIGHTER'),  -- 虚空遁地兽
(777, 'FIGHTER'),  -- 封魔剑魂
(799, 'FIGHTER'),  -- 铁血狼母
(875, 'FIGHTER'),  -- 腕豪
(876, 'FIGHTER'),  -- 含羞蓓蕾
(887, 'FIGHTER'),  -- 灵罗娃娃
(895, 'FIGHTER'),  -- 不羁之悦
(904, 'FIGHTER');  -- 不落魔锋

-- 辅助（18 个）
INSERT IGNORE INTO `champion_class` (`champion_id`, `class_name`) VALUES
(16, 'SUPPORT'),  -- 众星之子
(25, 'SUPPORT'),  -- 堕落天使
(26, 'SUPPORT'),  -- 时光守护者
(37, 'SUPPORT'),  -- 琴瑟仙女
(40, 'SUPPORT'),  -- 风暴之怒
(44, 'SUPPORT'),  -- 瓦洛兰之盾
(117, 'SUPPORT'),  -- 仙灵女巫
(147, 'SUPPORT'),  -- 星籁歌姬
(235, 'SUPPORT'),  -- 涤魂圣枪
(267, 'SUPPORT'),  -- 唤潮鲛姬
(350, 'SUPPORT'),  -- 魔法猫咪
(412, 'SUPPORT'),  -- 魂锁典狱长
(427, 'SUPPORT'),  -- 翠神
(432, 'SUPPORT'),  -- 星界游神
(497, 'SUPPORT'),  -- 幻翎
(555, 'SUPPORT'),  -- 血港鬼影
(888, 'SUPPORT'),  -- 炼金男爵
(902, 'SUPPORT');  -- 明烛
