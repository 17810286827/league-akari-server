# league-akari-server 项目交接文档（Handoff）

> 面向接手者的一站式文档：整体结构、全部 HTTP 接口、核心业务流程、外部依赖、配置与测试。
> 领域术语的唯一权威定义见根目录 `CONTEXT.md`；架构决策记录见 `docs/adr/`；部署交接见 `DEPLOY_HANDOVER.md`。
> 最后更新：2026-09-05（基于 main 分支 d8dc493，已含 #26 统一响应契约与 #27 结构收敛）

> ⚠️ 本文档第 3.4 节异常映射表与第 5.4 节播报触发方式已被 #26/#27 改造覆盖，
> 现行契约：所有 JSON 接口 HTTP 200 + `{code, message, data}` 统一信封，错误语义全靠业务码
> （0 成功 / 1xxx 参数 / 11xx 车队配置 / 2xxx 对局 / 3xxx 账号 / 4xxx 外部依赖 / 5xxx 系统，
> 登记处 `common/exception/ErrorCode`）；播报由 `MatchSavedEvent` 事务提交后触发，
> controller 不再直调 BroadcastCoordinator。最新结构见 AGENTS.md 目录结构章节。

---

## 1. 项目概览

英雄联盟对局同步后端（个人自用，车队 5 人规模）：接收 LCU 客户端推送的对局数据（比赛信息 + 时间线）幂等写入 MySQL；支持从 Riot 官方 API 回填历史对局；在此之上提供对局查询、OP Score 评分（MVP/SVP 评选）、车队周报/榜单/成员卡、AI 对局分析（SSE 流式）与局后 QQ 群播报（战报图 + AI 锐评）。

- **技术栈**：Spring Boot 3.3.5、Java 21、MyBatis-Plus 3.5.7、MySQL、Flyway、Lombok、Apache HttpClient 5
- **构建/测试**：`mvn test`（Maven，无 wrapper）
- **运行**：端口 8081，默认仅绑定 `127.0.0.1`（生产用环境变量 `SERVER_ADDRESS=0.0.0.0` 放开）
- **单包应用**：根包 `com.leagueakari`，入口 `LeagueAkariServerApplication`

### 分层约定（强约束）

- `controller/`：只做参数校验（`@Valid` / `@RequestParam`）与返回值封装，**不写业务逻辑、不捕获异常**
- `service`（各业务包内）：业务逻辑，**抛异常不处理 HTTP 语义**
- `config/GlobalExceptionHandler`：统一异常 → HTTP 状态转换，响应体统一 `{ code, message }`
- 数据库变更一律走 Flyway（`src/main/resources/db/migration/`），已执行迁移不可修改

---

## 2. 包结构与职责

```
com.leagueakari
├── controller/      路由层（4 个控制器，见 §3）
├── match/           对局子系统：写入、查询、时间线、AI 分析、统计读取门面
│   ├── MatchIngestService        幂等入库管道（先查后插 + 并发 DuplicateKey 兜底）
│   ├── MatchQueryService         分页列表 / 详情视图组装（折叠卡、MVP 称号、实时评分）
│   ├── MatchTimelineService      时间线 frames 幂等写入与查询
│   ├── AiAnalysisService         单局 AI 分析（SSE 流式、2 分钟 JVM 缓存）
│   ├── ParticipantStatsReader    stats_json 统计读取门面（缺失补 0 口径全项目唯一实现）
│   └── MatchNotFoundException    对局不存在领域异常 → 404
├── scoring/         OP Score 评分引擎域
│   ├── OpScoreEngine             评分核心（职业表/基线表自加载，纯计算）
│   ├── MatchMvpService           MVP/ACE 评选编排（落库）+ 实时评分查询（computeScores）
│   └── BaselineService           scoring_baseline 基线累积 + 进程内缓存
├── team/            车队数据域
│   ├── TeamRosterService         roster 名单 → 成员身份集合（两套 puuid 归一）
│   ├── TeamStatsService          周报 / 七榜单 / 成员列表 / 成员卡的全部聚合口径
│   └── WeeklyAiCommentService    周报 AI 锐评（非流式，10 分钟缓存）
├── broadcast/       局后播报域
│   ├── BroadcastCoordinator      推送状态机（PENDING→PUSHING→SENT/AI_FAILED/FAILED）
│   ├── FleetGameSummaryService   "车队视角一局摘要"口径唯一实现（主队判定/比分/排序/称号）
│   ├── ReportImageProjector      一局摘要 → 战报图渲染规格（纯投影）
│   ├── PostGameSummaryBuilder    一局摘要 → AI 锐评输入 JSON（纯投影）
│   └── PostGameCommentService    局后锐评生成（非流式，失败由协调器降级）
├── reportimage/     ReportImageRenderer（Java2D 900px 战报 PNG，内置思源黑体）
├── qqbot/           QQ 官方开放平台
│   ├── QqBotClient               OpenAPI：access_token 缓存、文本/Markdown/图片（分片上传）消息
│   ├── QqEventWsClient           WS 事件通道（心跳/重连，默认关闭）
│   └── QqEventDispatcher         事件帧解析（纯函数，只关心入群/退群）
├── riot/            Riot 官方 API 域
│   ├── RiotHttpClient            统一出口：X-Riot-Token + 限流 + 状态码语义（三合一）
│   ├── RiotRateLimiter           滚动窗口限流器（个人 Key 约 100 请求/2 分钟）
│   ├── RiotAccountClient         召唤师搜索（riot_account 库缓存优先 → Account-V1 + Summoner-V4）
│   └── RiotMatchHistoryService   MATCH-V5 历史回填（异步、幂等、复用 saveMatch）
├── ai/              AI 公共客户端
│   ├── AiClient                  OpenAI 兼容 chat/completions（非流式 call + 流式 callStream + 空正文重试）
│   ├── AiCompletionRequest       采样参数载体（model/temperature/penalty/maxTokens/thinking）
│   ├── AiStreamHandler           流式增量回调接口（onContent / onReasoning）
│   └── PromptLoader              提示词文件加载 + 内置默认回退（全项目唯一实现）
├── gamedata/        游戏静态资源（CommunityDragon）
│   ├── GameDataService           英雄/装备 ID → 中文名（懒加载 + JVM 缓存，zh_cn 失败降级 default）
│   └── ChampionIconService       英雄头像 PNG（战报图用，失败降级色块圆盘）
├── dto/             请求/响应 DTO（含 MatchSummaryResponse 等嵌套视图）
├── entity/          数据库实体（match / match_participant / match_timeline / riot_account /
│                    champion_class / match_mvp / scoring_baseline）
├── mapper/          MyBatis-Plus Mapper（7 个）
├── config/          配置类（见 §7）+ GlobalExceptionHandler
└── util/            ClientDisconnectDetector（客户端断开异常识别，避免断连刷 ERROR 日志）
```

**依赖方向的几条主线**（改动前先看，避免破坏分层）：

- `MatchIngestService` → `MatchMvpService`（同事务评选 + 基线累积）
- `MatchController` → `MatchIngestService` + `BroadcastCoordinator`（落库后触发播报判定）
- `TeamStatsService` / `BroadcastCoordinator` → `TeamRosterService`（身份集合）→ `RiotAccountClient`
- `RiotMatchHistoryService` → `MatchIngestService.saveMatch`（回填与客户端同步走同一条入库管道）
- 三个 AI 场景（单局分析 / 周报锐评 / 局后锐评）全部经公共 `AiClient`，采样参数由各场景从 `AiProperties` 组装（ADR 0004/0005）

---

## 3. HTTP 接口清单

统一约定：

- 成功同步类接口返回 `{ "code": 0 }`；查询类接口包 `{ "data": ... }`；错误统一 `{ code, message }`
- 分页响应 `PageResponse<T>`：`{ items, page, pageSize, total, recentOpponents }`（recentOpponents 仅对局列表携带）
- 服务在线探测：`GET /actuator/health`；`GET /` 返回 `{code:0, service, health}` 引导信息

### 3.1 对局（MatchController，`/api/matches`）

| 方法 | 路径 | 说明 | 实现落点 |
|---|---|---|---|
| POST | `/api/matches` | 接收对局同步推送，**幂等写入** | `MatchIngestService.saveMatch`（事务内：主表+参赛者+MVP评选+基线累积），随后 `BroadcastCoordinator.maybeBroadcast` 触发局后播报判定 |
| GET | `/api/matches` | 分页列表，支持 `page`(1)、`pageSize`(20)、`queueId`、`puuid`、`summonerName`、`startTime`、`endTime` 筛选 | `MatchQueryService.pageMatches` |
| GET | `/api/matches/{gameId}` | 对局详情（全量快照 + mvp/ace 称号 + 全员 playerScores 实时评分） | `MatchQueryService.getMatchDetail` |
| POST | `/api/matches/{gameId}/ai-analysis` | **SSE 流式** AI 对局分析（`text/event-stream`） | `AiAnalysisService.analyzeStream` |
| POST | `/api/matches/{gameId}/timeline` | 接收时间线推送（frames 全量），幂等写入 | `MatchTimelineService.saveTimeline` |
| GET | `/api/matches/{gameId}/timeline` | 查询时间线 frames | `MatchTimelineService.getTimeline` |

关键契约：

- **幂等键是 gameId**：重复推送同一 gameId 不产生任何写入、不覆盖首存（先查后插 + `DuplicateKeyException` 并发兜底）。时间线同规则。
- **列表必须按玩家查**：`puuid` 与 `summonerName` 二选一，都缺失时返回空页（不暴露全量对局）。玩家视角（self 卡片/队友/最近对手）以查询参数为准，与 `match.self_puuid`（推送者）无关。
- **时间线一致性**：path 的 `gameId` 与 body 的 `gameId` 不一致抛 `IllegalArgumentException` → 400。
- **AI 分析 SSE 事件协议**（data 为 JSON）：`{type:"start",fromCache}` → `chunk`（正文增量）/`reasoning`（思维链增量）→ `done`（截断时带 `truncated:true`）/`error`。前置校验失败（Key 未配置 → 503，对局不存在 → 404）在流建立前抛出。结果缓存 2 分钟，命中时 `fromCache=true`。
- 列表/详情的 MVP 称号字段：`mvp`（胜方最佳）与 `ace`（败方最佳，语义即 SVP，历史命名保留）；老数据可能两者皆 null。

### 3.2 Riot 召唤师（RiotController，`/api/riot/accounts`）

| 方法 | 路径 | 说明 | 实现落点 |
|---|---|---|---|
| GET | `/api/riot/accounts/by-name?riotName=昵称#tag` | 按 Riot ID 搜索账号（puuid/gameName/tagLine/等级/头像） | `RiotAccountClient.searchByRiotId` |

- 查询优先级：`riot_account` 持久化缓存表（puuid 终身不变，一人一行）→ 未命中才调 Riot Account-V1，并补 Summoner-V4 等级/头像（失败不阻塞主流程），结果按 puuid upsert 回库。
- 错误：格式缺 `#tag` → 400；召唤师不存在 → 404；Key 未配置 / Riot 调用失败 → 503。

### 3.3 车队（TeamController，`/api/team`）

| 方法 | 路径 | 说明 | 实现落点 |
|---|---|---|---|
| GET | `/api/team/weekly?date=yyyy-MM-dd` | 车队周报（date 为该周任意一天，缺省=上一自然周）；含总览、六榜单、名场面、AI 锐评（失败降级 null） | `TeamStatsService.weeklyReport` + `WeeklyAiCommentService` |
| GET | `/api/team/leaderboards?dimension=&mode=&start=&end=` | 榜单中心单维度榜单；dimension 必填（mvp/opscore/criminal/feeder/carry/signature/attendance），mode 为 game_mode 过滤，start/end 毫秒时间戳（缺省全时段） | `TeamStatsService.leaderboard` |
| GET | `/api/team/members` | roster 成员与全时段车队对局出勤 | `TeamStatsService.members` |
| GET | `/api/team/members/{puuid}` | 成员卡：近 8 周成长曲线 + 英雄基线对比（全时段）；非车队成员 → 400 | `TeamStatsService.memberCard` |
| POST | `/api/team/backfill` | 触发 Riot 历史对局回填（异步，立即返回）；运行中重复触发返回 `started=false` | `RiotMatchHistoryService.startBackfill` |

- 所有车队接口依赖 `team.roster` 配置：未配置 → 400；任一成员库内与 Riot 都查不到 → 400；Riot 查询失败但库内有身份 → 降级可用（仅回填能力缺失）。

### 3.4 统一异常映射（GlobalExceptionHandler）

| 异常 | HTTP | 说明 |
|---|---|---|
| `MethodArgumentNotValidException` | 400 | `@Valid` 校验失败，取首个字段错误 |
| `IllegalArgumentException` | 400 | 业务参数错误（gameId 不一致、名单未配置、未知榜单维度等） |
| `HttpMessageNotReadableException` / `MethodArgumentTypeMismatchException` | 400 | 请求体/参数类型错误 |
| `MatchNotFoundException` / `RiotAccountNotFoundException` | 404 | 对局/召唤师不存在 |
| `NoResourceFoundException` | 404 | 路径不存在 |
| `HttpRequestMethodNotSupportedException` | 405 | 方法不支持 |
| `IllegalStateException` | 503 | 外部依赖失败（Riot/AI Key 未配置、调用失败等） |
| 其他 `Exception` | 500 | 完整堆栈落日志，响应只透"服务器内部错误" |
| `AsyncRequestNotUsableException` / 客户端断开类 | （不写响应体） | SSE 断连是预期现象，记 WARN 无堆栈 |

---

## 4. 数据库（Flyway V1–V7）

| 版本 | 内容 |
|---|---|
| V1 | `match` 对局主表（幂等键 `uk_game_id`，teams_json 快照）+ `match_participant` 参赛者明细（直显列 + stats_json 全量快照，`uk(match_id,puuid)`） |
| V2 | `match_timeline` 时间线（frames_json 全量，`uk_game_id`） |
| V3 | `riot_account` 召唤师账号缓存（`uk_puuid`，按名查询索引） |
| V4 | `champion_class` 英雄职业表（ADC/MAGE/TANK/ASSASSIN/FIGHTER/SUPPORT，含全量 INSERT 种子数据） |
| V5 | `match_mvp` MVP/ACE 评选结果（`uk(match_id,type)`，score + score_detail_json） |
| V6 | `scoring_baseline` 评分基线表（按英雄累积的每分钟维度累计和）+ `match_mvp` 扩展列 `scoring_version` / `op_score` / `grade` |
| V7 | `match` 局后播报列：`push_status`（PENDING/PUSHING/SENT/AI_FAILED/FAILED）、`push_image_at`、`push_comment_at`、`push_error`；**并把存量旧局置为 SENT**（防迁移后误播报） |

注意事项：

- `match` 是 MySQL 保留字，SQL 中必须反引号。
- 所有表字段均带中文注释（工作区约定）；新增表/字段必须新建 `V{n}__xxx.sql`。
- `champion_class` 与 `scoring_baseline` 无写入口（前者手工维护，后者仅同步管道累加）。

---

## 5. 核心业务流程

### 5.1 对局同步入库管道（系统的写入主干）

```
LCU/桌面端 POST /api/matches
  → MatchIngestService.saveMatch（@Transactional）
      1) 按 game_id 查重 → 已存在直接返回（幂等）
      2) 插入 match 主表（DuplicateKey 并发兜底）
      3) 逐条插入 match_participant（直显列缺失补 0，stats 全量透传）
      4) 同事务：MatchMvpService.evaluateAndSave（OP Score 评选 → match_mvp）
      5) 同事务：MatchMvpService.collectBaselines（per-min 值累加 → scoring_baseline）
  → 返回 {code:0}
  → BroadcastCoordinator.maybeBroadcast(gameId)（失败只落库状态，不影响同步响应）
```

时间线独立同步（POST `/api/matches/{gameId}/timeline`），同样幂等，重复推送不覆盖首存 frames。

### 5.2 OP Score 评分引擎（scoring 包）

- 输入：全 10 人 `MvpScoringInput`（自 stats_json 经 `ParticipantStatsReader` 提取，缺失补 0）。
- 8 个维度（damage/kda/gold/tank/vision/healShield/cc/turret）→ 每分钟值 → **队内位次分**（同队 5 人 100/75/50/25/0，并列取平均）与**基线分**（我的每分钟值 ÷ 该英雄历史均值 × 100）按混合比合成：样本 <10 纯局内，10~30 线性过渡，≥30 锁定混合比上限 0.5。
- 维度按英雄职业权重（`scoring.weights`，每行和为 1.0）加权平均得 0–100 总分 → OP Score = 总分/10 + 多杀加分（双杀 0.2 / 三杀 0.5 / 四杀 1.0 / 五杀 2.0），clamp 0–10，映射 8 档文字等级（完美/卓越/优秀/良好/一般/偏低/较差/糟糕）。
- **MVP = 胜方 op_score 最高；ACE = 败方 op_score 最高（接口/库里字段值 `ACE`，语义即 SVP）**。评选以 op_score 为准，平局按加权总分决胜。
- **大乱斗修正按 queueId 判定**（450 / 2400 / 2410 / 2450），不能用 gameMode 字符串——LCU 的 CHERRY 实为斗魂竞技场。大乱斗下辅助职业视野权重归零。
- 评分算法版本号 `scoring.version`（当前 4）随评选落库；详情接口的全员评分是**实时计算**（`MatchMvpService.computeScores`，纯计算不落库），与落库口径同引擎同权重，老对局同样可算。
- `champion_class` 表与 `BaselineService` 缓存由引擎自加载（懒加载/写后置空失效）。

### 5.3 车队身份与统计口径（team 包）

- **两套 puuid 体系不可混用**（CONTEXT.md 权威定义）：腾讯侧 puuid（带连字符，LCU/SGP 同步的局）与 Riot 全局 puuid（无连字符，MATCH-V5 回填的局）。同一个人在库里可能两者都有。
- `TeamRosterService` 把 `team.roster` 的"昵称#tag"解析为**成员身份集合**（库内按 summoner_name 反查 ∪ Riot Account-V1），进程内缓存；车队所有匹配按身份集合进行。
- **车队对局**：同局车队成员数 ≥ `team.min-shared-members`（默认 2）。周报与全部榜单只统计车队对局；**成员卡是唯一按"个人全部对局"统计的出口**。
- 自然周口径：周一 00:00 起算，时区 Asia/Shanghai；周报默认统计"今天回退 7 天所在周"。
- `TeamStatsService.loadGames` 是全部榜单/成员卡的统一数据装载入口（批量加载参赛者与评选记录避免 N+1，实时评分分段计时打日志）。
- 榜单七维度：mvp / opscore / criminal（战犯）/ feeder（献祭）/ carry / signature（绝活）/ attendance（出勤）。
- 周报 AI 锐评：只喂"梗素材"（总览 + 各榜前 3 + 名场面），失败降级为 null 不影响周报主体。

### 5.4 局后播报（broadcast 包，纯推送状态机）

```
对局落库后（含桌面端补推）触发 maybeBroadcast(gameId)：
  门控① 状态：SENT / AI_FAILED / PUSHING → 跳过
  门控② 开关与配置：push.enabled、group/appId/secret 齐备
  门控③ 时间窗：距估算结束时刻（game_creation + duration）超过 push.recent-window-minutes(30) → 视为旧局，置 SENT
  门控④ 车队局：同局成员数 ≥ 阈值（个人局永远不播报，置 SENT 免重复判定）
  抢占：CAS PENDING/FAILED → PUSHING（并发双发防线）
  发送：FleetGameSummaryService 一局摘要
        → ReportImageProjector 投影 → ReportImageRenderer 渲染 PNG
        → QqBotClient 图片消息（分片上传换 file_info，msg_type=7）→ 置 SENT
  锐评（可选 push.ai-comment-enabled）：PostGameSummaryBuilder 投影
        → PostGameCommentService 非流式 AI → Markdown 消息（msg_type=2，**加粗**）
        → 失败发"AI 缺席提示"文本 → AI_FAILED；提示也失败 → FAILED 等补推
```

- 状态机：`PENDING/FAILED →(CAS)→ PUSHING → SENT / AI_FAILED / FAILED`；发送失败置 FAILED，桌面端轮询补推同一局时天然重试。
- 服务启动时 `recoverInterruptedPush` 把残留 PUSHING 恢复为 FAILED（进程中断自愈）。
- **口径唯一来源**：主队判定/比分/排序/称号只存在于 `FleetGameSummaryService`，战报图与锐评是它的两个纯投影（历史上曾因两套实现口径漂移导致战报图比分恒显 0:0，commit 36af3b9）。

### 5.5 Riot 历史回填（riot 包）

- `POST /api/team/backfill` → 专用单线程执行器异步执行；`running` volatile 标记防并发触发。
- 按成员逐人：MATCH-V5 分页拉对局 ID（`sea.api.riotgames.com`，用 **Riot 全局 puuid**，腾讯 UUID 查不到）→ 数字 gameId 幂等预检查（已在库跳过详情拉取省配额）→ 拉详情转 `MatchSyncRequest`（`dataSource=riot-api`）→ 复用 `saveMatch` 入库（自动触发评选与基线累积）。
- 限流：所有 Riot 请求物理上只过 `RiotHttpClient`（X-Riot-Token + `RiotRateLimiter` 滚动窗口 + 404→业务异常 / 429→等待后重试一次）。单成员上限 `riot.backfill-max-matches`（默认 200 局）防失控。
- 中文昵称经 `URIBuilder.setPathSegments` 统一编码，禁止手工预编码（会二次编码成 %25）。

### 5.6 AI 三场景（ai 包公共客户端 + 各业务编排）

| 场景 | 模式 | 模型 | 缓存 | 失败语义 |
|---|---|---|---|---|
| 单局 AI 分析（`/api/matches/{id}/ai-analysis`） | SSE 流式，专用线程池 | `ai.model`（gemini-2.5-flash），thinking 可配 | JVM 缓存 2 分钟 | error 事件收尾；客户端断开静默终止 |
| 周报锐评（TeamStatsService 内） | 非流式 | `ai.model`，thinking=false | 按周标签 10 分钟 | 抛异常 → 周报 aiComment=null |
| 局后锐评（broadcast 内） | 非流式 | `ai.post-game-model` 独立键（延迟敏感） | 无 | 重试后仍失败 → 发"AI 缺席提示" |

- `AiClient` 只管连接级事务（URL/Bearer/浏览器 UA（Cloudflare 防护）/payload/HTTP 错误/SSE 解析），**不感知业务**；采样参数经 `AiCompletionRequest` 显式传入；空正文重试（推理模型把预算耗在思维链上时正文为空）由 `call` 重载承载。
- 提示词文件在 `src/main/resources/ai/`（system-prompt.md / weekly-prompt.md / post-game-prompt.md），每次调用现读，编辑即时生效；`PromptLoader` 提供内置默认回退。
- AI 摘要中的英雄/装备 ID 先经 `GameDataService` 转中文名（CommunityDragon），避免模型凭记忆猜 ID 出错。

---

## 6. 外部依赖一览

| 依赖 | 用途 | 出口 | 失败语义 |
|---|---|---|---|
| Riot API（asia/sea.api.riotgames.com） | 账号搜索、Summoner-V4、MATCH-V5 回填 | `RiotHttpClient`（唯一出口，token+限流+状态码语义） | 404→业务 404；429→重试一次；其余→503 |
| AI 网关（OpenAI 兼容 `ai.base-url`） | 三个 AI 场景 | `AiClient`（与 Riot 共用全局 HttpClient 连接池） | 非 200/网络失败→503（HTTP 层）或业务降级 |
| QQ 官方开放平台（api.bot.qq.com） | 群消息推送 + WS 事件通道 | `QqBotClient` / `QqEventWsClient`（独立协议域，**不并入** Riot 出口） | `QqPushException` → 播报状态机 FAILED |
| CommunityDragon（raw.communitydragon.org） | 英雄/装备中文名、头像 PNG | `GameDataService` / `ChampionIconService` 直连（无鉴权无限流语义） | 失败降级：回 ID 字符串 / 色块圆盘，可自愈重试 |

---

## 7. 配置（application.yml）

| 前缀 | 关键项 | 说明 |
|---|---|---|
| `server` | port=8081，address=`${SERVER_ADDRESS:127.0.0.1}` | 本机自用勿放宽监听 |
| `spring.datasource` | `${DB_HOST:192.168.31.90}:3306/league_akari`，`DB_USERNAME`/`DB_PASSWORD` 可覆盖 | 虚拟机 MySQL，跑测试需可达 |
| `team` | `name`（车队名）、`roster`（"昵称#tag" 名单）、`min-shared-members=2` | 成员增删改这里即可；改名后需更新 |
| `riot` | `api-key`（`RIOT_API_KEY`）、account/summoner/match-domain、backfill-page-size=100、backfill-max-matches=200 | Key 为空仅影响搜索与回填 |
| `ai` | base-url、api-key（`AI_API_KEY`）、model、thinking=false、temperature/penalty、max-tokens=4096、weekly/post-game 独立键、三个提示词文件 | 配置唯一真值，经 `AiProperties` 绑定（ADR 0004） |
| `push` | enabled（`PUSH_ENABLED`，默认 false）、group-open-id、app-id、client-secret、recent-window-minutes=30、ai-comment-enabled | 凭证属部署机密，生产用环境变量 |
| `scoring` | version=4、weights（六职业+UNKNOWN 权重表）、multi-kill-bonus、baseline-threshold-min/max=10/30、baseline-mix-max=0.5 | 权重直接改 yaml 可微调 |
| `logging` | 文件 `/var/log/league-akari/app.log`（`LOG_FILE` 可覆盖），20MB×15 天轮转 | 容器内挂载持久化 |

config 包其余基础设施：`HttpClientConfig`（全局 HttpClient 5 连接池 + AI 流式专用线程池）、`BackfillConfig`（限流器 + 回填执行器）、`TimeConfig`（注入 `Clock`，时区 Asia/Shanghai，测试可换 fixed）、`MybatisPlusConfig`（分页插件）、`CorsConfig`（任意来源放开 /api，本机自用无鉴权）。

---

## 8. 测试

- JUnit 5 + Mockito + AssertJ；测试路径与被测路径镜像（`src/test/java/com/leagueakari/{package}/`）。当前约 40 个测试类，覆盖全部业务包。
- 集成测试在 `controller/`（MatchControllerIntegrationTest、MatchTimelineControllerIntegrationTest、TeamControllerIntegrationTest、PushBroadcastIntegrationTest）。
- 跑测试/启动需保证 `192.168.31.90:3306` 的 MySQL 可达（Flyway 会自动迁移）。
- 名为 `*SmokeTest` / `*ProbeTest` 的类（QqRealSendSmokeTest、AiModelProbeTest、MatchMapperSmokeTest）是真实环境冒烟，注意别当常规单测看待。
- 开发约定：TDD 红绿灯（先写测试）；新增统计读取口径必须走 `ParticipantStatsReader` 门面并补 `ParticipantStatsReaderTest`。

---

## 9. 已知坑与约定（接手必读）

1. **两套 puuid**：腾讯侧（带连字符）与 Riot 全局（无连字符）不可互换、不可互相推导；新增玩家匹配逻辑一律走成员身份集合（`TeamRosterService.RosterMember.owns`）。
2. **SVP 叫 ACE**：落库与接口字段值是 `ACE`，语义即 SVP，不要新造第三种叫法。
3. **大乱斗判定看 queueId**，不看 gameMode（CHERRY = 斗魂竞技场）。
4. **战报图/锐评口径只有一处**：FleetGameSummaryService；投影层禁止重新判定主队/重算比分（历史 bug 教训）。
5. **Riot 请求只准走 `RiotHttpClient`**；QQ 与 CommunityDragon 是独立协议域，不并入。
6. **榜单慢**：`loadGames` 会全量装载并实时评分，已有分段计时日志定位耗时；优化时别破坏"与落库口径同引擎"的约定。
7. **列表接口的视角 puuid**：以查询参数（puuid/summonerName 反查首行）为中心，不是 `match.self_puuid`（单客户端部署下那永远是推送者）。
8. **V7 之后的存量数据已置 SENT**：新增类似"只播报一次"语义时，幂等语义由推送状态机承载，不要依赖"仅首次入库触发"。
9. **AI 局后锐评走独立 `post-game-model` 键**：历史教训是某推理模型无视 thinking=false 仍先推理 60 秒；换慢模型时播报不受影响正是拆分的原因。
10. **`match` 表名是保留字**，手写 SQL 必须反引号。

---

## 10. 相关文档索引

| 文档 | 内容 |
|---|---|
| `CONTEXT.md` | 领域术语权威定义（MVP/SVP、身份集合、车队对局、一局摘要、播报等，含 Avoid 词） |
| `docs/adr/0001` | MVP/SVP 职业差异化评分 |
| `docs/adr/0002` | 成员身份集合（两套 puuid） |
| `docs/adr/0003` | QQ 官方机器人推送选型 |
| `docs/adr/0004` | AI 配置单一来源（AiProperties） |
| `docs/adr/0005` | AI 客户端统一调用（AiClient） |
| `docs/spec/mvp-svp-scoring.md` | 评分规格 |
| `docs/spec/post-game-broadcast.md` | 局后播报规格 |
| `DEPLOY_HANDOVER.md` | CI/CD 与云服务器部署交接（GitHub Actions → 阿里云 ACR → SSH 部署） |
| `DOCKER_DEPLOY.md` / `docker-compose.yml` / `deploy.sh` | Docker 一键部署 |
