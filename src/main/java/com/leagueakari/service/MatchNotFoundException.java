package com.leagueakari.service;

/**
 * 对局不存在异常，由全局异常处理器转为 404
 */
public class MatchNotFoundException extends RuntimeException {

    /**
     * 构造异常：message 携带缺失的 gameId，便于日志定位
     */
    public MatchNotFoundException(Long gameId) {
        super("对局不存在: " + gameId);
    }
}
