package com.leagueakari.dto.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 车队榜单响应：单一维度的榜单数据
 * <p>dimension 取值：mvp / criminal / feeder / carry / signature / attendance；
 * 时间范围与模式过滤语义与周报共享同一套口径引擎。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaderboardResponse {

    /** 榜单维度 */
    private String dimension;

    /** 统计范围起始（epoch 毫秒，含）；null 表示不限 */
    private Long startMs;

    /** 统计范围结束（epoch 毫秒，不含）；null 表示不限 */
    private Long endMs;

    /** 模式过滤（game_mode 精确匹配）；null 表示全部模式 */
    private String gameMode;

    /** 榜单条目（已按该维度口径排序） */
    private List<WeeklyReportResponse.BoardEntry> entries;
}
