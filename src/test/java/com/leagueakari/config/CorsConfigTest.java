package com.leagueakari.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 配置集成测试：模拟浏览器跨源预检（OPTIONS），验证 /api 接口返回
 * Access-Control-Allow-Origin 等 CORS 响应头——这是前端 Vite dev server
 * 直连后端时用户可见的行为契约（预检被拦截即前端请求失败）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 用例：带 Origin 与 Access-Control-Request-Method 的预检应返回
     * Access-Control-Allow-Origin 回显头（allowedOriginPatterns("*") 匹配任意来源）
     */
    @Test
    void preflightForApiReturnsCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/matches")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
    }

    /**
     * 用例：非 /api 路径的预检不应携带 CORS 头，验证跨域仅对 API 开放
     */
    @Test
    void preflightOutsideApiHasNoCorsHeaders() throws Exception {
        mockMvc.perform(options("/not-an-api")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
