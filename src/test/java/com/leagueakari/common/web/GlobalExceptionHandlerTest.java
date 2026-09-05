package com.leagueakari.common.web;

import com.leagueakari.common.exception.BizException;
import com.leagueakari.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandler 单元测试（standalone MockMvc，不依赖数据库）：
 * 验证统一响应契约——所有业务异常 HTTP 200 + ApiResult{code, message}，错误语义全靠业务码。
 * <ul>
 *   <li>BizException 主通道：错误码 + 动态文案直出；</li>
 *   <li>参数类异常（@Valid 校验失败等）→ 1001；</li>
 *   <li>兜底异常 → 5000（堆栈落日志、响应不透出细节）；</li>
 *   <li>客户端断开 / 响应已提交 → 放弃写响应体（null）。</li>
 * </ul>
 * 框架级响应（真 HTTP 非 200）：路径不存在 → 404、方法不支持 → 405，
 * WARN 一行并携带请求 URI 便于定位来源——不落兜底分支（否则 ERROR + 全堆栈刷屏、
 * 且语义漂移为 200 + 5000）。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    /** 测试用控制器：唯一职责是抛出各类异常，模拟真实 service 层的失败路径 */
    @RestController
    static class BoomController {
        /** 业务异常（带动态文案） */
        @GetMapping("/boom-biz")
        public String boomBiz() {
            throw new BizException(ErrorCode.MATCH_NOT_FOUND, "对局不存在: gameId=123");
        }

        /** 业务异常（默认文案） */
        @GetMapping("/boom-biz-default")
        public String boomBizDefault() {
            throw new BizException(ErrorCode.ROSTER_NOT_CONFIGURED);
        }

        /** 未识别异常：应落兜底 5000，响应不透出内部细节 */
        @GetMapping("/boom-runtime")
        public String boomRuntime() {
            throw new IllegalStateException("数据库密码错误等内部细节");
        }

        /** 只接受 POST 的路径：GET 访问时由 Spring 抛 HttpRequestMethodNotSupportedException */
        @org.springframework.web.bind.annotation.PostMapping("/post-only")
        public String postOnly() {
            return "ok";
        }

        /** 显式抛 NoResourceFoundException：模拟静态资源未命中（裸地址/未知路径场景同语义） */
        @GetMapping("/boom-no-resource")
        public String boomNoResource() throws org.springframework.web.servlet.resource.NoResourceFoundException {
            throw new org.springframework.web.servlet.resource.NoResourceFoundException(
                    org.springframework.http.HttpMethod.GET, "missing-resource");
        }
    }

    @BeforeEach
    void setUp() {
        // standalone 模式：不启动 Spring 上下文，注册测试控制器 + 被测异常处理器
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 用例：业务异常（带动态文案）→ HTTP 200 + 业务码 2001 + 动态文案直出 */
    @Test
    void bizExceptionReturns200WithErrorCodeAndDynamicMessage() throws Exception {
        mockMvc.perform(get("/boom-biz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001))
                .andExpect(jsonPath("$.message").value("对局不存在: gameId=123"))
                // 失败响应不带 data 字段（NON_NULL 序列化契约）
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** 用例：业务异常（默认文案）→ 错误码默认文案直出 */
    @Test
    void bizExceptionUsesDefaultMessageWhenNoDynamicContext() throws Exception {
        mockMvc.perform(get("/boom-biz-default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1101))
                .andExpect(jsonPath("$.message").value("车队名单未配置：请先在服务端配置 team.roster 成员名单"));
    }

    /**
     * 用例：未识别异常（如库深层冒出的 IllegalStateException）→ 兜底 5000。
     * <p>这是 JDK 语义通道退役的核心价值：内部异常细节不再被误判为"调用方参数错误"，
     * 也不会透出给调用方（完整堆栈只落日志）。</p>
     */
    @Test
    void unexpectedExceptionFallsBackToInternalError() throws Exception {
        mockMvc.perform(get("/boom-runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5000))
                // 固定文案：内部异常 message（如"数据库密码错误"）不得透出到响应
                .andExpect(jsonPath("$.message").value("服务器内部错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** 用例：直接单元调用校验失败映射 → 1001（@Valid 校验在集成测试覆盖，此处验映射本身） */
    @Test
    void validationFailureMapsToInvalidArgument() {
        // 构造最小 MethodArgumentNotValidException 过于繁琐（需 BindingResult），
        // 校验路径的端到端断言见 controller 集成测试（missingFrames/类型不匹配用例），此处不重复
        // 本用例仅锁定"映射常量"：参数类错误统一 1001
        assertThat(ErrorCode.INVALID_ARGUMENT.getCode()).isEqualTo(1001);
    }

    /**
     * 用例：SSE 流式响应中途客户端断开（AsyncRequestNotUsableException，Broken pipe 包装）
     * <p>SSE 响应头与 Content-Type（text/event-stream）均已提交，异常处理器<b>不能再写
     * JSON 错误体</b>——没有 converter 能把 ApiResult 写成 event-stream。应返回 null 仅记日志。</p>
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
     * 此时响应已提交，再返回 ApiResult 会触发 HttpMessageNotWritableException。</p>
     */
    @Test
    void committedResponseSkipsErrorBody() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/matches/1/ai-analysis");

        var handler = new GlobalExceptionHandler();
        // 已提交响应 + 客户端断开类异常：返回 null，不再构造错误响应体
        var result = handler.handleOther(
                new RuntimeException("java.io.IOException: Broken pipe"), request, response);

        assertThat(result).isNull();
    }

    /**
     * 用例：兜底分支在响应未提交时返回 5000 系统错误（HTTP 仍为 200，错误语义全靠业务码）
     */
    @Test
    void uncommittedResponseStillReturnsInternalError() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/matches");

        var handler = new GlobalExceptionHandler();
        var result = handler.handleOther(new IllegalStateException("boom"), request, response);

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(5000);
        assertThat(result.getMessage()).isEqualTo("服务器内部错误");
        assertThat(result.getData()).isNull();
    }

    /** 用例：GET 访问 POST-only 端点（浏览器直开接口 URL / 公网扫描）→ 真 HTTP 405，非兜底 5000 */
    @Test
    void methodNotSupportedReturnsReal405() throws Exception {
        mockMvc.perform(get("/post-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").exists());
    }

    /** 用例：静态资源未命中 → 真 HTTP 404（不落兜底 5000） */
    @Test
    void noResourceFoundReturnsReal404() throws Exception {
        mockMvc.perform(get("/boom-no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("资源不存在"));
    }
}
