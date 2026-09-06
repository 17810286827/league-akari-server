package com.leagueakari.common.version;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GameVersionNormalizer 单元测试（工单 #38：版本筛选）：
 * game_version 原始值 → 主版本的归一口径（全站单点定义，黄金样本）。
 * LCU/SGP 原始格式如 "16.15.802.4387"，Riot 回填如 "15.16.802.1234"——
 * 主版本取前两段（大版本.小版本，对应一个游戏平衡补丁周期）。
 */
class GameVersionNormalizerTest {

    /** 黄金样本：常见原始格式 → 主版本 */
    @ParameterizedTest
    @CsvSource({
            "'16.15.802.4387', '16.15'",
            "'15.16.802.1234', '15.16'",
            "'16.1.1.1',       '16.1'",
            "'14.23.999.9999', '14.23'"
    })
    void normalize_extractsMajorMinor(String raw, String expected) {
        assertThat(GameVersionNormalizer.normalize(raw)).isEqualTo(expected);
    }

    /** 只有两段时原样返回（已是主版本形态） */
    @Test
    void normalize_twoSegmentsUnchanged() {
        assertThat(GameVersionNormalizer.normalize("16.15")).isEqualTo("16.15");
    }

    /** 一段时原样返回（异常兜底，不抛异常） */
    @Test
    void normalize_singleSegmentUnchanged() {
        assertThat(GameVersionNormalizer.normalize("16")).isEqualTo("16");
    }

    /** null/空/空白 → null（无版本信息的旧数据，筛选时跳过） */
    @ParameterizedTest
    @NullAndEmptySource
    void normalize_blankReturnsNull(String raw) {
        assertThat(GameVersionNormalizer.normalize(raw)).isNull();
    }

    /** 未知格式（无点分隔）→ 原样返回（不猜格式，可追溯） */
    @Test
    void normalize_unknownFormatUnchanged() {
        assertThat(GameVersionNormalizer.normalize("weird")).isEqualTo("weird");
    }
}
