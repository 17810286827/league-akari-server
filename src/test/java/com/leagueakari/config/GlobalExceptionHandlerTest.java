package com.leagueakari.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandler 单元测试（standalone MockMvc，不依赖数据库）：
 * 重点验证 NoResourceFoundException（裸地址/未知路径访问静态资源未命中）
 * 被转为 404 { code, message }，而不是落入兜底分支误报 500 + ERROR 堆栈
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    /** 测试用控制器：唯一职责是抛 NoResourceFoundException，模拟静态资源未命中的请求 */
    @RestController
    static class BoomController {
        @GetMapping("/boom")
        public String boom() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "missing-resource");
        }

        /** 只接受 POST 的路径：GET 访问时由 Spring 抛 HttpRequestMethodNotSupportedException */
        @PostMapping("/post-only")
        public String postOnly() {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        // standalone 模式：不启动 Spring 上下文，注册测试控制器 + 被测异常处理器
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void noResourceFoundReturns404InsteadOf500() throws Exception {
        // 静态资源未命中（如裸地址、未知路径）：应返回 404 + 统一响应体
        mockMvc.perform(get("/boom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void methodNotSupportedReturns405() throws Exception {
        // GET 访问 POST-only 接口（如浏览器直接打开 ai-analysis/timeline URL）：
        // 应返回 405 + 统一响应体，而不是 ERROR 堆栈 + 500
        mockMvc.perform(get("/post-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").exists());
    }

    /**
     * 用例：SSE 流式响应中途客户端断开（AsyncRequestNotUsableException，Broken pipe 包装）
     * <p>SSE 响应头与 Content-Type（text/event-stream）均已提交，异常处理器<b>不能再写
     * JSON 错误体</b>——没有 converter 能把 Map 写成 event-stream，会二次抛
     * HttpMessageNotWritableException。应返回 null 仅记日志。</p>
     */
    @Test
    void asyncRequestNotUsableSkipsErrorBody() {
        AsyncRequestNotUsableException e = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to flush: java.io.IOException: Broken pipe");

        // 直接单元调用：SSE 输出失效应返回 null（不写响应体）
        var result = new GlobalExceptionHandler().handleAsyncNotUsable(e);

        assertThat(result).isNull();
    }

    /**
     * 用例：兜底分支在响应已提交时直接放弃写错误体
     * <p>场景：SSE 流中抛出客户端断开类异常（如 ClientAbortException）进入 handleOther，
     * 此时响应已提交，再返回 ResponseEntity 会触发 HttpMessageNotWritableException
     * （Content-Type 已锁定为 text/event-stream）。已提交 → 返回 null 仅记日志。</p>
     */
    @Test
    void committedResponseSkipsErrorBody() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        var handler = new GlobalExceptionHandler();
        // 已提交响应 + 客户端断开类异常：返回 null，不再构造 500 响应体
        var result = handler.handleOther(
                new RuntimeException("java.io.IOException: Broken pipe"), response);

        assertThat(result).isNull();
    }

    /**
     * 用例：兜底分支在响应未提交时保持原契约（500 + { code, message }）
     */
    @Test
    void uncommittedResponseStillReturns500() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);

        var handler = new GlobalExceptionHandler();
        var result = handler.handleOther(new IllegalStateException("boom"), response);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody()).isEqualTo(Map.of("code", 500, "message", "服务器内部错误"));
    }
}
