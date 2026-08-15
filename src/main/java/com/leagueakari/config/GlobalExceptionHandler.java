package com.leagueakari.config;

import com.leagueakari.service.MatchNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

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
     * 兜底异常：记录完整堆栈后返回 500，避免把内部细节泄露给调用方
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", 500, "message", "服务器内部错误"));
    }
}
