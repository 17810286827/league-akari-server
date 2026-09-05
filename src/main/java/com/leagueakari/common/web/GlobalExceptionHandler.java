package com.leagueakari.common.web;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一异常处理：全部转成 HTTP 200 + ApiResult{code, message}，错误语义全靠业务码。
 * <p>映射规则（唯一事实来源是全局错误枚举的登记）：</p>
 * <ul>
 *   <li>{@link BizException} —— 业务主通道：service 抛出的有登记失败，按其错误码直出；</li>
 *   <li>参数类（@Valid 校验失败/请求体不可读/参数类型不匹配）—— 统一 1001；</li>
 *   <li>其余一切未识别异常 —— 兜底 5000（完整堆栈落日志，响应不透出内部细节）。</li>
 * </ul>
 * <p><b>退役的 JDK 语义通道</b>：IllegalArgumentException/IllegalStateException 不再映射
 * 400/503——库深层冒出的 JDK 异常统一落兜底，避免状态码撒谎；业务失败必须走 BizException。</p>
 * <p><b>不写响应体的两类例外</b>（返回 null 放弃响应）：
 * ① SSE 中途客户端断开（响应头与 event-stream 已提交，无 converter 能写 JSON 错误体）；
 * ② 客户端断开类异常 / 响应已提交——预期现象仅记日志，避免重复 ERROR 刷屏。
 * HTTP 非 200 只留给"未达业务"的框架级响应（路径不存在 404、方法不支持 405，交由 Spring 默认行为）。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常主通道：有登记的错误码 + 可展示文案，HTTP 200 直出统一响应
     */
    @ExceptionHandler(BizException.class)
    public ApiResult<Void> handleBiz(BizException e) {
        // 业务失败是预期事件：WARN 一条即可，不打堆栈（排查靠 code + message）
        log.warn("业务异常: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ApiResult.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * @Valid 校验失败：取第一个字段错误拼接为提示，统一落 1001
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResult<Void> handleValidation(MethodArgumentNotValidException e) {
        // 只取首个字段错误作为 message，避免长串错误堆叠
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("Request validation failed: {}", message);
        return ApiResult.fail(ErrorCode.INVALID_ARGUMENT, message);
    }

    /**
     * 请求体不可读：JSON 语法错误或字段类型不匹配（如 Boolean 字段收到字符串 "Win"），
     * 属于调用方参数错误，统一落 1001
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResult<Void> handleUnreadable(HttpMessageNotReadableException e) {
        // 取最底层根因（Jackson 的具体报错）作为日志线索，避免完整堆栈刷日志
        String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        log.warn("Request body not readable: {}", detail);
        return ApiResult.fail(ErrorCode.INVALID_ARGUMENT, "请求体格式错误");
    }

    /**
     * 路径/查询参数类型不匹配（如 gameId 传非数字）：统一落 1001
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResult<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Request argument type mismatch: name={}, detail={}", e.getName(), e.getMessage());
        return ApiResult.fail(ErrorCode.INVALID_ARGUMENT, "参数类型错误");
    }

    /**
     * 异步响应已不可用（SSE 流式输出中途客户端断开）：响应头与 Content-Type
     * （text/event-stream）均已提交，<b>不能再写 JSON 错误体</b>——强行写会二次抛
     * HttpMessageNotWritableException。仅记 WARN（无堆栈）并返回 null 放弃响应
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ApiResult<Void> handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.warn("Async response not usable (client disconnected during SSE stream): {}", e.getMessage());
        return null;
    }

    /**
     * 兜底异常：记录完整堆栈后返回 5000 系统错误，避免把内部细节泄露给调用方。
     * <p>两类例外直接放弃写响应体（返回 null）：</p>
     * <ul>
     *   <li>客户端断开类异常（ClientAbortException / Broken pipe 等）：预期现象，
     *       记 WARN 无堆栈，避免同一断开在多层重复打 ERROR 堆栈刷屏；</li>
     *   <li>响应已提交（如 SSE 流中途中断，Content-Type 已锁定）：再写错误体
     *       会触发 HttpMessageNotWritableException，仅记日志。</li>
     * </ul>
     */
    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleOther(Exception e, HttpServletResponse response) {
        // 客户端断开（关页面/刷新/网络断开导致的 Broken pipe）：预期事件，WARN 无堆栈
        if (ClientDisconnectDetector.isClientDisconnect(e)) {
            log.warn("Client disconnected during response: {}", e.getMessage());
            return null;
        }
        log.error("Unexpected error", e);
        // 响应已提交（SSE 流式输出已开始）：无法再写错误体，直接放弃
        if (response.isCommitted()) {
            return null;
        }
        return ApiResult.fail(ErrorCode.INTERNAL_ERROR);
    }
}
