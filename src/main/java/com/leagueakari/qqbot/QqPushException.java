package com.leagueakari.qqbot;

/**
 * QQ 官方开放平台调用异常：凭证/配置缺失或接口返回非 200 时抛出。
 * 由局后播报编排捕获并落库（match.push_error），不向同步接口传播
 */
public class QqPushException extends RuntimeException {

    public QqPushException(String message) {
        super(message);
    }

    public QqPushException(String message, Throwable cause) {
        super(message, cause);
    }
}
