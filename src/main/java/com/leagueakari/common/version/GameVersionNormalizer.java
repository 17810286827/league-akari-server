package com.leagueakari.common.version;

/**
 * game_version 归一函数（工单 #38 / spec #32，全站单点定义）：
 * 原始格式（LCU/SGP "16.15.802.4387"、Riot "15.16.802.1234"）→ 主版本
 * "大版本.小版本"（一个平衡补丁周期）。
 * <p>纯静态函数：任何消费方（榜单/成员卡/赛季报告）都经此归一，禁止各自截取。</p>
 */
public final class GameVersionNormalizer {

    private GameVersionNormalizer() {
        // 工具类：禁止实例化
    }

    /**
     * 归一 game_version 原始值为主版本（前两段）
     *
     * @param rawVersion 原始值（如 "16.15.802.4387"）；null/空返回 null
     * @return 主版本（如 "16.15"）；不足两段时原样返回
     */
    public static String normalize(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return null;
        }
        String trimmed = rawVersion.trim();
        int firstDot = trimmed.indexOf('.');
        if (firstDot < 0) {
            // 无点分隔的异常格式：原样返回（不猜格式，可追溯）
            return trimmed;
        }
        int secondDot = trimmed.indexOf('.', firstDot + 1);
        return secondDot < 0 ? trimmed : trimmed.substring(0, secondDot);
    }
}
