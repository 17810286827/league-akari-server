package com.leagueakari.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RootController 单元测试（standalone MockMvc，不依赖数据库）：
 * 根路径 "/" 返回服务引导信息（code=0），裸地址访问得到 200 友好响应
 */
class RootControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RootController()).build();
    }

    @Test
    void rootReturnsServiceInfo() throws Exception {
        // 裸地址访问（GET /）：返回 200 + 服务信息，不再是 404/500
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.service").value("league-akari-server"))
                .andExpect(jsonPath("$.health").value("/actuator/health"));
    }
}
