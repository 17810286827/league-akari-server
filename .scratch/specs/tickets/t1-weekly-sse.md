## Parent

#28（spec: 周报 AI 锐评流式化 + 历史周持久化）

## What to build

车队成员打开周报页时立即看到总览、七榜单与名场面（统计数据照旧同步返回），AI 锐评区域以打字机效果逐字流式出现。AI 生成失败时锐评区域显示明确错误提示，周报其他部分不受影响。模型输出思维链时前端可见灰字推理过程。锐评 SSE 事件契约与单局 AI 分析完全一致（start/chunk/reasoning/done/error，含正文为空自动重试与 reasoning-reset），前端可复用既有 SSE 消费逻辑。

本票不含持久化缓存：每次请求实时生成（缓存与历史周落库是下一张票）。

## Acceptance criteria

- [ ] 周报统计接口返回内容不变（不含等待 AI）
- [ ] 新增周报锐评独立 SSE 端点，事件契约与单局 AI 分析一致，走 SSE 信封豁免约定
- [ ] Web 周报页先渲染统计，锐评区域流式打字机渲染
- [ ] AI 失败时锐评区域显示错误提示，榜单/名场面不受影响
- [ ] reasoning 事件在前端以灰字折叠区展示（有则显示）
- [ ] controller 只做参数校验与 SSE 装配，生成逻辑在 service 层（BizException 语义）
- [ ] TDD：SSE 契约测试（事件序列/error/超时）先行，先例为单局 AI 分析 SSE 集成测试
- [ ] 关键路径日志：锐评生成开始/完成/失败可追踪

## Blocked by

None (can start immediately).
