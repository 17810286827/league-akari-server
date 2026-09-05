package com.leagueakari.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MatchController 时间线接口集成测试：真实写入虚拟机 MySQL，覆盖幂等写入、
 * 原样查询与 404 三类核心契约。
 * <p>说明：@Transactional 使每个用例结束后回滚，不污染数据库；
 * 幂等用例依赖同事务内对插入数据的可见性，Spring 事务默认传播下成立。
 * 测试专用 gameId 取 9000000051~9000000056 区间，避免与真实数据冲突。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MatchTimelineControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 用例：同一 gameId 重复推送时间线均返回 200 且 code=0，验证服务端幂等跳过
     */
    @Test
    void postTimeline_isIdempotent() throws Exception {
        long gameId = 9000000051L;
        // frames 全量数组：与 LCU timeline 返回结构一致（数组套对象）
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("gameId", gameId,
                        "frames", java.util.List.of(java.util.Map.of("timestamp", 1000, "events", java.util.List.of()))));

        // 首次推送：应成功落库
        mockMvc.perform(post("/api/matches/{gameId}/timeline", gameId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 重复推送同样返回 200，不覆盖首次内容
        mockMvc.perform(post("/api/matches/{gameId}/timeline", gameId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /**
     * 用例：先推送时间线再查询，data 应与推送的 frames 原样一致（结构逐层校验）
     */
    @Test
    void getTimeline_returnsSavedFrames() throws Exception {
        long gameId = 9000000052L;
        // 手工拼接 JSON：两层 frame，第一层带事件明细，验证全量透传
        String frames = """
                [{"timestamp":1000,"events":[{"type":"CHAMPION_KILL","killerId":1,"victimId":2}]},\
                {"timestamp":2000,"events":[]}]
                """;
        mockMvc.perform(post("/api/matches/{gameId}/timeline", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameId\":" + gameId + ",\"frames\":" + frames + "}"))
                .andExpect(status().isOk());

        // 查询时间线：data 下的结构与推送的 frames 数组一致
        mockMvc.perform(get("/api/matches/{gameId}/timeline", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].timestamp").value(1000))
                .andExpect(jsonPath("$.data[0].events[0].type").value("CHAMPION_KILL"))
                .andExpect(jsonPath("$.data[0].events[0].killerId").value(1))
                .andExpect(jsonPath("$.data[1].timestamp").value(2000));
    }

    /**
     * 用例：不存在的 gameId 返回统一响应（HTTP 200 + 业务码 2002，全局异常处理器契约）
     */
    @Test
    void getTimeline_notFound() throws Exception {
        mockMvc.perform(get("/api/matches/{gameId}/timeline", 9999999999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2002))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("时间线不存在")));
    }

    /**
     * 用例：body 与 path 的 gameId 不一致返回业务码 1002（HTTP 200，契约要求二者一致），
     * 拒绝以 path 为准静默落库造成幂等键与调用方预期不符
     */
    @Test
    void postTimeline_gameIdMismatch_returns1002() throws Exception {
        // path 为 9000000055，body 携带 9000000056：二者不一致
        mockMvc.perform(post("/api/matches/{gameId}/timeline", 9000000055L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameId\":9000000056,\"frames\":[{\"timestamp\":1000}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    /**
     * 用例：body 缺少 frames 字段返回业务码 1001（@NotNull 校验，HTTP 200），
     * 而非落库 NULL 触发 NOT NULL 约束错误
     */
    @Test
    void postTimeline_missingFrames_returns1001() throws Exception {
        mockMvc.perform(post("/api/matches/{gameId}/timeline", 9000000054L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameId\":9000000054}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }
}
