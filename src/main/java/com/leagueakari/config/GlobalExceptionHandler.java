package com.leagueakari.config;

import com.leagueakari.riot.RiotAccountNotFoundException;
import com.leagueakari.util.ClientDisconnectDetector;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import com.leagueakari.match.MatchNotFoundException;

/**
 * 统一异常处理：返回 { code, message }
 * <p>路由层不捕获异常，由本类兜底转换：
 * 参数校验 → 400，对局不存在 → 404，其余未知异常 → 500。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数校验失败：取第一个字段错误拼接为提示信息，返回 400
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        // 只取首个字段错误作为 message，避免长串错误堆叠
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("Request validation failed: {}", message);
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", message));
    }

    /**
     * 对局不存在：由 service 抛出的 MatchNotFoundException 转为 404
     */
    @ExceptionHandler(MatchNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(MatchNotFoundException e) {
        log.warn("Match not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", 404, "message", e.getMessage()));
    }

    /**
     * 召唤师不存在：Riot Account-V1 返回 404 时由 service 抛出，转为 404
     */
    @ExceptionHandler(RiotAccountNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRiotAccountNotFound(RiotAccountNotFoundException e) {
        log.warn("Riot account not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", 404, "message", e.getMessage()));
    }

    /**
     * 请求体不可读：JSON 语法错误或字段类型不匹配（如 Boolean 字段收到字符串 "Win"），
     * 属于调用方参数错误，返回 400 而非 500
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        // 取最底层根因（Jackson 的具体报错）作为提示，避免完整堆栈刷日志
        String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        log.warn("Request body not readable: {}", detail);
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "请求体格式错误"));
    }

    /**
     * 路径/查询参数类型不匹配（如 gameId 传非数字）：属于调用方参数错误，返回 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Request argument type mismatch: name={}, detail={}", e.getName(), e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "参数类型错误"));
    }

    /**
     * 业务参数校验失败（如 path 与 body 的 gameId 不一致）：
     * 属于调用方参数错误，返回 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("code", 400, "message", e.getMessage()));
    }

    /**
     * 服务不可用（如 Riot API Key 未配置、外部 API 调用失败）：
     * 返回 503 并透出 message，便于前端明确提示原因
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.error("Service unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", 503, "message", e.getMessage()));
    }

    /**
     * 静态资源/路径未命中（Spring 6.1 的 NoResourceFoundException，如访问不存在的路径）：
     * 属于正常的 404 语义，返回 404 { code, message }，仅记 warn 不打堆栈，
     * 避免落入兜底分支被误报为 500 + ERROR 堆栈刷日志
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        log.warn("Resource not found: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", 404, "message", "资源不存在"));
    }

    /**
     * 请求方法不支持（如浏览器直接打开 POST-only 接口 URL：ai-analysis / timeline）：
     * 客户端用法错误，返回 405 并透出该路径支持的方法，仅记 warn 不打堆栈
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not supported: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("code", 405, "message", "请求方法不支持：" + e.getMessage()));
    }

    /**
     * 异步响应已不可用（SSE 流式输出中途客户端断开，AsyncRequestNotUsableException）：
     * 客户端断开是预期现象（关页面/刷新/网络闪断），此时响应头与 Content-Type
     * （text/event-stream）均已提交，<b>不能再写 JSON 错误体</b>——没有 converter 能
     * 把 Map 写成 event-stream，强行写会二次抛 HttpMessageNotWritableException。
     * 仅记 WARN（无堆栈）并返回 null 放弃响应
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Map<String, Object>> handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.warn("Async response not usable (client disconnected during SSE stream): {}", e.getMessage());
        return null;
    }

    /**
     * 兜底异常：记录完整堆栈后返回 500，避免把内部细节泄露给调用方。
     * <p>两类例外直接放弃写响应体（返回 null）：</p>
     * <ul>
     *   <li>客户端断开类异常（ClientAbortException / Broken pipe 等）：预期现象，
     *       记 WARN 无堆栈，避免同一断开在多层重复打 ERROR 堆栈刷屏；</li>
     *   <li>响应已提交（如 SSE 流中途中断，Content-Type 已锁定）：再写 JSON 错误体
     *       会触发 HttpMessageNotWritableException，仅记日志。</li>
     * </ul>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e, HttpServletResponse response) {
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
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", 500, "message", "服务器内部错误"));
    }
}
