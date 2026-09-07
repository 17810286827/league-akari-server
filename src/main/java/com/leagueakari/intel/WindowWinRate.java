package com.leagueakari.intel;

import lombok.Value;

import java.util.List;

/**
 * 窗口胜率：某玩家在给定对局窗口内的原始胜率统计（不可变值对象）。
 * <p>口径（ADR 0011 / spec 决策）：喂进来的窗口（约 20 局）原始胜率，不做队列过滤，
 * 零额外请求。局数与胜场一并携带，供展示层"小样本标局数不裸百分比"
 * （对齐搭档胜率矩阵原则）；空窗口返回无数据，展示层如实标注"无数据"。</p>
 */
@Value
public class WindowWinRate {

    /** 玩家标识 */
    String puuid;

    /** 窗口内胜场数 */
    int wins;

    /** 窗口内总局数 */
    int games;

    /** 胜率（wins/games，0~1；games=0 时为 null 表示无数据） */
    Double winRate;

    /**
     * 由对局胜负序列统计窗口胜率
     *
     * @param puuid 玩家标识
     * @param wins  该玩家每局的胜负（true=胜）
     * @return 窗口胜率统计；games=0 时 winRate 为 null（无数据，避免除零）
     */
    public static WindowWinRate of(String puuid, List<Boolean> wins) {
        int winCount = 0;
        for (Boolean w : wins) {
            if (Boolean.TRUE.equals(w)) {
                winCount++;
            }
        }
        int games = wins.size();
        Double rate = games == 0 ? null : (double) winCount / games;
        return new WindowWinRate(puuid, winCount, games, rate);
    }

    /** 是否窗口内有数据（games &gt; 0）；false 时 winRate 必为 null */
    public boolean hasData() {
        return games > 0;
    }
}
