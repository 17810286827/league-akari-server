# 0004-ai-config-single-source.md

# AI 模型配置统一为 yaml 唯一真值 + AiProperties 类型化绑定（否决运行时热切换）

## Status

accepted

## Context

AI 大模型配置（`ai.*`：模型名、网关地址、采样参数、各场景输出上限）散落为五种形态且互相漂移：

- `application.yml` 的 `ai.*` 段是运行时真值，但第 83 行注释写"分析模型：deepseek-v4-flash"，实际值却是 `mimo-v2.5`；
- 三个 AI 服务（`AiAnalysisService` / `WeeklyAiCommentService` / `PostGameCommentService`）各自用 8 个 `@Value` 注入同一批键，默认值互不相同（`ai.model` 在 yml=analysis 场景、`AiAnalysisService` 默认 `deepseek-v4-flash`、`WeeklyAiCommentService` 默认 `mimo-v2.5`；temperature 有 0.7 与 1.0 两版；`PostGameCommentService` 用嵌套回退 `${ai.post-game-model:${ai.model:mimo-v2.5}}`）；
- `ai.weekly-prompt-file` 只存在于代码默认值，yml 从未声明——改文件却不知道真值在哪；
- `AiModelProbeTest`（本地工具）与 9 处 Java 注释把模型名当文档写死，随配置演进必然过期。

同时，team/push/scoring 三段都已有 `@ConfigurationProperties` 类（`TeamProperties`/`PushProperties`/`ScoringConfig`），AI 是唯一没有 Properties 类的段——重复字段定义与默认值漂移由此而来。

部署现实：`application.yml` 打进镜像，生产改配置 = 改源码 → GitHub Actions 构建推阿里云 → ssh 部署（分钟级）。这使"切换模型实验"成本高，曾诱发热切换方案（DB 覆盖层 / 管理接口 / Spring Cloud RefreshScope）以绕开部署管线。

## Decision

1. **yaml 唯一真值**：`ai.*` 键（含部署环境变量 `AI_API_KEY` 覆盖）是 AI 配置的唯一来源。Java 侧**不设任何默认值**——键缺失时由 `@Validated` 校验在启动阶段直接失败，杜绝"代码默认值与 yml 各说各话"的静默默认（历史漂移即源于 @Value 默认值）。
2. **新增 `AiProperties`**（`config/AiProperties.java`，`@ConfigurationProperties(prefix = "ai")`，与 team/push/scoring 同模式）：14 个字段一一对应 yml 键，必填项带 `@NotBlank`/`@NotNull`，`api-key` 允许为空（沿用"未配置即快速失败返回明确错误"的降级语义）。三个 AI 服务删除各自的 @Value 与重复字段声明路径，构造注入同一 Bean。
3. **场景分工保留双键**：`ai.model` 供单局 AI 分析与周报锐评共用；`ai.post-game-model` 是局后播报独立键（播报对延迟敏感，历史上因 mimo-v2.5 无视 `thinking=false` 仍先推理 ~60s 而拆分），删除嵌套回退表达式，键缺失直接启动失败而非层层兜底。
4. **分析模型回归 `deepseek-v4-flash`**（行为变更，有意为之）：yml 值此前被实验性切到 `mimo-v2.5` 而注释/代码默认值仍指向 deepseek——按注释体现的真实意图统一为 deepseek-v4-flash，post-game-model 不变。
5. **补 `ai.weekly-prompt-file` 进 yml**：此前仅存在于代码默认值，现显式声明，yml 成为键的完整清单。
6. **契约测试锁定**：`AiPropertiesTest` 直接绑定真实 `application.yml` 逐字段断言当前值即意图；新增/调整任何 `ai.*` 键必须同步该测试（防漂移回归网）。
7. 模型名只允许出现在 yml（值与解释性注释）；Java 注释/测试常量不再写死模型名。`AiModelProbeTest`（gitignored 本地工具）保持本地可编辑形态，其探测清单与新 `ai.model` 一致。

## Considered Options

- **DB 覆盖层 + 管理接口实时切换**（Flyway V8 表存快照，DB 有记录优先于 yml；启动默认仍读 yml）→ 否决：真值从文件挪到数据库，与"配置文件即真值"的既有惯例冲突，需新增表、鉴权管理端点与优先级规则；切换属低频运维操作，走 CI 部署的分钟级成本可接受；收益（免重启换模型）不抵两处真值的长期维护负担。
- **仅内存态 + 管理接口**（重启回 yml 默认）→ 否决：生产每次重启/重部署都会静默丢配置，比漂移更隐蔽。
- **Spring Cloud `@RefreshScope` + actuator refresh** → 否决：yml 在镜像内，容器部署下文件本身难改（需额外挂载 volume），引入 spring-cloud-context 依赖只为低频操作，两头不讨好。
- **不建类，仅删 @Value 默认值** → 否决：8 个字段仍在三个类里各声明一遍，加字段要改三处，重复未根治。
- **模型名封闭枚举校验（AiModel enum）** → 否决：网关（opencode zen）模型集合会演进，封闭清单每加模型都要改代码走部署；typo 风险已由契约测试 + 启动必填校验覆盖大部分。

## Consequences

- 配置真值唯一（yml），新增/修改 AI 参数只有一处；启动即校验完整性，不再有静默兜底。
- 三个服务构造签名收紧为"一个配置对象 + 运行时依赖"，单元测试以测试替身构造，契约由 AiPropertiesTest 统一守护。
- 代价：生产切换模型仍需走 CI 部署管线（分钟级）——这是"yaml 唯一真值"的固有代价，本 ADR 明示接受；将来若切换频率升高（如 A/B 试模型成为常态），可重新评估 DB 覆盖层方案，届时本 ADR 的否决理由作为对照基线。
