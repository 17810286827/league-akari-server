package com.leagueakari.intel;

/**
 * 敌方情报卡渲染接口：输入聚合后的敌方情报，输出 PNG 字节。
 * <p>T3 编排依赖此接口（占位实现打通链路）；T4 用 Java2D 实现真情报卡渲染，
 * T5 合龙时替换占位实现。接口隔离使"链路打通"与"视觉打磨"可并行推进。</p>
 */
public interface IntelCardRenderer {

    /**
     * 渲染敌方情报卡为 PNG 字节
     *
     * @param intel 聚合后的敌方情报（含我方红蓝方、敌方玩家行、开黑分组）
     * @return PNG 字节
     */
    byte[] render(EnemyIntel intel);
}
