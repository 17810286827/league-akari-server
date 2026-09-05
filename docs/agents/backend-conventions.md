# 后端代码规范（Agent 记忆文档）

> 本文是给实现类 Agent 的**规范速查**，沉淀自既有代码的稳定模式（2026-09-05，源于工单 #33 实现前的调研）。
> 约束性声明的权威来源仍是根目录 `AGENTS.md` 与 `CONTEXT.md`；本文补充"怎么写"的形态细节。
> 每条规范都标注了参考实现，改代码前先看对应参考类。

## 1. 响应契约

- 所有 JSON 接口统一 `{ code, message, data }` 信封（`common/web/ApiResult`），HTTP 一律 200。
- `code=0` 成功；前端判失败只看 `code !== 0`；`data` 为 null 不序列化（`@JsonInclude(NON_NULL)`）。
- **豁免清单**（不包 ApiResult）：
  - SSE 事件流（`produces = MediaType.TEXT_EVENT_STREAM_VALUE`，返回 `SseEmitter`，协议自成契约）
  - `/actuator/health`（Spring 管理）

## 2. 错误码与异常体系

- 错误码**必须先在 `common/exception/ErrorCode` 枚举登记**再使用，段位：
  `1xxx` 请求参数 / `11xx` 车队配置 / `2xxx` 对局域 / `3xxx` 账号域 /
  `4xxx` 外部依赖（`41xx` AI）/ `5xxx` 系统。
- 业务异常统一 `BizException(errorCode)` / `BizException(errorCode, message)` /
  `BizException(errorCode, message, cause)`，由 `common/web/GlobalExceptionHandler` 统一转信封。
- **service 层抛 BizException，controller 不 try-catch**（分层铁律，AGENTS.md）。
- 现有可用错误码速查：`AI_KEY_MISSING(4101)`、`AI_API_ERROR(4102)`、`ROSTER_NOT_CONFIGURED(1101)`、
  `DATA_ASSEMBLY_FAILED(5001)`、`TIMELINE_NOT_FOUND(2002)` 等，新需求优先复用。

## 3. SSE 端点模式（参考：`match/AiAnalysisService` + `controller/MatchController#analyzeMatch`）

controller 层固定五步，**流式业务逻辑全部在 service 层**：

1. `@PostMapping(value = "/xxx", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` 返回 `SseEmitter`
2. 返回 emitter **之前**同步调 `service.validateAndConfigured(...)`——校验失败（如 4101 Key 未配置）
   抛 BizException，走全局处理器返回 JSON 信封（HTTP 200 + code），避免流建立后再中断
3. `new SseEmitter(300_000L)`（5 分钟超时）
4. `onCompletion / onTimeout / onError` 三个生命周期回调打日志；
   `onError` 内先经 `ClientDisconnectDetector.isClientDisconnect(e)` 判定，客户端断开降级 INFO 不刷 ERROR
5. `service.streamXxx(..., emitter)` 内部经 `aiStreamExecutor` 异步执行，controller 立即返回

事件协议（javadoc 约定，载荷用 `Map.of(...)` 直接序列化，**不建 DTO**）：

```
{"type":"start","fromCache":bool}          ← 只推一次，重试不重推
{"type":"chunk","content":"..."}           ← 正文增量（打字机）
{"type":"reasoning","content":"..."}       ← 思维链增量（前端灰字）
{"type":"reasoning-reset"}                 ← 重试前推送，前端清空思维链缓冲
{"type":"done"} / {"type":"done","truncated":true}
{"type":"error","message":"..."}
```

重试门控（ADR 0006 根治结论，勿改动语义）：
- `contentStreamed`（正文已推送）→ **不可重试**（打字机会重复）
- 仅 reasoning 已推送 → 可重试，重试前推 `reasoning-reset`
- 正文为空（思维链耗尽预算形态）恰是最值得重试的失败

service 内部骨架（照抄 `AiAnalysisService#doAnalyzeStream`）：缓存命中 → `start{fromCache:true}` +
全文 chunk + done；未命中 → start{fromCache:false} → `aiClient.callStream(request, systemPrompt,
summary, handler, logContext)` 带 `contentStreamed/reasoningStreamed` 标记的 for 重试环 → 成功缓存 →
done。`send()` 辅助方法把客户端断开翻译成内部 `ClientDisconnectedException`（不推 error 不 complete）。

## 4. AI 调用模式（参考：`ai/AiClient`、`team/WeeklyAiCommentService`）

- 非流式：`aiClient.callWithRetry(...)`，返回 null = 正文为空（调用方决定语义）。
- 流式：`aiClient.callStream(request, systemPrompt, userContent, AiStreamHandler, logContext)`，
  返回 finish_reason（stop/length/null）；回调内异常原样穿透。
- 提示词：classpath `ai/*.md`，`PromptLoader.load(location, builtinFallback)` 加载，
  存在即读（改提示词免重启）、缺失回退内置文案。
- 请求参数：`AiCompletionRequest(model, temperature, freqPenalty, presencePenalty, maxTokens,
  thinking, thinkingBudget)`，全部来自 `AiProperties`（`ai.*` 配置，单一真值，ADR 0004）。
- 场景独立参数键：`max-tokens`（单局）/ `weekly-max-tokens`（周报）/ `post-game-*`（局后）。
- max-tokens 与 thinking-budget 必须按 ADR 0006 三层配套，调值需重新探测网关。

## 5. DTO 与 Lombok 惯例

- 响应 `XxxResponse`（`@Data @Builder @NoArgsConstructor @AllArgsConstructor`）按域分包（`dto/team/` 等）；
  请求 `XxxRequest` + `@Valid`。
- **禁用 record**：不可变值对象 `@Value`（注意已有显式构造器时需补 `@AllArgsConstructor`），
  可变 `@Data`。
- 成员声明顺序：`static final` 常量 → 实例字段 → 构造器 → 实例方法 → 静态方法。
- 注释率 ≥ 20%（中文），关键方法必须有 javadoc；关键业务节点/异常/数据变更打 `@Slf4j` 日志。

## 6. 数据库

- 变更走 Flyway：`src/main/resources/db/migration/V{n}__xxx.sql`，已执行的不可改，只新增；
  每字段中文注释；`map-underscore-to-camel-case` 已开。
- 测试/开发库：`192.168.31.90:3306/league_akari`（虚拟机 MySQL，测试需可达），
  集成测试 `@Transactional` 回滚，gameId 用 9000000201+ 区间隔离。

## 7. 测试惯例

- **纯 Mockito 单测为主**（无 Spring 上下文），测试路径镜像被测文件包结构。
- SSE/异步测试三件套（参考 `match/AiAnalysisServiceTest`）：
  1. 注入 `Runnable::run` 同步执行器保证断言时序
  2. `doAnswer` mock `AiClient.callStream` 回放流（手动调 `handler.onContent/onReasoning`）
  3. `doAnswer` 拦截 `emitter.send` 捕获事件，断言 `type` 序列 `containsExactly`
- 客户端断开用例：send 抛 `AsyncRequestNotUsableException`，断言无 error 事件、
  `never().complete()`，logback `ListAppender` 断言零 ERROR。
- 缓存用例：mock AiClient，第二次调用 `verify(times(1))`。
- 集成测试（参考 `controller/TeamControllerIntegrationTest`）：`@SpringBootTest +
  @AutoConfigureMockMvc + @Transactional`，`@MockBean` 隔离外部 I/O
  （TeamRosterService 固定 roster、RiotMatchHistoryService、AI 服务固定返回值）。

## 8. 前端 SSE 消费模式（league-akari-web，参考：`api/matches.ts#analyzeMatch`）

- SSE 不走 axios（无法增量消费），直接 `fetch(API_BASE_URL + url, { headers: { Accept:
  'text/event-stream' } })` + `response.body.getReader()` 逐行解析 `data:` 行。
- **开流前失败判定**：HTTP 200 但 content-type 非 event-stream → 响应体是 JSON 错误信封，
  解析 `{code, message}` 抛 `ApiError(code, message)`。
- 回调接口形态 `AnalyzeStreamHandlers`（onStart/onChunk/onReasoning/onReasoningReset/onDone/onError），
  未知事件类型忽略（向前兼容）。
- 打字机效果：`onChunk` 里追加到临时变量并同步到 ref，正文 markdown 渲染 `computed` 自动重算；
  reasoning 实时发布不等正文首块。
- 请求竞态：`requestId` 序号 + 模块级 `activeRequests` Map 闸门（参考 `composables/useMatchAnalysis`）。
