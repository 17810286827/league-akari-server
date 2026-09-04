# 0005. AI 模型调用逻辑统一到公共 AiClient

- 状态：已采纳（accepted）
- 日期：2026-09-04
- 关联：[0004-ai-config-single-source](./0004-ai-config-single-source.md)（配置统一）、GitHub Issue #8

## 背景与问题

ADR-0004 统一了 AI 调用的**配置**（base-url、api-key、model、采样参数收口到
`application.yml` + `AiProperties`），但**调用逻辑**仍然三处各自手写：

| 场景 | 服务 | 方式 |
| --- | --- | --- |
| 单局 AI 分析（self 视角） | AiAnalysisService | 流式 SSE（stream=true），含思维链透传 |
| 周报锐评 | WeeklyAiCommentService | 非流式 |
| 局后播报锐评 | PostGameCommentService | 非流式（与周报版几乎逐行重复） |

三处各自维护浏览器 User-Agent 常量（Cloudflare 防护所需）、各自拼 payload、
各自设 Bearer 请求头、各自解析响应。任何网关行为变化（请求头要求、payload 格式、
SSE 事件格式）都要改三个地方，且三处已经出现实际漂移（thinking 参数一处条件判断
两处写死、penalty 只有流式传、空正文一处抛异常两处返回 null）。

## 决策

新建独立 `com.leagueakari.ai` 包（仿照 qqbot 包"按外部系统分包"的先例），
公共组件 `AiClient` 同时提供非流式 `call()` 与流式 `callStream()`，三个生产服务全部迁移。

关键设计取舍：

1. **AiClient 是纯 HTTP 接缝，不感知业务场景**。只管连接级事务：URL 拼接、Bearer
   鉴权、浏览器 UA、payload 组装、HTTP 错误转 `IllegalStateException`、响应/SSE 解析。
   采样参数（temperature、penalty、maxTokens、thinking、model）由调用方经
   `AiCompletionRequest` 显式组装传入；各业务服务继续从 `AiProperties` 读自己那几个键。
   配置键与 `AiProperties` 不动，`AiPropertiesTest` 契约测试零改动。
2. **流式采用回调式 handler**（`AiStreamHandler.onContent/onReasoning`），而非反应式流。
   与现有 HttpClient5 + `aiStreamExecutor` 同步阻塞线程池架构完全兼容，不引入 Reactor
   依赖；回调内抛出的异常（客户端断开信号）原样穿透，保持"断开即停上游消费"语义。
3. **错误语义零变化**：HTTP 非 200 统一抛 `IllegalStateException`；非流式空正文返回
   null；空正文重试（最多 2 次）、JVM 缓存（各场景不同 TTL）、提示词 classpath 加载
   与缺失回退全部留在业务服务层——它们是业务策略，不进公共层。
4. **penalty 为 null 时不进 payload**：周报/局后场景原本就不传 penalty，用"可空参数"
   表达场景差异，避免迁移时采样行为漂移。

## 被否决的备选方案

- **反应式流（Flux）**：需引入 Reactor/WebFlux 依赖，与现有同步阻塞架构冲突，
  迁移面远超收益。
- **AiClient 读场景级配置键**（ai.max-tokens / weekly-max-tokens / post-game-max-tokens）：
  调用更简洁，但公共组件开始感知"周报/局后"业务概念，职责边界腐化。
- **重试进公共层**：重试次数、日志前缀是业务策略，公共层会重新耦合场景差异。
- **只抽非流式两处**：改动最小，但流式调用继续单独漂移，问题只解决一半。

## 后果

- 正面：网关行为变化只改一处；HTTP 细节断言集中到 `AiClientTest`（15 用例），
  服务测试瘦身为纯业务用例（mock AiClient）；新 AI 场景第一天起就有现成调用组件。
- 负面：三个服务的构造签名变化（`CloseableHttpClient` 参数被 `AiClient` 取代），
  相关测试需同步调整；多了一层间接（服务 → AiClient → HttpClient）。
- 语义保持：浏览器 UA、`disableAutomaticRetries`、300s 读超时、空正文 null +
  业务层重试、finish_reason=length 截断检测、思维链与正文分流，全部原样保留。
- 本地探测工具 `AiModelProbeTest` 维持手写实现不迁移（gitignored 联调工具，
  其硬编码 API Key 问题另行处理）。
