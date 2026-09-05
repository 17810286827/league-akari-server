package com.leagueakari.common.exception;

import lombok.Getter;

/**
 * 业务异常：携带全局错误码的运行时异常，service 层表达"有登记的业务失败"的唯一通道。
 * <p>与意外异常的边界：抛出本异常 = 该失败在 {@link ErrorCode} 有登记、文案可向用户展示，
 * 全局异常处理器按其错误码直出统一响应；其余一切未识别异常统一落系统兜底（5000）。
 * 抛出规则：业务规则不满足直接抛、不 catch；catch 仅允许出现在异常翻译边界
 * （技术异常按业务语义翻译后再抛，如 Riot 出口的 404/429）。</p>
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务错误码（全局错误枚举的一项），全局处理器据此产出 code */
    private final ErrorCode errorCode;

    /**
     * 直接使用错误码默认文案（无动态上下文的场景）
     *
     * @param errorCode 全局错误枚举项
     */
    public BizException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * 动态文案覆盖默认文案（携带 gameId/riotId 等定位上下文时使用）
     *
     * @param errorCode 全局错误枚举项
     * @param message   可展示的动态文案（覆盖默认文案）
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 异常翻译边界专用：技术异常按业务语义翻译时保留根因堆栈，
     * 保证"原始异常"不被吞掉（排障时沿 cause 链能看到技术根因）
     *
     * @param errorCode 全局错误枚举项
     * @param message   可展示的动态文案
     * @param cause     技术根因
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
