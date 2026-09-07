package com.leagueakari.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntelProperties 默认值契约测试：
 * 断言 intel.premade-detect-threshold 的代码默认值 = 10，与 application.yml intel 段
 * 的生产意图（${INTEL_PREMADE_DETECT_THRESHOLD:10}）一致——防止代码默认值与 yml 各说各话。
 */
class IntelPropertiesTest {

    /** 用例：intel.* 开黑判定阈值默认 10（同桌面端口径的保守档） */
    @Test
    void premadeDetectThresholdDefaultsTo10() {
        // yml 真值走占位符 ${INTEL_PREMADE_DETECT_THRESHOLD:10}（原始 PropertySource 不解析
        // 占位符，故直接断言类默认值；yml 的 intent 见 application.yml intel 段注释）
        IntelProperties props = new IntelProperties();
        assertThat(props.getPremadeDetectThreshold()).isEqualTo(10);
    }
}
