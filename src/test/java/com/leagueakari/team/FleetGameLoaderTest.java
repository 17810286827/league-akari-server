package com.leagueakari.team;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FleetGameLoader 静态纯函数测试：自然周边界计算
 * <p>断言数值与拆分前 TeamStatsServiceTest 逐字一致。</p>
 */
class FleetGameLoaderTest {

    /** 测试时区与周口径一致：Asia/Shanghai */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 用例：周三入参返回该自然周（周一 00:00 ~ 次周一 00:00，+08:00）的 epoch 毫秒 */
    @Test
    void weekRange_coversMondayToSunday() {
        LocalDate wednesday = LocalDate.of(2026, 8, 26);
        FleetGameLoader.WeekRange range = FleetGameLoader.weekRange(wednesday, ZONE);

        // 独立真值：直接用 ZonedDateTime 计算期望边界
        long expectedStart = ZonedDateTime.of(2026, 8, 24, 0, 0, 0, 0, ZONE).toInstant().toEpochMilli();
        long expectedEnd = ZonedDateTime.of(2026, 8, 31, 0, 0, 0, 0, ZONE).toInstant().toEpochMilli();
        assertThat(range.getStartMs()).isEqualTo(expectedStart);
        assertThat(range.getEndMs()).isEqualTo(expectedEnd);
        assertThat(range.getMonday()).isEqualTo(LocalDate.of(2026, 8, 24));
    }
}
