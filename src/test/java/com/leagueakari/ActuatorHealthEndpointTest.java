package com.leagueakari;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验证生产部署使用的健康检查端点可访问。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReturnsStatus() throws Exception {
        // 部署脚本依赖该端点判断新容器是否真正可用。
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
