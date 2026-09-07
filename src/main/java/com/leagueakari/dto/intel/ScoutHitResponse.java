package com.leagueakari.dto.intel;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 侦察缓存命中查询入参/出参：给定一批 puuid，返回哪些人的历史已在缓存内。
 * <p>桌面端据此减少重复喂量；T3 发卡前据此判断敌方哪些人有历史可判开黑。</p>
 */
@Data
public class ScoutHitResponse {

    /** 命中映射：puuid → 是否在缓存内有历史摘要行（true 有 / false 无） */
    private Map<String, Boolean> hitMap;

    public ScoutHitResponse(Map<String, Boolean> hitMap) {
        this.hitMap = hitMap;
    }

    /** 查询入参（独立类承载 @Valid 校验） */
    @Data
    public static class Request {
        /** 待查询的玩家标识列表 */
        @NotEmpty(message = "puuids 不能为空")
        private List<@NotNull(message = "puuid 不能为空") String> puuids;
    }
}
