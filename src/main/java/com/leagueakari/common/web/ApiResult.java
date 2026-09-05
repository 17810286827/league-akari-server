package com.leagueakari.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leagueakari.common.exception.ErrorCode;
import lombok.Getter;

/**
 * 全局响应对象：所有 JSON 端点统一返回 {code, message, data}，HTTP 一律 200。
 * <p>契约要点：</p>
 * <ul>
 *   <li>code：业务码，0=成功，非 0=失败（取值见全局错误枚举）——前端唯一判失败依据；</li>
 *   <li>message：成功固定 "ok"，失败为可直接展示的文案；</li>
 *   <li>data：业务数据，有值才序列化（NON_NULL）——同步写接口成功时缺省，
 *       失败响应同样不带 data；前端禁止用 data 是否存在判失败。</li>
 * </ul>
 * <p>豁免：AI 分析 SSE 事件流（协议自成契约）与健康检查端点（Spring 管理）。</p>
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResult<T> {

    /** 业务码：0=成功，非 0=失败（全局错误枚举登记） */
    private final int code;

    /** 提示信息：成功固定 ok，失败为可展示文案 */
    private final String message;

    /** 业务数据：有值才序列化 */
    private final T data;

    private ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功（无业务数据）：同步写接口等"成功即返回 code=0"的场景
     */
    public static ApiResult<Void> success() {
        return new ApiResult<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getDefaultMessage(), null);
    }

    /**
     * 成功（带业务数据）：查询类接口的统一包装
     *
     * @param data 业务数据（null 时 data 字段整体缺省）
     */
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getDefaultMessage(), data);
    }

    /**
     * 失败（使用错误码默认文案）
     *
     * @param errorCode 全局错误枚举项
     */
    public static ApiResult<Void> fail(ErrorCode errorCode) {
        return new ApiResult<>(errorCode.getCode(), errorCode.getDefaultMessage(), null);
    }

    /**
     * 失败（动态文案，覆盖错误码默认文案）
     *
     * @param errorCode 全局错误枚举项（决定 code）
     * @param message   可展示的动态文案
     */
    public static ApiResult<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResult<>(errorCode.getCode(), message, null);
    }
}
