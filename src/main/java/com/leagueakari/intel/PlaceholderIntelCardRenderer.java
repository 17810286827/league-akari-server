package com.leagueakari.intel;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 敌方情报卡渲染占位实现：T3 打通链路用，输出非法的"占位字节"而非真 PNG。
 * <p>仅用于"开始信号 → 锚定 → 聚合 → 发群"链路验证（真情报卡由 T4 的 Java2D 实现
 * 提供，T5 合龙时替换本类为真实现，见 docs/adr/0011）。</p>
 */
@Component
public class PlaceholderIntelCardRenderer implements IntelCardRenderer {

    /** 占位字节前缀（非 PNG，仅标识链路已走通） */
    private static final String PLACEHOLDER = "PLACEHOLDER-INTEL-CARD";

    @Override
    public byte[] render(EnemyIntel intel) {
        return (PLACEHOLDER + ":" + intel.getEnemies().size() + "-enemies")
                .getBytes(StandardCharsets.UTF_8);
    }
}
