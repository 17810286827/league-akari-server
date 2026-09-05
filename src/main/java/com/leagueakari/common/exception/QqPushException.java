package com.leagueakari.common.exception;

/**
 * QQ 官方开放平台调用异常：凭证/配置缺失或接口返回非 200 时抛出。
 * 由局后播报编排捕获并落库（match.push_error），不向同步接口传播，
 * 也<b>不会进入 HTTP 边界</b>（它是驱动播报状态机的技术异常，不是业务失败通道）。
 * 按异常集中约定与 BizException 同住公共异常包。
 */
public class QqPushException extends RuntimeException {

    public QqPushException(String message) {
        super(message);
    }

    public QqPushException(String message, Throwable cause) {
        super(message, cause);
    }
}
