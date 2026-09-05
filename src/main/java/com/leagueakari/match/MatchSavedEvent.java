package com.leagueakari.match;

/**
 * 对局已同步事件：对局落库事务内发布，事务提交后（AFTER_COMMIT）由局后播报协调器消费。
 * <p>发布语义：<b>每次同步都发布</b>（含幂等跳过的重复推送）——是否播报由推送状态机
 * 内部判定（状态/开关/时间窗/车队局门控），"每次同步都触发判定"正是桌面端补推重试
 * 的兜底通道。事务回滚时事件随事务丢弃（不会基于未提交数据触发播报）。</p>
 * <p>解耦语义：发布方（match 包）与消费方（broadcast 包）仅通过本事件关联，
 * controller 不再承载"落库后触发播报"的编排。</p>
 *
 * @param gameId 本次同步的对局 ID（幂等键）
 */
public record MatchSavedEvent(Long gameId) {
}
