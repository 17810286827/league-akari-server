# 0006. 思考模式网关实测结论与配置诚实化

- 状态：已采纳（accepted）
- 日期：2026-09-05
- 关联：[0004-ai-config-single-source](./0004-ai-config-single-source.md)（ai.thinking 键的由来）、
  [0005-ai-client-unified-invocation](./0005-ai-client-unified-invocation.md)（thinking 参数透传实现）

> **更新记录（2026-09-05 晚）**：同日稍后网关与模型整体切换（`yt.19851117.xyz`/gemini-2.5-flash →
> `pianyitoken.gay`/deepseek-v4-flash，动因：旧网关已无 deepseek-v4-flash、新网关无
> gemini-2.5-flash）。新组合实测思考模式**真实可用**（UTF-8 请求体下 reasoning_content
> 正常流出、4096 预算内正文完整；thinking=false 亦能有效压制），故 `ai.thinking` 重新置
> **true**，`post-game-model` 一并切至 deepseek-v4-flash。本 ADR 的核心教训不变：
> **换模型/网关必须先跑思考参数探测（探测矩阵可复用），不能假设参数生效**——本 ADR
> 主体（gemini 静默忽略思考参数的实证）依然有效，是"配置值必须反映真实行为"的决策依据。
> 排障提示：curl 内联中文请求体在 Windows Git Bash 下按本地编码发送（模型收到乱码），
> 会复现"思维链耗尽预算"假象；探测必须用 UTF-8 文件方式发送（`-d @file`）。

> **更新记录 2（2026-09-05 深夜）：思维链耗尽预算生产故障的根治**。生产重现
> （gameId=442534037：reasoning=4086、chars=0、finish=length，"AI 返回内容为空"）。
> 复现环路：真实 system-prompt + 生产参数直连网关（4 轮 2 红，50% 复现率）。根因有二：
> 1. **预算不足**（主因）：该提示词任务（4 个颁奖 + 虎扑文风）健康思维链可达 6000+ 字符，
>    4096 token 预算下被硬截断，正文为空——红样本思维链内容层层推进（分析→权衡→草稿），
>    分段重复率 4-10%，是正常思考而非病态复读（penalty 对照组证实惩罚非主因）。
> 2. **重试门控缺陷**（放大器）：`onReasoning` 置 `streamed=true` 阻断重试——思维链已推送
>    后正文为空直接失败，而这恰是最值得重试的失败形态。
> 修复三层：`max-tokens` 4096→8192（红率 50%→10%）；新增 `ai.thinking-budget=2048`
> （`chat_template_kwargs.thinking_budget`，实测 0/16 失败；**3072 值下网关约束失效 4/16**，
> 该参数执行依值而变，调值必须重新探测）；重试门控改为"仅正文已推送才不可重试"，重试前推
> `reasoning-reset` 事件（前端清空思维链缓冲）。约束：thinking_budget 单独使用时上限可能
> 被网关无视（单独 budget=2048 无 max-tokens 提升时 2/6 红），必须三层配套。

## 背景与问题

`ai.thinking` 配置（ADR-0004 引入）在三个 AI 场景统一控制模型思考模式：
`true` = 期望模型先输出长思维链再出正文（前端灰字展示推理过程），
`false` = 直出正文。参数经请求体 `chat_template_kwargs.thinking` 透传网关。

2026-09-05 用户反馈"思考模式没有生效"（前端看不到思考过程）。此时模型已切换为
`gemini-2.5-flash`（曾用 mimo-v2.5 / deepseek-v4-flash，deepseek 系时期思考模式确实可用）。

## 实测证据（2026-09-05，直连网关 `https://yt.19851117.xyz/v1`）

针对 `gemini-2.5-flash` 通道的探测矩阵（流式 + 非流式、简单题 + 诱导思考的难题）：

| 参数风格 | 请求 | reasoning_content 输出 |
| --- | --- | --- |
| 不传参数（thinking=true 的现状路径） | baseline | 无 |
| DeepSeek 风格 | `chat_template_kwargs.thinking=true` | 无 |
| OpenAI 风格 | `reasoning_effort=medium` | 无 |
| Qwen 风格 | `enable_thinking=true` | 无 |

补充观察：

- 全部参数风格**均不报错**（静默忽略），HTTP 200 正常返回正文；
- 流式响应连 usage 统计块都没有，非流式 `completion_tokens` 与正文长度一致（无隐藏思考 token 消耗）；
- 响应 id 形如 `msg_01h54RgSo4etndoBj5nZkftU`（Anthropic 风格 ULID，非 OpenAI `chatcmpl-` 前缀），
  该通道背后疑似非原生 Gemini 转发，思考通道在网关层被剥离；
- 旁证：诱导思考的经典难题（球棒与球）模型抢答了直觉错误答案，确无推理发生。

**结论：`ai.thinking` 对当前网关的 gemini-2.5-flash 是彻底 no-op（true/false 行为完全一致）。**

## 决策

1. **`ai.thinking` 固定为 `false`**，注释如实记录实测结论与本 ADR 指针。
   配置值必须反映真实行为——"开着但无效"的 true 会让排障者（和未来的自己）
   误以为思考通道存在。`AiPropertiesTest` 契约断言同步为 false（该测试的定位就是
   "yml 当前值即生产意图"，见 ADR-0004）。
2. **AiClient 逻辑不动**：`thinking=false` 时仍发送 `chat_template_kwargs.thinking=false`
   （对 gemini 无害；换回 deepseek 系模型时立即重新生效），`true` 时不发参数。
3. **前端零改动**：`MatchCardDetails.vue` 的思考折叠区已有 `v-if="reasoning"` 守卫，
   无 reasoning 事件时整块不渲染，"接受无思考"方向下无需任何前端变更。
4. **换模型时的重启开关**：若换回已知支持思考参数的模型（如 deepseek 系），
   把 `ai.thinking` 改回 `true` 即可——这正是 ADR-0004 "yaml 唯一真值"设计的目的。

## Considered Options

- **按模型名适配思考参数风格（模型→参数映射表）** → 否决——三种风格实测全部无效，
  适配层无从适配；且网关行为随模型/上游变化（yml 已记录 mimo-v2.5 无视
  `thinking=false` 的历史教训），映射表维护成本高、验证成本更高
- **换回 deepseek-v4-flash 恢复思考** → 否决——模型选择是独立决策（速度/质量权衡，
  见 application.yml model 键注释），不应被思考功能绑架；且 deepseek 时期实测
  开思考整流约 90s（关约 25s），慢 3.6 倍
- **保持 true 等网关未来支持** → 否决——"配置说开着、行为是关着"正是本次
  用户困惑的根源；未来网关支持时改一行 yml 即可，无需提前"占位"

## Consequences

- 单局 AI 分析不再有"🧠 模型思考过程"折叠区（本来也从未在此模型下出现过）；
- 周报/局后锐评的"空正文自动重试"（推理模型把预算耗在思维链的场景）失效概率降低，
  但 retry-count=3 保留作为通用网络/生成失败兜底；
- 新模型上线时**必须**先跑 `AiModelProbeTest` 风格的思考参数探测（本 ADR 的探测
  矩阵可直接复用），确认思考通道真实可用后再改 `ai.thinking`，避免再次"静默无效"。
