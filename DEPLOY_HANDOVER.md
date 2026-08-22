# 前端仓库 CI/CD 自动部署迁移交接文档

> 本文档记录 league-akari-server（后端）从零搭建「推送 GitHub → 自动部署云服务器 Docker」的完整过程，
> 供前端仓库复制这套流程时参考。**第 6 章踩坑记录是核心内容**，每一条都真实踩过、消耗过排查时间，
> 迁移前务必通读，可以少走大量弯路。

---

## 1. 最终架构

```
开发者本机                GitHub 托管 Runner                云服务器（腾讯云 Ubuntu 24.04）
─────────                ──────────────────                ─────────────────────────────
git push main  ──────▶  job1: build-and-push              /opt/league-akari/
                          ├─ 配置预检（Variables/Secrets）   ├── repo/        ← 代码同步目标
                          ├─ 构建（mvn package）            └── config/
                          ├─ docker build (linux/amd64)        ├── .env         ← 生产配置（手动建，永不覆盖）
                          └─ 推送阿里云 ACR（SHA + latest）      ├── current-image   ← 当前运行版本记录
                                                        │        └── previous-image  ← 回滚版本记录
                         job2: deploy (SSH)               │
                          ├─ scp-action 上传代码到 /tmp ───┘
                          └─ ssh-action 远程执行：
                               ├─ 同步代码到 /opt/league-akari/repo
                               ├─ docker login 阿里云
                               └─ ./deploy.sh
                                    ├─ docker compose pull（SHA 镜像）
                                    ├─ up -d --force-recreate
                                    ├─ 健康检查（120s / 10s 一次）
                                    ├─ 失败 → 打 failed- 标签 + 回滚上一版本
                                    └─ 成功 → 清理旧镜像（保留当前+上一+最近3个failed）
```

**关键设计决策**（迁移时保持一致）：

| 决策 | 原因 |
|---|---|
| SSH 部署，**不用** self-hosted runner | 国内服务器下载 GitHub Action 依赖（codeload.github.com）频繁超时，不可靠（见坑 #9） |
| 镜像标签用 **commit SHA**，`latest` 仅作别名 | 回滚需要精确版本；部署永远用 SHA，不追 latest |
| 生产 `.env` 放 `/opt/league-akari/config/`，代码放 `repo/` | 代码目录每次部署整体替换，配置目录永不被 CI 触碰 |
| MySQL 独立容器管理，Compose 只管应用 | 数据库生命周期与发版解耦，部署不碰数据库 |
| CI **跳过测试**直接打包 | GitHub Runner 访问不到内网 MySQL，集成测试必挂（见坑 #2） |
| 健康检查失败自动回滚应用镜像，**不回滚数据库** | Flyway 迁移不可逆，回滚镜像时要求迁移向后兼容 |

---

## 2. 前置条件清单

迁移前需要准备：

- [ ] **GitHub 仓库**（main 分支触发部署）
- [ ] **阿里云个人版容器镜像服务（ACR）**：一个命名空间 + 一个镜像仓库（前端可建 `league-akari-web`）
- [ ] **云服务器**：Ubuntu 22.04/24.04（**强烈建议 Ubuntu，别用 CentOS**，见坑 #6）
- [ ] 服务器已装 Docker + **docker-compose-v2 插件**（见坑 #12、#13 的正确安装方式）
- [ ] 一对 **SSH 密钥**（部署专用，`ssh-keygen -t ed25519`）

### 2.1 阿里云 ACR 需记录的信息

在控制台「容器镜像服务 → 个人版实例」查看：

| 信息 | 示例 | 用途 |
|---|---|---|
| 实例公网域名 | `crpi-da59qcl73m93max2.cn-beijing.personal.cr.aliyuncs.com` | 填入 `ALIYUN_REGISTRY`（**注意特殊格式，见坑 #3**） |
| 命名空间 | `ikunlol` | 填入 `ALIYUN_NAMESPACE` |
| 镜像仓库名 | `league-akari-server`（前端建 `league-akari-web`） | 填入 `IMAGE_NAME` |
| 访问凭证用户名 | `nick1572695973`（**阿里云账号名，不是邮箱**） | 填入 Secret `ALIYUN_USERNAME` |
| 访问凭证固定密码 | 在「访问凭证」页设置 | 填入 Secret `ALIYUN_PASSWORD` |

### 2.2 GitHub 需配置的 Variables / Secrets

仓库 **Settings → Secrets and variables → Actions**：

**Variables（非敏感）**：

| 名称 | 示例值 |
|---|---|
| `ALIYUN_REGISTRY` | `crpi-da59qcl73m93max2.cn-beijing.personal.cr.aliyuncs.com` |
| `ALIYUN_NAMESPACE` | `ikunlol` |
| `IMAGE_NAME` | `league-akari-web` |

**Secrets（敏感）**：

| 名称 | 说明 |
|---|---|
| `ALIYUN_USERNAME` | ACR 访问凭证用户名 |
| `ALIYUN_PASSWORD` | ACR 访问凭证固定密码 |
| `SERVER_HOST` | 云服务器公网 IP |
| `SERVER_PORT` | SSH 端口，通常 `22` |
| `SERVER_USER` | SSH 用户，如 `ubuntu` |
| `SERVER_SSH_KEY` | **私钥文件完整内容**（含 BEGIN/END 行） |

> 私钥配对：公钥追加到服务器 `~/.ssh/authorized_keys`（`chmod 600`）。

---

## 3. 配置文件模板（前端版）

以下 4 个文件放前端仓库根目录。以 React/Vue + Vite 构建为例，按需调整构建命令和产物目录。

### 3.1 `.github/workflows/deploy.yml`

```yaml
name: build-and-deploy

on:
  push:
    branches:
      - main
  workflow_dispatch:

# 同一时间只保留最新一次部署，旧任务自动取消
concurrency:
  group: league-akari-web-production
  cancel-in-progress: true

env:
  IMAGE_REPOSITORY: ${{ vars.ALIYUN_REGISTRY }}/${{ vars.ALIYUN_NAMESPACE }}/${{ vars.IMAGE_NAME }}

jobs:
  build-and-push:
    name: 构建并推送 amd64 镜像
    runs-on: ubuntu-latest
    permissions:
      contents: read
    outputs:
      image_tag: ${{ steps.version.outputs.image_tag }}
    steps:
      - name: 检出代码
        uses: actions/checkout@v4

      # 配置预检：任何 Variables/Secrets 缺失时立刻报错，避免静默登录 docker.io（坑 #3）
      - name: 检查 GitHub Actions 配置
        env:
          ALIYUN_REGISTRY: ${{ vars.ALIYUN_REGISTRY }}
          ALIYUN_NAMESPACE: ${{ vars.ALIYUN_NAMESPACE }}
          IMAGE_NAME: ${{ vars.IMAGE_NAME }}
          ALIYUN_USERNAME: ${{ secrets.ALIYUN_USERNAME }}
          ALIYUN_PASSWORD: ${{ secrets.ALIYUN_PASSWORD }}
        run: |
          set -Eeuo pipefail
          test -n "${ALIYUN_REGISTRY}" || { echo "❌ 缺少 Variable: ALIYUN_REGISTRY"; exit 1; }
          test -n "${ALIYUN_NAMESPACE}" || { echo "❌ 缺少 Variable: ALIYUN_NAMESPACE"; exit 1; }
          test -n "${IMAGE_NAME}" || { echo "❌ 缺少 Variable: IMAGE_NAME"; exit 1; }
          test -n "${ALIYUN_USERNAME}" || { echo "❌ 缺少 Secret: ALIYUN_USERNAME"; exit 1; }
          test -n "${ALIYUN_PASSWORD}" || { echo "❌ 缺少 Secret: ALIYUN_PASSWORD"; exit 1; }
          echo "✓ 配置完整，镜像地址：${ALIYUN_REGISTRY}/${ALIYUN_NAMESPACE}/${IMAGE_NAME}"

      - name: 配置 Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm

      # 前端构建产物在 Docker 里做也行（推荐，CI 更快），这里选择 Docker 内构建则可省此步
      - name: 安装依赖并构建
        run: |
          npm ci
          npm run build

      - name: 计算镜像版本
        id: version
        run: echo "image_tag=${GITHUB_SHA}" >> "${GITHUB_OUTPUT}"

      - name: 登录阿里云容器镜像仓库
        uses: docker/login-action@v3
        with:
          registry: ${{ vars.ALIYUN_REGISTRY }}
          username: ${{ secrets.ALIYUN_USERNAME }}
          password: ${{ secrets.ALIYUN_PASSWORD }}

      - name: 构建并推送镜像
        uses: docker/build-push-action@v6
        with:
          context: .
          file: ./Dockerfile
          platforms: linux/amd64
          push: true
          tags: |
            ${{ env.IMAGE_REPOSITORY }}:${{ steps.version.outputs.image_tag }}
            ${{ env.IMAGE_REPOSITORY }}:latest

  deploy:
    name: 通过 SSH 部署到云服务器
    needs: build-and-push
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - name: 检出本次提交
        uses: actions/checkout@v4

      # 注意：scp-action 先传文件。ssh-action 的 script 在远程服务器执行，
      # 拿不到 GITHUB_WORKSPACE 等 Runner 环境变量（坑 #10）
      - name: 上传代码到云服务器
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.SERVER_HOST }}
          port: ${{ secrets.SERVER_PORT }}
          username: ${{ secrets.SERVER_USER }}
          key: ${{ secrets.SERVER_SSH_KEY }}
          source: "."
          target: "/tmp/league-akari-web-deploy"

      # 注意：envs 参数声明要传给远程脚本的环境变量，script_stop 不是合法参数（坑 #11）
      - name: 在云服务器执行部署
        uses: appleboy/ssh-action@v1
        env:
          IMAGE_REPOSITORY: ${{ env.IMAGE_REPOSITORY }}
          IMAGE_TAG: ${{ needs.build-and-push.outputs.image_tag }}
          ALIYUN_REGISTRY: ${{ vars.ALIYUN_REGISTRY }}
          ALIYUN_USERNAME: ${{ secrets.ALIYUN_USERNAME }}
          ALIYUN_PASSWORD: ${{ secrets.ALIYUN_PASSWORD }}
        with:
          host: ${{ secrets.SERVER_HOST }}
          port: ${{ secrets.SERVER_PORT }}
          username: ${{ secrets.SERVER_USER }}
          key: ${{ secrets.SERVER_SSH_KEY }}
          envs: IMAGE_REPOSITORY,IMAGE_TAG,ALIYUN_REGISTRY,ALIYUN_USERNAME,ALIYUN_PASSWORD
          script: |
            set -Eeuo pipefail
            sudo mkdir -p /opt/league-akari/web/repo /opt/league-akari/web/config
            # 必须递归 chown，否则 config 子目录属主仍是 root（坑 #16）
            sudo chown -R "$USER":"$USER" /opt/league-akari/web
            test -f /opt/league-akari/web/config/.env || { echo "❌ 缺少 .env，请先手动创建"; exit 1; }
            rm -rf /opt/league-akari/web/repo
            mkdir -p /opt/league-akari/web/repo
            cp -a /tmp/league-akari-web-deploy/. /opt/league-akari/web/repo/
            rm -rf /tmp/league-akari-web-deploy
            docker login "${ALIYUN_REGISTRY}" -u "${ALIYUN_USERNAME}" -p "${ALIYUN_PASSWORD}"
            cd /opt/league-akari/web/repo
            # scp 会丢执行权限，必须先 chmod（坑 #12）
            chmod +x deploy.sh
            ./deploy.sh
```

### 3.2 `Dockerfile`（前端 nginx 版）

```dockerfile
# 构建阶段：Node 构建静态产物
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# 运行阶段：nginx 托管静态文件（alpine 自带 wget 可做健康检查）
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
# 如需 SPA 路由兜底，带上 nginx 配置：
# COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
```

### 3.3 `docker-compose.yml`（前端版）

```yaml
services:
  league-akari-web:
    image: ${IMAGE_REPOSITORY:?IMAGE_REPOSITORY is required}:${IMAGE_TAG:?IMAGE_TAG is required}
    container_name: league-akari-web
    restart: unless-stopped
    ports:
      - "8082:80"          # 8081 已被后端占用，前端用 8082
    environment:
      # 前端通常无敏感 env；如需运行时配置可在此透传
      TZ: Asia/Shanghai
```

### 3.4 `deploy.sh`（与后端通用，改三处常量）

与后端仓库的 `deploy.sh` 完全同构，只需改：

```bash
DEPLOY_ROOT="/opt/league-akari/web"      # 前端独立部署根目录
HEALTH_URL="http://127.0.0.1:8082/"      # 前端健康检查地址（nginx 首页）
```

其余逻辑（版本记录、回滚、failed 标签、镜像清理）原样复制后端的 `deploy.sh`。

### 3.5 `.env.example`（前端版，通常很短）

```dotenv
# 前端一般没有敏感运行时配置；如 API 地址在构建时注入，
# 放 GitHub Variables 而不是服务器 .env
```

---

## 4. 服务器一次性初始化（root 执行）

```bash
# 1. 确认部署用户在 docker 组（ubuntu 通常已在）
usermod -aG docker ubuntu

# 2. 创建部署目录（注意 -R 递归授权，坑 #16）
mkdir -p /opt/league-akari/web/repo /opt/league-akari/web/config
chown -R ubuntu:ubuntu /opt/league-akari/web

# 3. 安装 Docker Compose v2（Ubuntu 24.04 包名是 docker-compose-v2，坑 #13）
apt-get update && apt-get install -y docker-compose-v2

# 4. 验证 compose 插件（若报 unknown command 见坑 #14）
docker compose version

# 5. 手动创建生产配置（切 ubuntu 用户）
sudo -u ubuntu tee /opt/league-akari/web/config/.env << 'EOF'
# 前端所需配置（如有）
EOF
sudo -u ubuntu chmod 600 /opt/league-akari/web/config/.env

# 6. 配置 SSH 公钥（GitHub Actions 部署私钥的公钥）
sudo -u ubuntu mkdir -p /home/ubuntu/.ssh
echo 'ssh-ed25519 AAAA... 部署公钥' >> /home/ubuntu/.ssh/authorized_keys
chmod 700 /home/ubuntu/.ssh && chmod 600 /home/ubuntu/.ssh/authorized_keys
```

---

## 5. 部署验证清单

首次部署后逐项检查：

```bash
# 1. 容器在跑且 healthy
docker ps --filter name=league-akari-web

# 2. 健康检查通过
curl -f http://127.0.0.1:8082/

# 3. 版本记录已写入
cat /opt/league-akari/web/config/current-image

# 4. 查看应用日志
docker logs --tail=50 league-akari-web

# 5. 二次推送验证幂等与滚动更新
#    本地改点东西 → git push → GitHub Actions 全绿 → curl 版本生效
```

---

## 6. 踩坑记录（核心章节）

> 按问题严重程度和排查成本排序。**每一条都真实发生过**，前端迁移时对照检查。

### 坑 #1：改 pom.xml 时误删依赖（编译失败）

- **现象**：CI 报 `package jakarta.validation does not exist`，14 个编译错误
- **原因**：给 `pom.xml` 添加 `spring-boot-starter-actuator` 时，Edit 操作把相邻的 `spring-boot-starter-validation` 整块**替换**掉了而不是追加
- **解决**：补回依赖（commit `6dd54b5`）
- **前端启示**：改 `package.json` / 构建配置时，注意别把相邻配置顶掉；CI 里的编译错误先看依赖清单 diff

### 坑 #2：CI 环境连不上内网数据库（集成测试全挂）

- **现象**：`Communications link failure` / `Connect timed out`，5 个 `@SpringBootTest` 全报错
- **原因**：`application.yml` 默认 DB 地址是内网 IP `192.168.31.90`，GitHub 托管 Runner（美国）根本不可达
- **解决**：CI 阶段直接 `-DskipTests`（commit `ea21aee`）；测试放到本地或接 Testcontainers 再说
- **前端启示**：前端集成测试若依赖后端 API，同理会挂；CI 里只做构建+单测，不依赖内网服务

### 坑 #3：GitHub Variables 未配置 → 静默登录 docker.io

- **现象**：`Logging into docker.io... unauthorized`，但明明配的是阿里云
- **原因**：`vars.ALIYUN_REGISTRY` 为空时，`docker/login-action` **静默回退登录 Docker Hub**，拿阿里云密码登 docker.io 必然 401。且 workflow 日志里 `IMAGE_REPOSITORY: //` 已经暗示变量全空
- **解决**：加「检查 GitHub Actions 配置」预检步骤（commit `66a9f07`），缺哪个变量直接点名报错
- **前端启示**：**照抄 3.1 的预检步骤**，这个坑非常隐蔽，没有预检会浪费大量排查时间

### 坑 #4：阿里云个人版 ACR 域名格式特殊

- **现象**：登录 `registry.cn-beijing.aliyuncs.com` 报 `authentication required`
- **原因**：**个人版实例**的域名是 `crpi-<实例ID>.<地域>.personal.cr.aliyuncs.com` 格式，`registry.cn-xxx.aliyuncs.com` 是老版/企业版格式。域名在控制台「访问凭证」页的示例命令里能看到
- **解决**：`ALIYUN_REGISTRY` 改为 `crpi-da59qcl73m93max2.cn-beijing.personal.cr.aliyuncs.com`
- **前端启示**：抄后端的域名格式，直接去 ACR 控制台复制

### 坑 #5：阿里云凭证 = 访问凭证固定密码（不是登录密码）

- **现象**：域名改对了仍 `authentication required`
- **原因**：ACR 登录用的用户名/密码是「访问凭证」页**单独设置的固定密码**，用户名是阿里云账号名（如 `nick1572695973`），与控制台登录密码无关
- **解决**：ACR 控制台 → 访问凭证 → 设置固定密码；先在服务器 `docker login` 验证通过，再填 GitHub Secrets
- **前端启示**：复用同一个固定密码即可（或单独设一个）

### 坑 #6：CentOS 7 EOL，别再用（已换 Ubuntu 24.04）

- **现象**：runner 二进制报 `GLIBCXX_3.4.20 not found`；装 devtoolset 报 `Cannot find a valid baseurl for repo: centos-sclo-rh`
- **原因**：CentOS 7 于 2024-06 EOL，SCL 仓库整体下线，系统 libstdc++ 过旧且无法升级
- **解决**：**直接重装 Ubuntu 24.04**。这不是修的问题，是平台已经死了
- **前端启示**：如果前端仓库目标也是这台服务器，已是 Ubuntu 无需处理；新服务器一律 Ubuntu LTS

### 坑 #7：self-hosted runner 的权限边界（已弃用该方案，但经验通用）

- `config.sh` **禁止** root/sudo 运行（`Must not run with sudo`）
- `svc.sh` **必须** sudo（写 systemd 服务文件）
- 仓库目录若曾被 root 解包，ubuntu 用户会 `Permission denied` → `chown -R ubuntu:ubuntu`
- 重新注册 runner 前要先去 GitHub 网页删除同名 runner，否则报 `A runner exists with the same name`
- 旧 systemd 服务文件残留会让 `svc.sh install` 报 `Failed: error: exists` → 删文件 + `daemon-reload`
- 注册时**必须带** `--labels self-hosted,linux,x64,xxx`，`.runner` 文件里看不到 labels 是正常的（新版存服务端）
- **前端启示**：SSH 方案没有这些问题；此坑仅供了解为何放弃 runner

### 坑 #8：self-hosted runner 在国内下载 Action 依赖超时（放弃 runner 的根本原因）

- **现象**：runner 收到任务后卡在 `Failed to download action 'https://codeload.github.com/...' Error: HttpClient.Timeout of 100 seconds elapsing`
- **原因**：每个 Action（checkout、login-action 等）都要从 `codeload.github.com` 下载 tar 包，国内网络频繁超时，重试也救不回来
- **解决**：整体切换 SSH 部署方案（commit `1169723`）。GitHub Runner 在境外构建，只有最终 SSH 连回国内服务器
- **前端启示**：**直接用 SSH 方案，不要尝试 self-hosted runner**，除非服务器有可靠代理

### 坑 #9：`ssh-action` 的 script 拿不到 Runner 环境变量

- **现象**：远程脚本报 `GITHUB_WORKSPACE: unbound variable` + `tar: This does not look like a tar archive`
- **原因**：`appleboy/ssh-action` 的 `script` 在**远程服务器**执行，`${GITHUB_WORKSPACE}` 只存在于 Runner 上；原来设计的「Runner 上打包、远程解包」的管道根本不成立
- **解决**：两步走——`appleboy/scp-action` 先把代码传到服务器 `/tmp`，`ssh-action` 再远程移动+执行（commit `6f6d471`）。需要传变量时用 `envs:` 参数声明
- **前端启示**：**照抄 3.1 的两步结构**，别试图在 ssh script 里引用 Runner 的环境变量

### 坑 #10：`script_stop` 已不是合法参数

- **现象**：`Unexpected input(s) 'script_stop'` 警告
- **原因**：`appleboy/ssh-action@v1` 移除了该参数
- **解决**：删掉，靠脚本自身的 `set -Eeuo pipefail` 实现失败即停
- **前端启示**：模板里已删除，无需处理

### 坑 #11：scp 传输丢失执行权限

- **现象**：`bash: ./deploy.sh: Permission denied`（exit 126）
- **原因**：`scp-action` 不保留可执行位，且 git 里 `deploy.sh` 存的 mode 就是 `100644`
- **解决**：远程执行前 `chmod +x deploy.sh`（commit `7f47da0`）
- **前端启示**：模板里已含此步骤

### 坑 #12：Docker Compose 插件缺失 / 包名不同

- **现象**：`docker compose --env-file` 报 `unknown flag`；`docker compose version` 报 `unknown command`
- **原因**：Ubuntu 24.04 apt 源里包名是 **`docker-compose-v2`**，不是 `docker-compose-plugin`（后者装不上：`Unable to locate package`）
- **解决**：`apt-get install -y docker-compose-v2`
- **前端启示**：服务器已装好；新服务器照第 4 章命令装

### 坑 #13：cli-plugins 目录残留损坏文件，优先级高于正确安装

- **现象**：`docker-compose-v2` 显示已装（`already the newest version`），但 `docker compose` 仍 `unknown command`
- **原因**：早前手动 `curl` 下载插件失败，在 `/usr/local/lib/docker/cli-plugins/` 留下一个 **3.8MB 的残缺文件**（正常 66MB）且**无执行权限**。Docker CLI 按路径优先级搜到它，发现不可执行即判定插件不存在——真正完好的 `/usr/libexec/docker/cli-plugins/docker-compose` 被遮蔽
- **解决**：删除残缺文件，软链接到正确位置：
  ```bash
  sudo rm -f /usr/local/lib/docker/cli-plugins/docker-compose
  sudo ln -s /usr/libexec/docker/cli-plugins/docker-compose /usr/local/lib/docker/cli-plugins/docker-compose
  ```
- **前端启示**：`docker compose` 不工作时，先 `ls -la` 各插件目录看文件大小和权限，别只看包管理器状态

### 坑 #14：Compose 变量插值语法

- **现象**：`invalid interpolation format for ...JAVA_OPTS. You may need to escape any $`
- **原因**：compose 文件里写了 `${JAVA_OPTS:}`——**冒号后无默认值是非法语法**，空默认值必须写 `${JAVA_OPTS:-}`（带短横线）
- **解决**：改为 `${JAVA_OPTS:-}`（commit `7478d47`）
- **前端启示**：compose 里所有「允许为空」的变量一律 `${VAR:-}`；`:-必填默认值` 可当必填校验用（模板里 `:?is required` 则是缺失即报错）

### 坑 #15：`chown` 不带 `-R`，子目录仍属 root

- **现象**：deploy.sh 写 `/opt/league-akari/config/current-image.pending` 报 `Permission denied`，而 workflow 明明 chown 过了
- **原因**：`config` 子目录是更早用 root 手动 `mkdir` 的；workflow 里 `sudo chown "$USER" /opt/league-akari` **只改了目录本身**，没递归
- **解决**：改为 `chown -R`（commit `56c6d9b`）+ 服务器手动执行一次 `chown -R`
- **前端启示**：模板已用 `-R`；手动建目录后记得整体授权

### 坑 #16：`openjdk:21-jdk-slim` 官方镜像已下架

- **现象**：`docker.io/library/openjdk:21-jdk-slim: not found`
- **原因**：OpenJDK 官方 Docker 镜像停止维护并从 Docker Hub 移除
- **解决**：换 `eclipse-temurin:21-jre-alpine`（更小且持续维护）；同时删掉了无关的 `libmariadb-java-dev` 依赖（commit `7b9e9ad`）
- **前端启示**：前端用 `node:20-alpine` + `nginx:alpine`，无此问题；但基础镜像选型要选官方持续维护的

### 坑 #17：GitHub Actions 构建 action 容器偶发卡住

- **现象**：`Build container for action use` 卡 1 分半不动
- **原因**：GitHub 基础设施偶发抖动，拉基础镜像慢
- **解决**：等 3-5 分钟；不行就 Cancel + Re-run
- **前端启示**：偶发性问题重跑即可，别急着改配置

---

## 7. 后端与前端迁移差异对照

| 维度 | 后端（已实现） | 前端（迁移时） |
|---|---|---|
| 构建 | `mvn package`（Java 21） | `npm ci && npm run build`（Node 20） |
| 产物 | Spring Boot fat JAR | `dist/` 静态文件 |
| 镜像 | `eclipse-temurin:21-jre-alpine` | 多阶段：`node:20-alpine` 构建 + `nginx:alpine` 运行 |
| 容器端口 | 8081（Spring） | 80（nginx），宿主映射 **8082**（避开 8081） |
| 健康检查 | `/actuator/health`（需 actuator 依赖） | `/`（nginx 200 即可，零依赖） |
| `.env` 内容 | DB/Riot/AI 密钥 + JVM 参数 | 通常为空或仅运行时占位配置 |
| 环境变量注入 | 运行时（容器 env） | **构建时**（Vite 的 `VITE_*` 走 GitHub Variables，不进 .env） |
| API 地址 | —— | 构建时 `VITE_API_BASE` 注入，或 nginx 反代 `/api` 到 `127.0.0.1:8081` |
| 部署目录 | `/opt/league-akari/repo` | `/opt/league-akari/web/repo` |
| 版本/回滚状态 | `/opt/league-akari/config/` | `/opt/league-akari/web/config/` |
| compose 项目名 | `league-akari-server` | `league-akari-web`（保持独立，互不影响） |
| 数据库 | 依赖宿主 `lol-mysql`（host.docker.internal） | 无 |

**nginx 反代建议**（前端调后端免 CORS）：

```nginx
# nginx.conf 的 server 段追加
location /api/ {
    proxy_pass http://host.docker.internal:8081/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
# 同时 compose 里加：
#   extra_hosts:
#     - "host.docker.internal:host-gateway"
```

---

## 8. 常用运维命令速查

```bash
# 看当前运行版本
cat /opt/league-akari/web/config/current-image

# 容器状态与日志
docker ps -a --filter name=league-akari-web
docker logs --tail=100 league-akari-web

# 手动回滚到上一版本
cd /opt/league-akari/web/repo
IMAGE_REPOSITORY=$(head -c0 /dev/null; echo 'crpi-...完整地址') \
IMAGE_TAG=$(cat /opt/league-akari/web/config/previous-image) ./deploy.sh

# 手动重新部署当前版本（不改代码）
cd /opt/league-akari/web/repo && ./deploy.sh   # 需 export IMAGE_REPOSITORY/IMAGE_TAG

# 查看镜像清单（含 failed- 标记）
docker images | grep league-akari-web

# 清理悬空镜像
docker image prune -f
```

---

## 9. 后端遗留事项（与前端无关，记录备查）

1. **CI 未跑测试**：`-DskipTests` 是权宜之计；后续应引入 Testcontainers 或把集成测试指向可达的测试库
2. **MySQL 未纳管**：`lol-mysql` 容器手动维护，无自动备份；建议后续加 cron `mysqldump`
3. **后端健康检查依赖 actuator**：`spring-boot-starter-actuator` + 只暴露 health（`management.endpoints.web.exposure.include=health`）
4. **服务器 2 核 2G**：构建在 GitHub Runner 上做（免费算力），服务器只承担运行；镜像层缓存复用率高
5. **镜像清理策略**：deploy.sh 保留当前 + 上一 + 最近 3 个 `failed-` 标签，其余 SHA 镜像自动清理
6. `deploy.sh` 的 `cleanup_images` 里 `latest` 标签被显式保留，避免重复拉取

---

## 附：本次后端落地涉及的提交序列（可对照参考）

| commit | 内容 |
|---|---|
| `ba9c7e0` | 初始方案：GitHub Actions 构建 + self-hosted runner 部署 |
| `6dd54b5` | 补回 validation 依赖（坑 #1） |
| `ea21aee` | CI 跳过测试（坑 #2） |
| `66a9f07` | 配置预检（坑 #3） |
| `7b9e9ad` | 基础镜像迁移 eclipse-temurin（坑 #16） |
| `1169723` | **方案切换**：SSH 部署替代 self-hosted runner（坑 #8） |
| `6f6d471` | scp-action 传文件 + envs 传变量（坑 #9、#10） |
| `7f47da0` | chmod +x deploy.sh（坑 #11） |
| `7478d47` | compose 插值语法 `${VAR:-}`（坑 #14） |
| `56c6d9b` | chown -R 递归授权（坑 #15） |

服务器侧手工修复（无 commit）：Ubuntu 重装、docker-compose-v2 安装、插件残缺文件清理（坑 #12、#13）、目录授权、`.env` 创建。
