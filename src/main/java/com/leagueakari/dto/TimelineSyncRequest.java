package com.leagueakari.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 时间线同步入参：frames 为原始全量数组，以 Object 接收以避免字段级校验破坏全量透传
 */
@Data
public class TimelineSyncRequest {

    @NotNull(message = "gameId 不能为空")
    private Long gameId;

    /** frames 数组全量（Object 原样接收，序列化后整体存入 frames_json） */
    @NotNull(message = "frames 不能为空")
    private Object frames;
}
