# AGENTS.md（工作区指令）

## 项目概览

league-akari-server：英雄联盟对局同步后端。接收 LCU 客户端推送的对局数据（比赛信息 + 时间线），幂等写入 MySQL，并提供分页列表 / 详情 / 时间线查询 API。

- 技术栈：Spring Boot 3.3.5、Java 21、MyBatis-Plus 3.5.7、MySQL、Flyway、Lombok
- 构建/测试命令：`mvn test`（Maven，无 wrapper）；无 lint 配置
- 服务器：端口 8081，仅绑定 `127.0.0.1`（个人本机自用，勿放宽监听地址）
- 测试：JUnit 5 + Mockito + AssertJ；测试路径与被测文件路径保持一致（如 `src/test/java/.../service/` ↔ `src/main/java/.../service/`）

## 目录结构

- `controller/`：路由层，只做参数校验（`@Valid` / `@RequestParam`）与返回值封装，不写业务逻辑，不捕获异常
- `service/`：业务逻辑层，抛出异常（如 `MatchNotFoundException`、`IllegalArgumentException`），不处理 HTTP 语义
- `config/GlobalExceptionHandler`：统一异常转换——参数校验 → 400，对局不存在 → 404，未知异常 → 500，响应体统一 `{ code, message }`
- `dto/`：请求/响应 DTO；`entity/`：数据库实体；`mapper/`：MyBatis-Plus Mapper

## 关键约定

- **响应契约**：同步接口成功返回 `{ "code": 0 }`；详情接口包装为 `{ "data": ... }`；错误返回 `{ code, message }`
- **幂等键是 gameId**：重复推送同一 gameId 不得产生重复数据或覆盖首次写入；时间线接口要求 path 与 body 的 gameId 一致，不一致抛 `IllegalArgumentException` 返回 400
- **数据库变更走 Flyway**：迁移文件在 `src/main/resources/db/migration/`（现有 V1__init.sql、V2__match_timeline.sql）；已执行的迁移不可修改，新增表/字段必须新建 `V{n}__xxx.sql`；实体字段需加中文注释；配置已开 `map-underscore-to-camel-case`
- **注释与日志**：代码注释率不低于 20%（中文注释）；关键业务节点、异常处理、数据变更处用 `@Slf4j` 打印日志
- **提交风格**：Conventional Commits 中文描述，如 `feat(matches): ...`、`fix(timeline): ...`

# AGENTS.md（工作区指令）

## 项目概览

league-akari-server：英雄联盟对局同步后端。接收 LCU 客户端推送的对局数据（比赛信息 + 时间线），幂等写入 MySQL，并提供分页列表 / 详情 / 时间线查询 API。

- 技术栈：Spring Boot 3.3.5、Java 21、MyBatis-Plus 3.5.7、MySQL、Flyway、Lombok
- 构建/测试命令：`mvn test`（Maven，无 wrapper）；无 lint 配置
- 服务器：端口 8081，仅绑定 `127.0.0.1`（个人本机自用，勿放宽监听地址）
- 测试：JUnit 5 + Mockito + AssertJ；测试路径与被测文件路径保持一致（如 `src/test/java/.../service/` ↔ `src/main/java/.../service/`）

## 目录结构

- `controller/`：路由层，只做参数校验（`@Valid` / `@RequestParam`）与返回值封装，不写业务逻辑，不捕获异常
- `service/`：业务逻辑层，抛出异常（如 `MatchNotFoundException`、`IllegalArgumentException`），不处理 HTTP 语义
- `config/GlobalExceptionHandler`：统一异常转换——参数校验 → 400，对局不存在 → 404，未知异常 → 500，响应体统一 `{ code, message }`
- `dto/`：请求/响应 DTO；`entity/`：数据库实体；`mapper/`：MyBatis-Plus Mapper

## 关键约定

- **响应契约**：同步接口成功返回 `{ "code": 0 }`；详情接口包装为 `{ "data": ... }`；错误返回 `{ code, message }`
- **幂等键是 gameId**：重复推送同一 gameId 不得产生重复数据或覆盖首次写入；时间线接口要求 path 与 body 的 gameId 一致，不一致抛 `IllegalArgumentException` 返回 400
- **数据库变更走 Flyway**：迁移文件在 `src/main/resources/db/migration/`（现有 V1__init.sql、V2__match_timeline.sql）；已执行的迁移不可修改，新增表/字段必须新建 `V{n}__xxx.sql`；实体字段需加中文注释；配置已开 `map-underscore-to-camel-case`
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

Issues 存为 `.scratch/<feature>/` 下的 markdown 文件（本地模式），一个 feature 一个目录。见 `docs/agents/issue-tracker.md`。

### Domain docs

单上下文：根目录 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。
