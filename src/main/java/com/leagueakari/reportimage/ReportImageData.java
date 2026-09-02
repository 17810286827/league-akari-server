package com.leagueakari.reportimage;

import java.util.List;

/**
 * 战报图渲染数据（纯 DTO，与实体/数据库解耦）：
 * 由 BroadcastCoordinator 从对局数据聚合组装，ReportImageRenderer 只依赖本结构画图。
 * 布局对应视觉原型（方案 C v2）：顶栏 → 资源条 → 焦点卡 → 双方阵容 → 底栏
 */
public class ReportImageData {

    /** 焦点玩家（MVP 大卡）：胜方车队内 MVP；败局车队内 ACE（尽力）；都没有则取车队表现最佳 */
    public Player hero;

    /** 车队名 */
    public String teamName;

    /** 副标题行：模式 · 地图 · 日期 · 时长（渲染为 meta 小字） */
    public String metaLine;

    /** 是否胜利（车队视角） */
    public boolean win;

    /** 结果标签：VICTORY · 胜利 / DEFEAT · 败北 */
    public String resultLabel;

    /** 主队（车队）击杀与对方击杀 */
    public int mainScore;

    public int otherScore;

    /** 资源对比（主队 : 对方）：塔/小龙/大龙，-1 表示无数据不显示该格 */
    public int mainTower = -1;

    public int otherTower = -1;

    public int mainDragon = -1;

    public int otherDragon = -1;

    public int mainBaron = -1;

    public int otherBaron = -1;

    /** 一血归属：true=主队拿一血；false=对方；null=无数据 */
    public Boolean mainFirstBlood;

    /** 主队 5 名参赛者（含车队成员与路人），按行展示 */
    public List<Player> mainTeam;

    /** 对方 5 名参赛者 */
    public List<Player> otherTeam;

    /** 底栏左侧：车队署名 */
    public String footerLeft;

    /** 底栏右侧：如 "AI 已评阅" / 对局时长 */
    public String footerRight;

    /** 参赛者行数据 */
    public static class Player {
        /** 召唤师名 */
        public String summonerName;
        /** 英雄中文名（头像内文字与名字副行） */
        public String championName;
        /** 头像底色索引（渲染器按英雄 id 取固定色板） */
        public int championId;
        public int kills;
        public int deaths;
        public int assists;
        /** 输出占比（对局内 10 人口径，0-1） */
        public double damageShare;
        /** 承伤占比（对局内 10 人口径，0-1） */
        public double damageTakenShare;
        /** 伤害转化率（伤害/经济，1.5 = 150%） */
        public double damagePerGold;
        /** 称号角标文本：MVP / SVP / 尽力 / 背锅，null 不显示 */
        public String titleTag;
        /** 焦点卡副行文案（hero 专用） */
        public String heroSub;
        /** OP Score（hero 卡展示，0-10） */
        public double opScore;
    }
}
