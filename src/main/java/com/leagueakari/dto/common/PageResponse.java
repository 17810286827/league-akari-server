package com.leagueakari.dto.common;

import com.leagueakari.dto.match.MatchSummaryResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 分页响应：{ items, page, pageSize, total }，在统一响应对象中作为 data 输出。
 * <p>items 为当前页记录列表（原字段名 data，统一信封后更名避免 data.data 嵌套歧义），
 * page/pageSize 回显请求参数，total 为满足条件的总条数。
 * recentOpponents 为列表页聚合的"最近对手"（仅 matches 列表接口填充，其余接口为 null 不输出）。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {

    /** 当前页记录列表 */
    private List<T> items;

    /** 当前页码，从 1 开始 */
    private long page;

    /** 每页条数 */
    private long pageSize;

    /** 满足筛选条件的总条数 */
    private long total;

    /** 最近对手：本页对局中非 self 队玩家按出现次数聚合前 5（列表查询时即返回，无需展开详情） */
    private List<MatchSummaryResponse.RecentOpponent> recentOpponents;

    public PageResponse(List<T> items, long page, long pageSize, long total) {
        this.items = items;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
    }
}
