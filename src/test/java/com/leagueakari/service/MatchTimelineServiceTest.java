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
 * 幂等用例依赖同事务内对插入数据的可见性，Spring 事务默认传播下成立。
 * 测试专用 gameId 取 9000000101 起保留区间（与 controller 集成测试的
 * 9000000051~9000000053 区间错开），并在每个写入用例开头断言库中无残留，
 * 防止历史残留数据架空幂等断言。</p>
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
     * <p>用例开头先断言库中无该 gameId 残留，保证幂等断言不被历史数据架空
     * （防止早期测试未回滚导致的残留再次污染真实库）。</p>
     */
    @Test
    void 重复写入同一gameId幂等跳过() throws Exception {
        long gameId = 9000000101L;
        // 前置守卫：保留区间 gameId 在库中必须为空，残留时立即失败而非"碰巧通过"
        assertNoResidue(gameId);

        service.saveTimeline(gameId, List.of(Map.of("timestamp", 1000)));
        service.saveTimeline(gameId, List.of(Map.of("timestamp", 2000))); // 重复推送

        // 断言库中仅一条且为首次写入内容：结构对比对 MySQL JSON 列的空白规范化鲁棒
        List<MatchTimeline> rows = matchTimelineMapper.selectList(
                new QueryWrapper<MatchTimeline>().eq("game_id", gameId));
        assertThat(rows).hasSize(1);
        assertThat(objectMapper.readValue(rows.get(0).getFramesJson(), List.class))
                .isEqualTo(List.of(Map.of("timestamp", 1000)));
    }

    /**
     * 用例：查询不存在的 gameId 应返回 null，不抛异常
     */
    @Test
    void 查询不存在返回null() {
        assertNull(service.getTimeline(9000000103L));
    }

    /**
     * 用例：写入后可查询到解析后的 frames 原始结构（JSON 往返保持一致）
     */
    @Test
    void 写入后可查询到原样frames() {
        long gameId = 9000000102L;
        // 前置守卫：与幂等用例同理，防止残留数据导致断言失真
        assertNoResidue(gameId);

        Object frames = List.of(Map.of("timestamp", 1000), Map.of("timestamp", 2000));
        service.saveTimeline(gameId, frames);

        // 查询结果与写入内容一致（Map/List 内容等价）
        assertThat(service.getTimeline(gameId)).isEqualTo(frames);
    }

    /**
     * 前置守卫：断言指定 gameId 在库中无任何记录。
     * <p>若存在历史残留（早期测试未回滚写入），后续"仅一条"的幂等断言
     * 会被架空为"碰巧通过"，此处显式失败以暴露残留。</p>
     */
    private void assertNoResidue(long gameId) {
        List<MatchTimeline> rows = matchTimelineMapper.selectList(
                new QueryWrapper<MatchTimeline>().eq("game_id", gameId));
        assertThat(rows).as("测试专用 gameId=%d 存在历史残留，请先清理", gameId).isEmpty();
    }
}
