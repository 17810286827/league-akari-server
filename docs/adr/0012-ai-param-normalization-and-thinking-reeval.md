# 0012. AI 参数归一与思考模式网关再实测（推翻 0006-更新记录 2）

- 状态：已采纳（accepted）
- 日期：2026-09-07
- 关联：[0004-ai-config-single-source](./0004-ai-config-single-source.md)（yaml 唯一真值原则）、
  [0006-thinking-mode-gateway-probe.md](./0006-thinking-mode-gateway-probe.md)（前次实测结论，本 ADR 部分推翻）、
  [0005-ai-client-unified-invocation](./0005-ai-client-unified-invocation.md)（AiClient 参数透传）

## 背景与问题

两个问题在同一天暴露：

1. **配置漂移已实际发生**：`ai.max-tokens` / `ai.weekly-max-tokens` / `ai.post-game-max-tokens`
   三键 yml 值全部是 16384，但 `AiPropertiesTest` 契约断言仍是 4096 / 2048——
   契约测试早已失红却无人发现（本地长期不跑全量测试），三个键自创建以来从未分叉过。
2. **思考模式"实测有效"的结论可疑**：0006 更新记录 2 称
   `chat_template_kwargs.thinking_budget=2048` 实测 0/16 失败，但生产日志反复出现
   thinking 开启时 reasoning 73 块、正文 111 字符、`finish_reason=length` 的空正文——
   如果预算约束真实生效，正文不该被思维链挤空。

## 决策与实测证据

### 一、参数归一：场景级键删除

- 删除 `ai.weekly-max-tokens`、`ai.post-game-max-tokens`、`ai.post-game-model`：
  四个场景（单局分析/复盘叙述/周报锐评/局后锐评）统一读 `ai.model` + `ai.max-tokens`。
- 依据：三值从未分叉 + 漂移实据（契约断言 4096/2048 vs yml 16384）。
  "场景级键"在无真实差异化需求时只是漂移温床；将来真要分场景再加回覆盖键，成本极低。
- `AiProperties` 同步删除字段；`AiPropertiesTest` 契约改为逐项断言统一值。

### 二、思考参数：30 次真实调用推翻前次结论

用本地探测工具（`AiThinkingEffortProbeTest`，复用 0006 的探测矩阵方法论，
但**直连流式读 reasoning_content 长度**而非只看失败率）4 轮实测：

| 参数路线 | 实测结果 | 证据 |
|---|---|---|
| `chat_template_kwargs: {thinking: false}` | **不执行** | 6 次全关对照全部有 reasoning 输出（40~722 字） |
| `chat_template_kwargs: {thinking_budget: 64/512/1024/2048}` | **不执行** | 各档位 reasoning 长度与基线无差异，纯模型随机 |
| DeepSeek 官方嵌套 `thinking: {type: "disabled"}` | **真实执行** | 3 连测 reasoning 全为 0 字 |
| 官方嵌套 `thinking: {reasoning_effort: "low"}` | 接受但效果不可证 | 短提示词下 low 与 high 的 reasoning 长度与噪音不可区分 |

结论：**该网关（pianyitoken.gay，OpenAI 兼容层）只透传 DeepSeek 官方 API 的嵌套 thinking
参数，vLLM 部署式 chat_template_kwargs 全系静默忽略**。0006 更新记录 2 中"budget=2048
实测 0/16 失败"应重新解读为巧合（未控制变量，样本量不足以穿透噪音）——budget 从未生效，
当时空正文红率下降实为 max-tokens 4096→8192 单变量贡献。

### 三、落地参数

- `AiClient.buildPayload` 改发官方嵌套参数：enabled 时可选带 `reasoning_effort`
  （新键 `ai.thinking-effort`，空 = 不传），disabled 直出正文。
- `ai.thinking: false`（全局关闭）：关闭后首 token 约 2s（开启时 2.5~7s 波动），
  四场景都是"锐评/叙述"型生成任务，质量风险可控。
- 删除 `ai.thinking-budget` 键（网关不执行，留着误导后人）。

## 影响

- AiClient / AiProperties / 四个业务服务构造点 / application.yml / 3 个测试文件同步修改；
  后端 339 测试全绿。
- 部署注意：`.env` 无需新增变量（`AI_THINKING_EFFORT` 占位符空即不传）。
- 后续换网关/模型时，思考参数探测**必须**复用 `AiThinkingEffortProbeTest` 模式：
  流式直读 reasoning 长度 + 多轮对照，只看失败率会被噪音骗（0006 的教训的教训）。
