package com.leagueakari.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 分页响应，与规格第 4.2 节契约一致：{ data, page, pageSize, total }
 * <p>data 为当前页记录列表，page/pageSize 回显请求参数，total 为满足条件的总条数。</p>
 */
@Data
@AllArgsConstructor
public class PageResponse<T> {

    /** 当前页记录列表 */
    private List<T> data;

    /** 当前页码，从 1 开始 */
    private long page;

    /** 每页条数 */
    private long pageSize;

    /** 满足筛选条件的总条数 */
    private long total;
}
