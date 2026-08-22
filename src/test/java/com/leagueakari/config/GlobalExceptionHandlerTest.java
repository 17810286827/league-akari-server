package com.leagueakari.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
}
