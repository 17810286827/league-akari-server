package com.leagueakari.riot;

/**
 * 召唤师不存在异常（Riot Account-V1 返回 404），由全局异常处理器转为 404
 */
public class RiotAccountNotFoundException extends RuntimeException {

    /**
     * 构造异常：message 携带查询的召唤师名，便于日志定位
     */
    public RiotAccountNotFoundException(String riotName) {
        super("召唤师不存在: " + riotName);
    }
}
