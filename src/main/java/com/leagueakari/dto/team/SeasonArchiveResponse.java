package com.leagueakari.dto.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 赛季资料响应（工单 #53 / spec：英雄×海克斯强化按版本统计）：
 * 一次请求返回所选版本完整截面 + 全部版本列表 + 逐版本序列（前端悬停走势卡数据源），
 * 版本切换/排序/筛选全在前端本地完成，不二次请求。
 * <p>口径（CONTEXT 词条 + ADR 0013/0014）：全库参与者行；胜率分母=持有局数；
 * 出场率分母=英雄对局数（两个分母刻意不同）；版本=归一主版本。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeasonArchiveResponse {

    /** 库里有数据的版本列表（归一主版本，升序）——版本筛选器选项 */
    private List<String> versions;

    /** 当前响应的版本截面（归一主版本；请求省略 version 时 = 最新有数据版本；无数据时 null） */
    private String selectedVersion;

    /** 覆盖率口径（白名单可观测性：白名单选错时页面能看出来，而不是安静地全空） */
    private Coverage coverage;

    /** 英雄列表（所选版本截面，按纳入对局数降序） */
    private List<ChampionEntry> champions;

    /**
     * 覆盖率计数（双条件并列的可观测面）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Coverage {

        /** 纳入样本的海克斯乱斗对局数（白名单队列且至少一行有强化） */
        private int includedGames;

        /** 队列命中白名单、但整局无任何强化数据的局数（同步链路缺 playerAugment 的信号） */
        private int kiwiNoAugmentGames;

        /** 队列不在白名单、但参与者行带强化的行数（白名单选错/新队列号的信号） */
        private int outsideQueueAugmentRows;
    }

    /**
     * 英雄条目（所选版本截面）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChampionEntry {

        /** 英雄 ID */
        private int championId;

        /** 英雄中文名（GameDataService 全站唯一出口） */
        private String championName;

        /** 该英雄在所选版本纳入的参与者行数（出场率分母） */
        private int games;

        /** 强化条目（按所选版本出场次数降序） */
        private List<AugmentEntry> augments;
    }

    /**
     * 强化条目（英雄×强化一格）：所选版本截面指标 + 全版本逐版本序列
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AugmentEntry {

        /** 强化 ID */
        private int augmentId;

        /** 所选版本：持有该强化的对局数（胜率分母） */
        private int games;

        /** 所选版本：持有该强化的胜场数 */
        private int wins;

        /** 所选版本：胜率 = wins/games（0-1；games=0 时 0） */
        private double winRate;

        /** 所选版本：出场率 = 持有次数 ÷ 该英雄对局数（0-1；可能大于 1 之和口径见 CONTEXT） */
        private double appearanceRate;

        /** 全版本逐版本序列（走势卡数据）：版本 → [局数, 胜场]，版本升序，无数据版本缺键 */
        private Map<String, List<Integer>> byVersion;
    }
}
