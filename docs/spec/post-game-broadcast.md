# Post-Game Broadcast 局后播报 — 设计规格

> 车队对局结束后，自动向车队群推送"战报图 + 局后锐评"。
> 术语见 `CONTEXT.md`（车队群 / 局后播报 / 战报图 / 局后锐评），通道决策见 `docs/adr/0003-qq-official-bot-push.md`。
> 视觉原型：`D:\IDE\project\LOL\qq-push-report-prototype.html`（方案 C v2）。

## 1. 目标与非目标

**目标**：开黑局打完，群里自动出现一张战报图（秒发），随后一条 AI 锐评文本（约 25s 后）；AI 全挂也有交代；同一局只播报一次；失败可追溯可重试。

**非目标**：不做 Web/桌面端 UI（纯服务端）；不做多车队多群；不做 Markdown/卡片消息形态；不做周报级聚合（已有）。

## 2. 触发链路

```
桌面端 match-sync（EndOfGame 实时 / 2min 轮询补推 / 手动）
  └─ POST /api/matches  (+ /api/matches/{id}/timeline)
       └─ MatchController.saveMatch ─┐
                                     ▼
                            MatchService.saveMatch()
                              ├─ 已存在 → return（幂等，不播报）
                              └─ 首次插入 → return 布尔 true（新增语义）
                                     ▼
                            BroadcastCoordinator.maybeBroadcast(gameId)
                              ├─ 条件①：本局命中车队（复用 TeamStatsService.isFleet 口径）
                              ├─ 条件②：落库时刻 - 估算结束时刻 ≤ push.recent-window（默认 30min）
                              │        （估算结束 = game_creation + game_duration*1000）
                              ├─ 条件③：push.enabled 且机器人凭证/群已配置
                              └─ 通过 → 异步执行（专用线程池 pushExecutor）
```

实现要点：

- **`saveMatch` 语义增强**：由 `void` 改为返回 `boolean`（是否首次插入），内部"先查后插 + 唯一键兜底"逻辑不变，仅在两处 insert 成功路径返回 true。`MatchController` 对外响应契约不变（`code: 0`），`RiotMatchHistoryService`（backfill）调用处忽略返回值——backfill 的局天然是旧局，被条件②时间窗排除，无需显式跳过；防御性起见 backfill 路径仍可通过时间窗兜底。
- **触发在首次插入事务外**：`maybeBroadcast` 应在 saveMatch 事务提交后调用（避免事务内长耗时），由 controller 编排：`boolean created = matchService.saveMatch(req); if (created) broadcastCoordinator.maybeBroadcast(gameId);`——或经事件/`@TransactionalEventListener(phase=AFTER_COMMIT)`。
- **条件②为何需要**：桌面端 DTO 无"实时/补推"标记（已核实），首次插入 + 时间窗是纯服务端可得的近似实时判据；实时失败被 2min 轮询补推的局（距结束仅数分钟）仍在窗内 → 正是想要的重试兜底；重启后补推的陈旧局（数小时以上）被排除。
- **并发**：同一局多个车队成员桌面端并发推送时，唯一键兜底保证只有一个"首次插入"返回 true；`maybeBroadcast` 内部以 `match.push_status` 乐观更新（`UPDATE ... SET push_status='PUSHING' WHERE game_id=? AND push_status='PENDING'` 影响行数=1 才继续）防双发。

## 3. 数据库变更（Flyway V7）

```sql
-- V7__match_push_status.sql
ALTER TABLE `match`
  ADD COLUMN push_status   VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '局后播报状态: PENDING待推送/SENT已送达/AI_FAILED图已发AI缺席/FAILED失败待补' ,
  ADD COLUMN push_image_at DATETIME NULL COMMENT '战报图发送时间',
  ADD COLUMN push_comment_at DATETIME NULL COMMENT '锐评/缺席提示发送时间',
  ADD COLUMN push_error    VARCHAR(512) NULL COMMENT '最近一次失败原因';
CREATE INDEX idx_match_push_status ON `match`(push_status);
```

- 状态机：`PENDING → SENDING(内存态) → SENT`；任一环节失败置 `FAILED`（记 `push_error`）。锐评重试耗尽后若战报图已发 → `AI_FAILED`（语义：图已送达、AI 缺席提示已发）。
- 兜底重试：桌面端每 2 分钟轮询会重推"未成功"的局，`saveMatch` 对已存在但 `push_status IN ('FAILED')` 的局可再次进入 `maybeBroadcast`（补推通道天然重试，无需定时任务）。已 `SENT` 的局不再触发。
- 存量数据：迁移默认 `PENDING` 会污染历史局 → 迁移末尾 `UPDATE match SET push_status='SENT' WHERE created_at < NOW() - INTERVAL 1 DAY`（或按时间窗把窗口外老局标记 SENT，避免误播历史）。

## 4. 消息流程（一次播报的两条消息）

```
T+0s    判定通过 → 渲染战报图 PNG（内存，~百 KB）
        → 上传官方媒体（分片上传 /v2/groups/{gid}/files, file_type=1）
        → 发送图消息（msg_type=7）           成功 → push_image_at, 状态推进
T+0.5s  异步生成局后锐评（LLM 非流式，~25s）
        → 成功 → 发送文本（msg_type=0）      成功 → push_comment_at, SENT
        → 重试 2 次仍失败 → 发送 AI 缺席提示文本 → push_comment_at, AI_FAILED
```

- 发送顺序保证：先图后文本（消息服务内部单飞串行或按 gameId 分桶，防止多局并发时乱序刷屏）。
- AI 缺席提示文案（常量，可配置）：「🤖 AI 评阅官本局不在线，锐评缺席一次——战报图已送达，欢迎人工复盘。」缺席提示也计入推送状态（`AI_FAILED` 表示提示已发；若提示本身也发送失败 → `FAILED` 待补推重试）。

## 5. QQ Bot 出站客户端（`qq-bot` 模块，仿 AiAnalysisService 的 HttpClient 用法）

- **凭证**：`POST https://api.bot.qq.com/app/getAppAccessToken`（`app_id` + `client_secret`）→ `access_token`，进程内缓存至过期前 60s 刷新；所有业务请求带 `Authorization: Bearer`。
- **发送文本**：`POST /v2/groups/{group_openid}/messages`，`{"msg_type":0,"content":"..."}`。
- **发送图片**：分片上传路径——`POST /v2/groups/{group_openid}/upload_prepare`（file_size/name/md5/sha1）→ 逐片 PUT 预签名 URL → `upload_part_finish` 逐片确认 → `POST /v2/groups/{group_openid}/files`（`file_type:1, upload_id`）→ 得 `file_info`（ttl 300s，现传现用）→ `POST .../messages`，`{"msg_type":7,"media":{"file_info":"..."}}`。
- **WS 事件客户端**：官方 WS 网关（Intent `GROUP_AND_C2C_EVENT`），处理 `GROUP_ADD_ROBOT`（把群 openid 打到 warn 日志/可选自更新配置）、`GROUP_DEL_ROBOT`、心跳。断线指数退避重连；事件通道故障不影响主动发送（发送是纯 HTTP）。管理端需配置 IP 白名单（云服务器出口 IP）。
- 错误码映射：850018 禁言、850026 下载失败、850031 超限等 → 统一包装为 `PushException(code)` 落 `push_error`。
- 发送频控远低于配额（每局 ≤2 条），无需队列限流，但保留发送间隔 ≥1s 的保险节流。

## 6. 战报图渲染规格（Java2D headless）

- **画布**：宽 **900px**，高动态（原型 C v2 约为 1180~1250px，按行数/文案自适应）；`BufferedImage.TYPE_INT_RGB`，输出 PNG。
- **字体**：镜像内打包 **思源黑体**（SIL OFL，可商用）：`SourceHanSansSC-Regular.otf` 与 `-Bold.otf`（Latin 数字用 Bold 对齐原型 Georgia 的效果）；Dockerfile COPY 至 `/fonts` 并在渲染前 `Font.createFont` 注册。禁止依赖系统字体（云服务器无中文字体）。
- **布局**（自上而下，原型 C v2 的区块与坐标基线）：

| 区块 | 内容 | 主色 |
|---|---|---|
| 顶栏 | 车队名+日期+时长 meta / 右 VICTORY·胜利或 DEFEAT·败北 胶囊 + 比分 | 底 `#101a2e→#0b1220` 渐变；胜蓝 `#4b7be5`/负红 `#e03e52` |
| 资源条 | 推塔/小龙/大龙/一血 对比（胜方高亮） | 文本 `#7e92ad`，高亮 `#8ab0ff`/`#ff8a98` |
| MVP 大卡 | 胜方/败方"英雄特写"（本队 MVP；败局给"尽力"成员）+ 输出/承伤/伤转 | 胜蓝/负红 12% 透明底 + 描边 |
| 阵容表 | 蓝红两列：头像色块(英雄中文名) | 名 `#e9f0fb` KDA `#b9c7db` |
| 行指标 | 输出占比(队色条)/承伤占比(队色条)/伤转(金 `#ffd76e` 无条) | 标签 `#6d819d` |
| 底栏 | 车队名 · AI 已评阅 | `#5c6e8a` |

- **数据与计算口径**（全部取自 `match`/`match_participant`/`match_mvp`，stats_json 字段为 Riot v5 标准名）：
  - 比分 = 双方 `kills` 合计；资源 = teams_json（tower/dragon/baron/riftHerald/voidGrub/atakhan + firstBlood）；
  - 输出占比 = 该玩家 `totalDamageDealtToChampions` ÷ 全 10 人总和；承伤占比 = `totalDamageTaken` ÷ 全 10 人总和（各 100% 口径）；
  - 伤害转化（伤转）= `totalDamageDealtToChampions` ÷ `goldEarned` × 100%；
  - 英雄中文名/头像底色：`GameDataService`（CommunityDragon）已有英雄中文名；底色为按英雄预置的固定色表（原型色值），新增英雄兜底取色板哈希；
  - MVP/尽力/背锅：本队 `match_mvp`（MVP/ACE）+ `op_score`；称号文案映射（胜方 MVP→MVP、败方 ACE→尽力、队内最低且 <5→背锅，可配置开关）。
- 渲染实现：独立的 `report-image` 组件 + 专用线程池（仿 `aiStreamExecutor`），纯函数式输入（`MatchReportData` DTO）→ PNG 字节，便于单测快照。

## 7. 局后锐评（AI）

- **Prompt**：新增 `resources/ai/post-game-prompt.md`（车队视角群聊风格：点名颁奖 🏆战神/💀战犯/🎖️奉献，火力对局不对人，正文 150-250 字，可直接发群，不输出 Markdown 标题）。输入 = 战报图同源数据的紧凑摘要 JSON。
- **调用**：仿 `WeeklyAiCommentService`（非流式、超时、`thinking=false` 直出正文、UA 伪装、10min 缓存不适用——每局唯一）；**失败重试 2 次**（指数退避），耗尽 → 走 §4 缺席提示。
- 与单局 AI 分析（`AiAnalysisService`，SSE）复用 HttpClient/线程池基建，不复用其 self 视角 prompt。

## 8. 配置（application.yml + 环境变量覆盖）

```yaml
push:
  enabled: false                  # 总开关，默认关；PUSH_ENABLED
  group-open-id: ""               # 目标群 openid（官方平台下发，入群事件可得）；PUSH_GROUP_OPEN_ID
  app-id: ""                      # 机器人 appId；QQ_BOT_APP_ID
  client-secret: ""               # 机器人 secret；QQ_BOT_CLIENT_SECRET
  recent-window-minutes: 30       # §2 条件②时间窗
  ai-comment-enabled: true        # 锐评开关（false = 只发图，不发缺席提示）
  image-enabled: true             # 战报图开关（false = 降级纯文本战报，第一版不实现纯文本战报则置 false 恒开图）
  send-interval-ms: 1000          # 保险节流
```

## 9. 失败处理矩阵

| 场景 | 行为 | 状态 |
|---|---|---|
| 上传/发图失败 | 记录 error，本局播报终止（不重试锐评），等桌面端补推重来 | FAILED |
| 发图成功、锐评失败(重试2次) | 发缺席提示 | AI_FAILED |
| 缺席提示发送失败 | 记 error，等补推重来 | FAILED |
| 群关闭主动消息(8500xx) | 记 error；连续 N 次可 warn 提醒配置 | FAILED |
| backfill / 旧局首次入库 | 时间窗外，不播报 | PENDING→迁移已置 SENT，不受影响 |

## 10. 部署与上线前置（运维清单）

1. 开发者注册 `q.qq.com` → 个人实名认证 → 创建机器人（昵称/头像/简介过审，如"XX 车队战报姬"）；
2. 管理端配置：服务范围（QQ 群）、事件方式 WebSocket、IP 白名单（云服务器出口）、内部体验号码（开发期 20 个）；
3. 车队群群主在 QQ 客户端"添加机器人" → 服务端 WS 收到 `GROUP_ADD_ROBOT` 日志取得 `group_openid` → 填入 `.env`（云服务器 `/opt/league-akari/config/.env`）；
4. 自建测试群验证主动消息可达（核对 `allow_proactive_msg`）后，把机器人拉入正式车队群；
5. 镜像：Dockerfile 增加字体 COPY 与所需开放端口（WS 出站无需入站端口）；
6. 上线顺序：先 `PUSH_ENABLED=false` 部署观察落库 → 开图关锐评 → 全开。

## 11. 相关文件

- 新建：`service/BroadcastCoordinator.java`、`qqbot/QqBotClient.java`（凭证/文本/媒体上传）、`qqbot/QqWsEventListener.java`、`service/PostGameCommentService.java`、`reportimage/ReportImageRenderer.java` + `ReportImageData.java`、`config/PushProperties.java`、`resources/ai/post-game-prompt.md`、`db/migration/V7__match_push_status.sql`
- 修改：`service/MatchService.saveMatch`（返回是否首插）、`controller/MatchController`（编排点）、`application.yml`、`Dockerfile`
- 复用：`TeamStatsService.isFleet`、`MatchMvpService`/`op_score` 数据、`GameDataService`（英雄中文名）、`HttpClientConfig`、线程池基建、`config/TeamProperties` 模式
