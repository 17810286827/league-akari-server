## Parent

#29（spec: 时间线复盘系统——规则转折点 + AI 叙述 + 回填补拉时间线）

## What to build

维护者运行 backfill 时，已入库但缺时间线数据的对局被自动补拉 Riot MATCH-V5 timeline 接口，写入走既有时间线入库管道（幂等键 game_id）。补拉前必须先做 Riot timeline 帧格式与桌面端 LCU/SGP 帧格式的比对，不兼容处做转换适配，保证两源数据对下游消费端（Web 曲线/地图组件）格式统一。已有时间线的对局不重复拉取。整个过程受既有 Riot 滚动窗口限流保护。

跑一次 backfill 后，历史回填的对局即拥有时间线数据，可被复盘功能覆盖。

## Acceptance criteria

- [ ] backfill 流程识别"已入库但缺时间线"的对局并补拉 MATCH-V5 timeline
- [ ] 已有时间线的对局零重复调用
- [ ] Riot 帧格式与 LCU/SGP 帧格式差异有书面比对结论（记录在 PR/issue 评论），不兼容处经转换适配后下游格式统一
- [ ] 补拉写入复用既有时间线入库管道，幂等键 game_id 不变
- [ ] 限流窗口生效：补拉调用纳入既有 Riot 限流预算
- [ ] TDD：mock Riot HTTP 响应的补拉/跳过/限流测试，先例为 Riot 客户端与限流测试
- [ ] 帧格式转换有黄金样本测试（Riot 样本 → 转换结果与 LCU/SGP 结构一致）
- [ ] 补拉成功/跳过/失败有日志可追踪

## Blocked by

None (can start immediately).
