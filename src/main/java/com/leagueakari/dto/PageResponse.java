package com.leagueakari.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 分页响应，与规格第 4.2 节契约一致：{ data, page, pageSize, total }
 * <p>data 为当前页记录列表，page/pageSize 回显请求参数，total 为满足条件的总条数。
 * recentOpponents 为列表页聚合的"最近对手"（仅 matches 列表接口填充，其余接口为 null 不输出）。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {

    /** 当前页记录列表 */
    private List<T> data;

    /** 当前页码，从 1 开始 */
    private long page;

    /** 每页条数 */
    private long pageSize;

    /** 满足筛选条件的总条数 */
    private long total;

    /** 最近对手：本页对局中非 self 队玩家按出现次数聚合前 5（列表查询时即返回，无需展开详情） */
    private List<MatchSummaryResponse.RecentOpponent> recentOpponents;

    public PageResponse(List<T> data, long page, long pageSize, long total) {
        this.data = data;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
    }
}
