# AGENTS.md（工作区指令）

## 项目概览

league-akari-server：英雄联盟对局同步后端。接收 LCU 客户端推送的对局数据（比赛信息 + 时间线），幂等写入 MySQL，并提供分页列表 / 详情 / 时间线查询 API。

- 技术栈：Spring Boot 3.3.5、Java 21、MyBatis-Plus 3.5.7、MySQL、Flyway、Lombok
- 构建/测试命令：`mvn test`（Maven，无 wrapper）；无 lint 配置
- 服务器：端口 8081，仅绑定 `127.0.0.1`（个人本机自用，勿放宽监听地址）
- 测试：JUnit 5 + Mockito + AssertJ；测试路径与被测文件路径保持一致（如 `src/test/java/.../service/` ↔ `src/main/java/.../service/`）

## 目录结构

- `controller/`：路由层，只做参数校验（`@Valid` / `@RequestParam`）与返回值封装（统一 `ApiResult`），不写业务逻辑，不捕获异常
- 业务逻辑按特性包组织（业务类抛 `BizException`，不处理 HTTP 语义）：
  - `match/`：对局摄取（幂等入库 + 事务内发布 `MatchSavedEvent`）、查询、时间线、AI 分析
  - `team/`：车队聚合——`FleetGameLoader`（装载/车队局判定/周口径）、`BoardEngine`（七榜单引擎）、`WeeklyReportService` / `LeaderboardService` / `MemberStatsService`（三个查询入口）、`TeamRosterService`（身份集合）
  - `riot/`：Riot 统一出口（`RiotHttpClient` 唯一出网点）、账号搜索、历史回填
  - `scoring/`：OP Score 评分引擎（评选落库 + 实时计算 + 基线累积）
  - `broadcast/`：局后播报状态机（监听 `MatchSavedEvent` AFTER_COMMIT 触发）+ 一局摘要 + 战报图/锐评投影
  - `qqbot/` `ai/` `gamedata/` `reportimage/`：QQ 推送客户端、AI 公共客户端、游戏静态资源、Java2D 渲染
- `common/`：横切设施——`web/`（ApiResult、GlobalExceptionHandler、ClientDisconnectDetector）、`exception/`（ErrorCode 全局错误登记处、BizException、QqPushException）、`stats/`（ParticipantStatsReader 统计读取门面）
- `dto/`：按域子包 `match/` `team/` `scoring/` `riot/` `common/`；`entity/`：数据库实体；`mapper/`：MyBatis-Plus Mapper

## 关键约定

- **响应契约**：所有 JSON 接口统一 `{ code, message, data }`（`common/web/ApiResult`），HTTP 一律 200，错误语义全靠业务码（0=成功；1xxx 参数 / 11xx 车队配置 / 2xxx 对局 / 3xxx 账号 / 4xxx 外部依赖 / 5xxx 系统）；`data` 有值才序列化；前端判失败只看 `code !== 0`（豁免：SSE 流、/actuator/health）
- **幂等键是 gameId**：重复推送同一 gameId 不得产生重复数据或覆盖首次写入；时间线接口要求 path 与 body 的 gameId 一致，不一致抛 `BizException(GAME_ID_MISMATCH)` 返回业务码 1002
- **数据库变更走 Flyway**：迁移文件在 `src/main/resources/db/migration/`（现有 V1~V7，最新为 V7__match_push_status.sql）；已执行的迁移不可修改，新增表/字段必须新建 `V{n}__xxx.sql`；实体字段需加中文注释；配置已开 `map-underscore-to-camel-case`
- **注释与日志**：代码注释率不低于 20%（中文注释）；关键业务节点、异常处理、数据变更处用 `@Slf4j` 打印日志
- **提交风格**：Conventional Commits 中文描述，如 `feat(matches): ...`、`fix(timeline): ...`

## 新增：Docker 部署配置

- `Dockerfile`：多阶段构建，包含 MySQL Connector-J 运行时依赖
- `docker-compose.yml`：完整的一键部署配置（应用 + MySQL）
- `.env.example`：环境变量模板
- `DOCKER_DEPLOY.md`：完整部署指南和常见问题排查

## 注意事项

- 数据库在 `192.168.31.90:3306`（虚拟机），凭据在 `application.yml`，可用环境变量 `DB_USERNAME` / `DB_PASSWORD` 覆盖；跑测试/启动需保证该 MySQL 可达
- 全局用户级指令（回答语言、分层规则等）见 `~/.zcode/AGENTS.md`，本文件只补充项目特有约束
- 工作树中可能存在进行中的未提交修改，改动相关文件前先 `git status` / `git diff` 确认现状

## 部署后建议

- 建议把 `target/*.jar` 加入 `.gitignore`（已自动处理）
- 生产环境建议使用 Kubernetes 或云厂商容器服务
- 建议添加 CI/CD 流水线（GitHub Actions / Gitee CI）

## Agent skills

### Issue tracker

Issues and specs for this repo live in GitHub Issues（`17810286827/league-akari-server`）. Use the `gh` CLI for all operations. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

单上下文：根目录 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。
