package com.leagueakari.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leagueakari.entity.MatchTimeline;
import com.leagueakari.mapper.MatchTimelineMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MatchTimelineService 时间线服务集成测试
 * <p>验证两条核心契约：
 * 1. 同一 gameId 重复推送时幂等跳过，库中仅保留首次写入内容；
 * 2. 查询不存在的 gameId 返回 null（由 controller 层转 404）。</p>
 * <p>说明：@Transactional 使每个用例结束后回滚，不污染数据库；
 * 幂等用例依赖同事务内对插入数据的可见性，Spring 事务默认传播下成立。</p>
 */
@SpringBootTest
@Transactional
class MatchTimelineServiceTest {

    @Autowired
    private MatchTimelineService service;

    @Autowired
    private MatchTimelineMapper matchTimelineMapper;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 用例：同一 gameId 重复推送，库中应仅一条记录且内容为首次写入的 frames
     */
    @Test
    void 重复写入同一gameId幂等跳过() throws Exception {
        service.saveTimeline(12345L, List.of(Map.of("timestamp", 1000)));
        service.saveTimeline(12345L, List.of(Map.of("timestamp", 2000))); // 重复推送

        // 断言库中仅一条且为首次写入内容：结构对比对 MySQL JSON 列的空白规范化鲁棒
        List<MatchTimeline> rows = matchTimelineMapper.selectList(
                new QueryWrapper<MatchTimeline>().eq("game_id", 12345L));
        assertThat(rows).hasSize(1);
        assertThat(objectMapper.readValue(rows.get(0).getFramesJson(), List.class))
                .isEqualTo(List.of(Map.of("timestamp", 1000)));
    }

    /**
     * 用例：查询不存在的 gameId 应返回 null，不抛异常
     */
    @Test
    void 查询不存在返回null() {
        assertNull(service.getTimeline(999999L));
    }

    /**
     * 用例：写入后可查询到解析后的 frames 原始结构（JSON 往返保持一致）
     */
    @Test
    void 写入后可查询到原样frames() {
        Object frames = List.of(Map.of("timestamp", 1000), Map.of("timestamp", 2000));
        service.saveTimeline(12346L, frames);

        // 查询结果与写入内容一致（Map/List 内容等价）
        assertThat(service.getTimeline(12346L)).isEqualTo(frames);
    }
}
