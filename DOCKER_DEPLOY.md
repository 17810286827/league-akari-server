# GitHub 推送自动部署到云服务器 Docker

本项目采用“两阶段 GitHub Actions”部署：GitHub 托管 Runner 构建并推送 `linux/amd64` 镜像，云服务器上的 self-hosted Runner 拉取指定 commit SHA 镜像并启动应用。

## 一次性初始化云服务器

以下命令使用 root 执行一次。Runner 本身使用专用 `deploy` 用户运行，不建议用 root 运行 GitHub Runner。

```bash
useradd --create-home --shell /bin/bash deploy
usermod -aG docker deploy
mkdir -p /opt/league-akari/config /opt/league-akari/repo
chown -R deploy:deploy /opt/league-akari
chmod 750 /opt/league-akari/config
```

安装 Docker、Docker Compose 插件，并以 `deploy` 用户安装 GitHub self-hosted runner。注册 Runner 时添加以下标签：

```text
self-hosted
linux
x64
league-akari-prod
```

### 创建生产配置

在服务器上手动创建 `/opt/league-akari/config/.env`，该文件不会被 GitHub Actions 覆盖：

```dotenv
DB_HOST=host.docker.internal
DB_PORT=3306
DB_USERNAME=your_database_user
DB_PASSWORD=replace_with_strong_password
RIOT_API_KEY=
AI_API_KEY=
# 局后播报（QQ 官方开放平台机器人，默认关闭；设计见 docs/adr/0003 与 docs/spec/post-game-broadcast.md）
PUSH_ENABLED=false
# 车队群 openid（非群号）：机器人被拉入群后服务端 WS 事件日志会提示，填入后启用
PUSH_GROUP_OPEN_ID=
# 机器人凭证（q.qq.com 应用管理）
QQ_BOT_APP_ID=
QQ_BOT_CLIENT_SECRET=
# 局后锐评开关：false = 只发战报图不调 AI（AI 不可用但想开图时用）
PUSH_AI_COMMENT_ENABLED=true
# 事件通道（WS）开关：用于收"机器人入群/退群"事件提示 openid 与保活，默认关闭
PUSH_WS_ENABLED=false
JAVA_OPTS=-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai
```

### 局后播报上线检查清单

开黑局打完后自动推送战报图 + AI 锐评到车队群，上线顺序：

1. 开放平台（q.qq.com）完成个人实名认证，机器人昵称/头像/简介过审；
2. 管理端配置服务范围（QQ 群）、事件方式 WebSocket、IP 白名单为云服务器出口 IP；
3. 群主在 QQ 客户端把机器人拉进车队群；开启 `PUSH_WS_ENABLED=true` 后从日志
   （`机器人已加入群 group_openid=...`）取得群 openid，填入 `PUSH_GROUP_OPEN_ID`；
4. 测试群实测一条主动消息可达（群侧"接收主动消息"开关关闭会导致发送失败落 FAILED）；
5. 分阶段放量：先 `PUSH_ENABLED=false` 部署观察落库 → 只发图
   （`PUSH_ENABLED=true` + `PUSH_AI_COMMENT_ENABLED=false`）→ 全开。

失败语义：发送失败落 `match.push_status=FAILED`，桌面端每 2 分钟补推错过的对局，
同一局会自动重试，无需人工介入；AI 生成失败会向群里发"缺席提示"。

```bash
chown deploy:deploy /opt/league-akari/config/.env
chmod 600 /opt/league-akari/config/.env
```

现有 MySQL 容器名为 `lol-mysql`，使用默认 `bridge` 网络并映射宿主机 `3306`。应用通过 `host.docker.internal:3306` 连接宿主机映射端口，不会重建或管理该 MySQL 容器。

## GitHub 仓库配置

在仓库的 **Settings -> Secrets and variables -> Actions** 中配置：

### Variables

```text
ALIYUN_REGISTRY=registry.cn-<region>.aliyuncs.com
ALIYUN_NAMESPACE=<阿里云命名空间>
IMAGE_NAME=league-akari-server
```

`ALIYUN_REGISTRY` 只填写仓库域名，不要带镜像名称，例如 `registry.cn-hangzhou.aliyuncs.com`。

### Secrets

```text
ALIYUN_USERNAME=<阿里云镜像仓库用户名>
ALIYUN_PASSWORD=<阿里云镜像仓库密码或访问令牌>
```

建议使用权限受限的阿里云访问凭据。GitHub Actions 不读取服务器上的业务密钥，`DB_PASSWORD`、`RIOT_API_KEY` 和 `AI_API_KEY` 只保存在服务器 `.env` 文件中。

## 自动部署流程

只有推送到 `main` 分支或手动点击 `workflow_dispatch` 才会触发：

1. GitHub 托管 Runner 使用 Java 21 执行 `mvn test`。
2. 测试通过后执行 Maven 打包并构建 `linux/amd64` 镜像。
3. 镜像推送到阿里云仓库，标签为 commit SHA 和 `latest`。
4. `league-akari-prod` Runner 自动同步代码到 `/opt/league-akari/repo`。
5. 同步时删除并重建 `repo` 目录，但不会触碰 `/opt/league-akari/config/.env`。
6. 拉取本次 SHA 镜像并执行 `docker compose up -d`。
7. 最多等待 120 秒，每 10 秒检查 `http://127.0.0.1:8081/actuator/health`。
8. 健康检查失败时标记失败镜像，并恢复上一个应用镜像；数据库迁移不会自动回滚。

工作流文件：`.github/workflows/deploy.yml`。

## 回滚状态

```text
/opt/league-akari/config/
├── .env
├── current-image
└── previous-image
```

首次部署失败时没有回滚目标，会保留现场并输出容器日志。成功部署后会保留当前版本、上一个版本和失败镜像标签；失败镜像需要按磁盘情况定期清理。

## 常用排查命令

```bash
cd /opt/league-akari/repo
docker compose --env-file /opt/league-akari/config/.env -f docker-compose.yml ps
docker compose --env-file /opt/league-akari/config/.env -f docker-compose.yml logs --tail=200 league-akari-server
curl --fail http://127.0.0.1:8081/actuator/health
docker inspect lol-mysql --format '{{json .NetworkSettings.Ports}}'
```

不要执行 `docker compose down -v`，因为该发布 Compose 不管理 MySQL，但生产环境仍应避免无意删除其他 Docker 数据卷。
