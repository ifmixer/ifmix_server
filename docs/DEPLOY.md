# ifmix_server 部署文档（AWS t4g.xlarge / Ubuntu 26.04 ARM64）

目标机器：`ssh app_us1`（AWS Graviton `t4g.xlarge`，4 vCPU / 8 GB，ARM64/aarch64）。
本文分两部分：**一、服务器已完成的基础环境**（一次性，已做完）；**二、两种 Java 发布方案**（日常发布，二选一）。

---

## 一、服务器现状（已初始化完成）

| 项目 | 状态 |
|---|---|
| 系统盘 | 8 GB，根分区 `/` 约 7 GB |
| 数据盘 | 30 GB `nvme1n1` → ext4 → 挂载 `/data`（UUID 写入 `/etc/fstab`, `noatime,nofail`） |
| Swap | `/data/swapfile` 2 GB（`vm.swappiness=10`） |
| 系统更新 | `apt full-upgrade` 完成，内核 `7.0.0-1012-aws` 已重启生效 |
| 防火墙 | `ufw` 启用，放行 **20856/tcp (SSH)** 与 **80/tcp (HTTP)** |
| SSH | 端口改为 **20856**（非默认，降低扫描/爆破噪音）；22 已关闭。socket 激活方式（`ssh.socket`），连接：`ssh app_us1`（本地 config 已设 `Port 20856`） |
| 反向代理 | **Nginx**，`api.ifmix.com` → 反代 `127.0.0.1:3001`（core-api）；日志落 `/data/app/log/nginx/*.log` |
| 工具 | unzip, zip, fail2ban, htop, jq |
| PostgreSQL | **18.6**（Ubuntu 官方源），数据目录迁至 `/data/postgresql/18/main`，监听 `localhost:5432` |
| Redis | **8.0.5**（Ubuntu 官方源），数据目录 `/data/redis`，监听 `localhost:6379`，`requirepass` 已设 |
| JDK | Temurin **25.0.4.1 LTS**（ARM64）→ `/usr/lib/jvm/temurin-25-jdk-arm64` |
| 日志（全部落数据盘，按应用分目录） | `/data/app/log/core-api/`、`/data/app/log/core-job/`、`/data/app/log/nginx/`；PG `/data/postgresql/log/`、Redis `/data/redis/redis.log`。core-api 由 logback 分 `info.log`/`warn.log`/`error.log`（按天+50MB 滚动、gzip、留 30 天、3GB 上限），需设 `LOG_PATH=/data/app/log/core-api` |
| 防膨胀 | journald `SystemMaxUse=500M`；PG `logging_collector=on` 写数据盘；PG 数据+WAL、Redis RDB 均在 `/data` |

> **命名约定**：全部基础设施统一用 `app` 前缀（系统用户/组 `app`、目录 `/opt/app`、服务 `app-core-api`、PG 角色 `app`）。**系统盘只放程序，凡是会持续增长的（数据、WAL、各类日志）一律落 `/data`**，避免 8 GB 系统盘被撑满。

### 数据库与凭据

> 密码在初始化时随机生成，已写入服务器 `/opt/app/env`（`root:app 640`）。**请妥善保管，下面明文仅供首次记录。**

- PostgreSQL 用户 `app` / 密码 `0tTvtqzcSly3X4nzKFJHnDJ4`
  - 库 `core_api`（业务库，对应本地 `core_api_local`）
  - 库 `core_job`（Spring Batch 元数据）
  - 库 `app_ops`（预留）
- Redis 密码 `xx0epuZ2XOZcP1WfEt2d7J45`
  - 连接串：`redis://:xx0epuZ2XOZcP1WfEt2d7J45@localhost:6379`

**安全说明**：PG 与 Redis 都只监听 `localhost`，应用与数据库同机通信，不对公网暴露。core-api 的 `3001` 也只在本机监听，**不在 `ufw` 放行之列**——公网流量统一走 **Nginx（80）→ 反代 `127.0.0.1:3001`**（配置见下文「Nginx 反向代理」）。生产建议再加 443/TLS（Let's Encrypt），见该节。

### 关键：环境变量映射（`/opt/app/env`）

`core-api` 用 `application.yml` 里的占位符（无 `prod` profile，`SPRING_PROFILES_ACTIVE=prod` 只是让它不落到 `local`）：

```
SPRING_PROFILES_ACTIVE=prod
PORT=3001
PG_WRITER_URL=jdbc:postgresql://localhost:5432/core_api
PG_READER_URL=jdbc:postgresql://localhost:5432/core_api
PG_USERNAME=app
PG_PASSWORD=0tTvtqzcSly3X4nzKFJHnDJ4
REDIS_URL=redis://:xx0epuZ2XOZcP1WfEt2d7J45@localhost:6379
APP_EXPOSE_ERRORS=false
APP_HEADER_VALIDATION_STRICT=false
LOG_PATH=/data/app/log/core-api
```

`core-job` 的 `application.yml` **把库连接硬编码成了 `localhost:5432/core_api_local` / `core_job_local`**，没有占位符。用 Spring 宽松绑定（relaxed binding）环境变量覆盖：

```
APP_DATASOURCE_BUSINESS_JDBCURL=jdbc:postgresql://localhost:5432/core_api
APP_DATASOURCE_BUSINESS_USERNAME=app
APP_DATASOURCE_BUSINESS_PASSWORD=0tTvtqzcSly3X4nzKFJHnDJ4
APP_DATASOURCE_JOB_JDBCURL=jdbc:postgresql://localhost:5432/core_job
APP_DATASOURCE_JOB_USERNAME=app
APP_DATASOURCE_JOB_PASSWORD=0tTvtqzcSly3X4nzKFJHnDJ4
```

> 还需按实际补充：`STORAGE_*`（S3/R2）、`SPRING_AI_OPENAI_*`、`AUTH_*` 等。

### 必坑：`app.storage.buckets` 必须配置（否则启动失败）

用默认 profile 启动时，`application.yml` 里的 `buckets: {}`（YAML 空 flow map）会被 Spring 解析成空字符串 `""`，无法绑定到 `Map<String, BucketConfig>`，直接 **APPLICATION FAILED TO START**（本次实测踩到）。`buckets` 是嵌套 map，用索引式环境变量表达，且 `STORAGE_TYPE` 要设为 `s3`：

```
STORAGE_TYPE=s3
STORAGE_REGION=auto
STORAGE_ENDPOINT=https://<你的账户>.r2.cloudflarestorage.com
STORAGE_ACCESS_KEY=<R2 access key>
STORAGE_SECRET_KEY=<R2 secret key>
APP_STORAGE_BUCKETS_UGC_BUCKETNAME=<生产 ugc 桶名>
APP_STORAGE_BUCKETS_UGC_PUBLICURL=https://<ugc 公网域名>
APP_STORAGE_BUCKETS_STATIC_BUCKETNAME=<生产 static 桶名>
APP_STORAGE_BUCKETS_STATIC_PUBLICURL=https://<static 公网域名>
```

> ⚠️ 服务器当前 `/opt/app/env` 里**暂时填的是 local profile 的 dev R2 桶（ugcdev/staticdev）作占位以验证启动**，上线前**务必换成生产桶和生产密钥**。

### 首次数据库迁移（Flyway，任一方案上线前先跑一次）

应用启动**不再自动 migrate**（见 `core-api/build.gradle.kts` 的 `flywayMigrate` task）。上线前手动执行：

```bash
# 在本地（或 CI）针对生产库跑迁移；DB_URL/DB_USER/DB_PASSWORD 覆盖连接
DB_URL="jdbc:postgresql://<隧道>/core_api" DB_USER=app DB_PASSWORD=... \
  ./gradlew :core-api:flywayMigrate
```

生产库只监听 localhost，跑迁移时用 SSH 隧道打通：
```bash
ssh -N -L 15432:localhost:5432 app_us1 &   # 本地 15432 → 服务器 5432
DB_URL="jdbc:postgresql://localhost:15432/core_api" DB_USER=app DB_PASSWORD=0tTvtqzcSly3X4nzKFJHnDJ4 \
  ./gradlew :core-api:flywayMigrate
```

---

### Nginx 反向代理（已配置）

公网入口：`api.ifmix.com` →（80）Nginx → `127.0.0.1:3001` core-api。core-api 不直接对外，只 Nginx 监听公网。

- 站点配置：`/etc/nginx/sites-available/api.ifmix.com`（软链到 `sites-enabled/`），已禁用发行版默认站点。
- 日志：`/data/app/log/nginx/access.log` / `error.log`（随其他日志落数据盘）。
- `ufw` 放行 80/tcp；Nginx `enabled`（开机自启）。

站点配置内容：
```nginx
server {
    listen 80;
    listen [::]:80;
    server_name api.ifmix.com;

    access_log /data/app/log/nginx/access.log;
    error_log  /data/app/log/nginx/error.log;

    client_max_body_size 20m;          # 上传上限, 按需调整

    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade           $http_upgrade;
        proxy_set_header Connection        "upgrade";
        proxy_read_timeout  300s;
        proxy_send_timeout  300s;
    }
}
```

改配置后：`sudo nginx -t && sudo systemctl reload nginx`。

**DNS**：把 `api.ifmix.com` 的 A 记录指向本机公网 IP（EC2 弹性 IP）。同时 AWS **安全组**要放行入站 80（`ufw` 只是主机内层，安全组是 VPC 外层，两层都要开）。

**验证**（DNS 未生效时可用 Host 头本机测）：
```bash
curl -H "Host: api.ifmix.com" http://127.0.0.1/actuator/health   # {"status":"UP"}
```

**升级 HTTPS（生产建议）**：装 certbot 自动签发 + 续期 Let's Encrypt 证书。
```bash
ssh app_us1 'sudo apt-get install -y certbot python3-certbot-nginx && \
  sudo certbot --nginx -d api.ifmix.com --non-interactive --agree-tos -m <你的邮箱>'
sudo ufw allow 443/tcp
```
certbot 会自动改上面的 server 块加 443/TLS 并配置 80→443 跳转，续期走 systemd timer。

---

## 二、Java 发布方案（二选一）

两种方案的**共同前提**：都在本地/CI 构建 fat jar（服务器不装 Gradle、不拉依赖，避开美国网络慢的问题）。

产物：
- `core-api/build/libs/core-api-0.0.1-SNAPSHOT.jar`（Spring Boot fat jar，主类 `com.ifmix.core.api.CoreApplicationKt`）
- `core-job/build/libs/core-job-0.0.1-SNAPSHOT.jar`（fat jar）

> 注意：`*-plain.jar` 是不含依赖的普通 jar，**不要传它**。带依赖的可执行 fat jar 是不带 `-plain` 后缀的那个。构建命令：`./gradlew :core-api:bootJar :core-job:bootJar`。

---

## 方案 1：服务器本地 JDK + systemd（推荐，最省流量）

**思路**：服务器只装 JDK（已装 Temurin 25）。发布 = `scp` 一个 fat jar 上去 + 重启 systemd。fat jar 里的第三方依赖每次都在，但 `scp` 增量小、无镜像层，最贴合“网络慢、只更新业务代码”的诉求。

> 实测 `core-api` fat jar 约 **142 MB**、`core-job` 约 **19 MB**（含全部依赖）。比 Docker 全量镜像小。若要进一步压缩传输，见文末「进阶：只传业务层」。

### 服务器端已就绪（本次已配置）

- 运行用户 `app`（`--system --shell nologin`）
- 目录 `/opt/app/`（`core-api.jar`、`core-job.jar`、`env`、`scripts/`）、日志 `/data/app/log/`
- systemd 服务 `/etc/systemd/system/app-core-api.service`（常驻 web，`Restart=on-failure`，日志 append 到 `/data/app/log/core-api/core-api.log`）
- `core-job` 触发脚本 `/opt/app/scripts/run-job.sh <jobName>`（`flock -n` 防重叠，cron 调度）

### 发布流程（首次）

```bash
# 1. 本地构建
./gradlew :core-api:bootJar :core-job:bootJar

# 2. 上传（传到临时位置，避免覆盖正在运行的 jar）
scp core-api/build/libs/core-api-0.0.1-SNAPSHOT.jar app_us1:/tmp/core-api.jar
scp core-job/build/libs/core-job-0.0.1-SNAPSHOT.jar app_us1:/tmp/core-job.jar

# 3. 就位 + 迁移 + 启动（服务器上）
ssh app_us1 '
  sudo install -o app -g app -m 640 /tmp/core-api.jar /opt/app/core-api.jar
  sudo install -o app -g app -m 640 /tmp/core-job.jar /opt/app/core-job.jar
  sudo systemctl enable --now app-core-api
  sudo systemctl status app-core-api --no-pager
'
```

### 更新流程（日常，只更新业务）

```bash
./gradlew :core-api:bootJar
scp core-api/build/libs/core-api-0.0.1-SNAPSHOT.jar app_us1:/tmp/core-api.jar
ssh app_us1 '
  sudo install -o app -g app -m 640 /tmp/core-api.jar /opt/app/core-api.jar
  sudo systemctl restart app-core-api
'
# 观察启动
ssh app_us1 'journalctl -u app-core-api -n 50 --no-pager; tail -f /data/app/log/core-api/core-api.log'
```

### core-job 定时任务（cron）

```bash
# 服务器上给 app 用户加 crontab（或用 /etc/cron.d/）
ssh app_us1 'echo "30 3 * * * app /opt/app/scripts/run-job.sh anonymousCleanup >> /data/app/log/core-job/core-job.log 2>&1" | sudo tee /etc/cron.d/app-core-job'
```

### 健康检查

```bash
curl -fsS http://localhost:3001/actuator/health   # 服务器本机
```

---

## 方案 2：Docker 二阶段构建（依赖层沉底，业务层增量）

**思路**：把“不常变的依赖”烤进一个基础层，业务代码放最上层。日常发布时只有最上面的薄层变化，`docker push` 只传变化的层。适合以后依赖稳定、想要不可变镜像 + 回滚方便的场景。

### 前置：服务器装 Docker

```bash
ssh app_us1 '
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker ubuntu
'
```

### 关键技巧：Spring Boot 分层 jar（Layered Jar）

Spring Boot fat jar 天然支持分层，把依赖和业务代码拆成不同 Docker 层。`dependencies` 层（占体积的大头、几乎不变）只在依赖升级时才变，`application` 层（你的业务字节码，几 KB～几 MB）每次都变。

`Dockerfile`（放项目根，`--platform=linux/arm64` 对齐 Graviton）：

```dockerfile
# ---- Stage 1: 拆分层 ----
FROM eclipse-temurin:25-jre AS builder
WORKDIR /app
ARG JAR=core-api/build/libs/core-api-0.0.1-SNAPSHOT.jar
COPY ${JAR} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- Stage 2: 运行镜像（层顺序 = 变更频率从低到高）----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/extracted/application/ ./
ENV JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED -Xms256m -Xmx2g -XX:+UseG1GC"
EXPOSE 3001
ENTRYPOINT ["java", "-jar", "app.jar"]
```

> `dependencies` 层在 `COPY` 顺序里排在 `application` 前面，Docker 层缓存就能保证：只改业务代码时，前三层缓存命中，只有最后一层 `application` 重新构建和推送。

构建（在本地/CI，必须出 ARM64 镜像）：
```bash
./gradlew :core-api:bootJar
docker buildx build --platform linux/arm64 --build-arg JAR=core-api/build/libs/core-api-0.0.1-SNAPSHOT.jar -t app/core-api:latest --load .
```

### 镜像如何上传（这是方案 2 的核心痛点）

美国网络慢，逐一权衡：

**选项 A：AWS ECR（推荐）**
同 region 的 ECR，服务器从 ECR 拉镜像走 AWS 内网，快且稳。本地 push 到 ECR 只需传变化的层。
```bash
# 一次性：建仓库
aws ecr create-repository --repository-name app/core-api --region us-east-2
# 本地登录 + push（只传变化层）
aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin <acct>.dkr.ecr.us-east-2.amazonaws.com
docker tag app/core-api:latest <acct>.dkr.ecr.us-east-2.amazonaws.com/app/core-api:latest
docker push <acct>.dkr.ecr.us-east-2.amazonaws.com/app/core-api:latest
# 服务器拉取（走 AWS 内网）
ssh app_us1 'aws ecr get-login-password --region us-east-2 | docker login --username AWS --password-stdin <acct>.dkr.ecr.us-east-2.amazonaws.com && docker pull <acct>.dkr.ecr.us-east-2.amazonaws.com/app/core-api:latest'
```

**选项 B：`docker save` + `scp`（不想用 registry 时）**
只在依赖变了时传全量；日常只传业务增量比较难，`docker save` 是全量 tar，不划算。**不推荐日常用**，仅作应急。
```bash
docker save app/core-api:latest | zstd | ssh app_us1 'zstd -d | docker load'
```

**选项 C：服务器上直接 build**
把 fat jar `scp` 上去，在服务器上 `docker build`（基础镜像 `eclipse-temurin:25-jre` 首次拉一次后缓存）。这样每次只传 jar，不传镜像层。**如果坚持用 Docker 又嫌 push 慢，这个最实际**——但本质上和方案 1 传的东西一样多（都是那个 jar），只是多包了层 Docker。

> 结论：Docker 方案要真正省流量，**必须配 ECR（选项 A）**，靠层缓存只推业务层。否则 Docker 相比方案 1 没有传输优势，反而多一层复杂度。

### 运行（docker compose，服务器上）

`/opt/app/compose.yml`：
```yaml
services:
  core-api:
    image: <acct>.dkr.ecr.us-east-2.amazonaws.com/app/core-api:latest
    network_mode: host           # 直连本机 PG/Redis(localhost)，省去端口映射
    env_file: /opt/app/env
    restart: unless-stopped
    logging: { driver: json-file, options: { max-size: "50m", max-file: "3" } }
```
```bash
ssh app_us1 'cd /opt/app && docker compose pull && docker compose up -d'
```

---

## 两方案对比与建议

| 维度 | 方案 1（本地 JDK + systemd） | 方案 2（Docker 二阶段 + ECR） |
|---|---|---|
| 首次搭建成本 | 低（已完成） | 中（装 Docker、建 ECR、写 Dockerfile） |
| 日常更新传输量 | 一个 fat jar（60–120 MB） | 仅 `application` 层（几 MB，需 ECR 层缓存） |
| 依赖更新传输量 | 同上（整包） | `dependencies` 层（几十 MB，偶发） |
| 回滚 | 备份旧 jar 换回 + 重启 | `docker tag` 切旧镜像，秒级 |
| 环境隔离 | 依赖宿主 JDK | 完全隔离 |
| 复杂度 | 最低 | 较高 |

**建议**：现在先用**方案 1** 上线（服务器已配好，改代码就 `scp + restart`，最快）。等依赖真正稳定、需要更严格的不可变部署与快速回滚时，再切**方案 2 + ECR**——那时“只推业务层”的省流量优势才真正体现。

---

## 进阶：方案 1 也想“只传业务层”

如果日常连 60 MB 都嫌大，可用 Spring Boot 分层 jar 在服务器上就地重组，只 `scp` 变化的 `application` 层：

```bash
# 服务器上首次：把 jar 拆层到 /opt/app/extracted
ssh app_us1 'cd /opt/app && /usr/lib/jvm/temurin-25-jdk-arm64/bin/java -Djarmode=tools -jar core-api.jar extract --layers --destination extracted'
# systemd ExecStart 改为运行拆层目录：java ... -jar /opt/app/extracted/application/... (或用 JarLauncher)
```
依赖没变时，只 `rsync extracted/application/` 这一层（几 MB）。**但这增加了运维复杂度**，只在传输真的是瓶颈时才上。默认整包 `scp` 已经够用。

---

## 附录：本次执行记录与验收（2026-09-19）

### 实际执行的操作

1. **数据盘**：`nvme1n1`（空盘，`wipefs` 确认无残留）→ `mkfs.ext4 -L data` → 挂载 `/data`（UUID `00c260bd-…`，`noatime,nofail` 写入 fstab）→ 建 `/data/{postgresql,redis}`、`/opt/app`、`/data/app/log`。
2. **Swap**：`fallocate 2G /data/swapfile` → `mkswap`/`swapon` → 写 fstab → `vm.swappiness=10`。
3. **系统更新**：`apt full-upgrade`（169 包，含内核 1006→1012）→ 装 unzip/zip/ufw/fail2ban/htop/jq → `ufw allow OpenSSH` + `ufw enable` → **重启**，确认新内核 `7.0.0-1012-aws` 生效、`/data` 自动挂载。
4. **PostgreSQL**：先试 PGDG `noble` 源 → **依赖冲突失败**（`libicu74`/`libxml2` ABI 不匹配 26.04）→ 改用 **Ubuntu 官方源**装 PG 18.6 → 停服务 → `rsync` 数据目录到 `/data/postgresql/18/main` → 改 `postgresql.conf` 的 `data_directory` → 重启 → 建用户 `app`（随机密码）+ 三库 → 授权 public schema → TCP+密码登录三库全部验证通过。
5. **Redis**：Ubuntu 官方源 8.0.5（redis.io `noble` 源无更新版本）→ 数据目录改到 `/data/redis`（`chown redis:redis`）→ 设 `requirepass`（随机）→ 确认 `bind 127.0.0.1 -::1` + `protected-mode yes` → `PING`=PONG、无密码连接 `NOAUTH` 拒绝。
6. **JDK**：Adoptium 源装 Temurin 25.0.4.1 LTS（ARM64）。
7. **运行骨架**：建 `app` 系统用户（`nologin`）、`/opt/app/env`（`root:app 640`）、systemd `app-core-api.service`、`/opt/app/scripts/run-job.sh`（`flock -n` 防重叠）。

### 端到端验收（方案 1 全链路，已通过）

| 步骤 | 结果 |
|---|---|
| 本地 `gradlew :core-api:bootJar :core-job:bootJar` | 成功（core-api **142 MB**、core-job **19 MB** fat jar） |
| SSH 隧道跑 `flywayMigrate` 到 `core_api` | 成功，`public` schema **22 张表**（V1 baseline） |
| `scp` jar → `install` 到 `/opt/app` → `systemctl enable --now` | 服务 `enabled` + `active`（开机自启） |
| `curl /actuator/health` | **`{"status":"UP"}`**，Tomcat 端口 3001，启动 16.5s |

### 启动踩坑（已修复）

默认 profile 下 `app.storage.buckets`（YAML 空 map `{}`）被解析成空串，无法绑定 `Map<String,BucketConfig>` → **APPLICATION FAILED TO START**。已在 `/opt/app/env` 用索引式环境变量（`APP_STORAGE_BUCKETS_UGC_BUCKETNAME=…` 等）+ `STORAGE_TYPE=s3` 修复，重启后 `UP`。详见上文「必坑：`app.storage.buckets`」。

### 当前运行状态

- `app-core-api.service`：**运行中**（`enabled`/`active`），健康 `UP`。
- `nginx`：**运行中**（`enabled`/`active`），`api.ifmix.com` → `127.0.0.1:3001`，80/tcp 已放行。
- `core-job`：jar 已就位 `/opt/app/core-job.jar`，cron 未挂（按需 `echo "…run-job.sh anonymousCleanup" | sudo tee /etc/cron.d/app-core-job`）。
- `/data`：已用 2.1 G / 26 G 可用。

### ⚠️ 上线前必办（待处理）

1. **保管凭据**：PG `0tTvtqzcSly3X4nzKFJHnDJ4`、Redis `xx0epuZ2XOZcP1WfEt2d7J45`（在 `/opt/app/env`）。
2. **换生产存储**：`/opt/app/env` 中 storage 现为 **dev R2 占位桶（ugcdev/staticdev）**，须换成生产桶 + 生产密钥。
3. **补齐业务变量**：`SPRING_AI_OPENAI_*`（Agnes AI）、`AUTH_*`（Google/Apple JWKS、JWT 私钥）等按实际填。
4. **对外暴露**：Nginx 反代已就位（`api.ifmix.com`→80→3001），主机 `ufw` 已放行 80。**仍需**：① AWS 安全组放行入站 80/443；② `api.ifmix.com` A 记录指向本机弹性 IP；③ 配 HTTPS（certbot，见「Nginx 反向代理」节）。
5. **core-job 定时任务**：确认需要的 job 名与调度，挂 `/etc/cron.d/app-core-job`。

---

## 附录二：ifmix→app 重命名 + 日志迁移（2026-09-20）

把所有基础设施命名从 `ifmix` 统一改为 `app`，并把易膨胀的写入点全部迁到数据盘 `/data`。

### 改动清单

| 类别 | 变更 |
|---|---|
| 系统用户/组 | `usermod -l app -d /opt/app ifmix` + `groupmod -n app ifmix`（保留 uid 999/gid 987） |
| 目录 | `/opt/ifmix` → `/opt/app`；应用日志 `/var/log/ifmix` → `/data/app/log` |
| systemd | 删 `ifmix-core-api.service`，建 `app-core-api.service`（`User=app`、日志 append 到 `/data/app/log/core-api/core-api.log`） |
| env | `/opt/app/env`，DB 用户名 `ifmix`→`app`（密码不变） |
| PostgreSQL | `ALTER ROLE ifmix RENAME TO app`（密码不变，三库 owner 自动跟随）；`ifmix_ops` 库无数据 → drop 重建为 `app_ops` |
| Redis 日志 | → `/data/redis/redis.log` |
| PG 日志 | `logging_collector=on` → `/data/postgresql/log/` |
| journald | `SystemMaxUse=500M` |
| SSH 别名 | `ifmix_us1` → `app_us1`（本地 `~/.ssh/config`） |

### 踩坑：Redis systemd 沙箱不放行 `/data`（重要）

Redis 数据目录在 `/data/redis`，但 Ubuntu 的 `redis-server.service` 带 `ProtectSystem=strict`，`ReadWritePaths` 白名单只有 `/var/lib/redis`、`/var/log/redis` 等——**整个 `/data` 对 Redis 沙箱是只读的**。

- 现象：平时能启动（数据不落盘时不触发），但关机 `SIGTERM` 触发 RDB 保存时报 `Failed opening the temp RDB file ... Read-only file system`，Redis 拒绝退出、卡死在 `deactivating`。
- 这其实是**最初把 Redis 数据放 `/data/redis` 时就埋下的隐患**，数据落盘早晚必炸，不是这次改名才引入。
- 修复：加 drop-in `/etc/systemd/system/redis-server.service.d/data-paths.conf`：
  ```ini
  [Service]
  ReadWritePaths=-/data/redis
  ```
  Redis 日志也放 `/data/redis/redis.log`（属主 `redis:redis`，沙箱已放行；**不要**放 `/data/app/log`——那是 `app` 属主，`redis` 用户无权限写）。
- 验证：`BGSAVE` 成功落 `dump.rdb`、`systemctl restart` 干净完成不再卡 `deactivating`。

> 教训：往非默认路径迁移**受 systemd 沙箱保护**的服务（Redis/PostgreSQL 等）时，除了文件属主权限，还要检查 unit 的 `ProtectSystem`/`ReadWritePaths`，用 drop-in 放行目标路径。

### 验收

- 无 `ifmix` 残留：系统用户/组、`/opt`、`/var/log`、systemd、PG 角色/库、cron、`/tmp` 全部清零（另清理了 JVM 旧 `/tmp/hsperfdata_ifmix` 孤儿目录）。仅项目名 `ifmix_server`、Java 包名 `com.ifmix.*`、业务 R2 域名 `*.ifmix.com` 保留（与基础设施命名无关）。
- `app-core-api`：健康 `{"status":"UP"}`，以 `app` 用户连 PG `core_api` + Redis 成功，日志写 `/data/app/log/core-api/core-api.log`。
- Redis / PostgreSQL：`active`，日志分别落 `/data/redis/`、`/data/postgresql/log/`。

---

## 附录三：Nginx 反向代理（2026-09-20）

对外入口 `api.ifmix.com`（80）→ Nginx → `127.0.0.1:3001` core-api。

### 执行

- `apt-get install nginx`（Ubuntu 官方源）。
- 站点 `/etc/nginx/sites-available/api.ifmix.com`（`server_name api.ifmix.com`，反代 3001，转发 `X-Forwarded-*`/`Host`，`client_max_body_size 20m`，超时 300s），软链到 `sites-enabled/`，删默认站点。
- 日志落 `/data/app/log/nginx/{access,error}.log`（防膨胀，与其他日志同处；nginx 由 root master 打开日志，无 Redis 那种沙箱/属主问题）。
- `ufw allow 80/tcp`；`nginx -t` 通过；`systemctl enable nginx`。

### 验收

```
curl -H "Host: api.ifmix.com" http://127.0.0.1/actuator/health  →  {"status":"UP"} [200]
```
Nginx→core-api 反代链路打通，`nginx` `active`/`enabled`。

### 仍需（DNS/安全组/TLS）

1. **AWS 安全组**：入站放行 80（要上 HTTPS 再加 443）。`ufw` 是主机内层，安全组是 VPC 外层，两层都要开。
2. **DNS**：`api.ifmix.com` A 记录 → 本机弹性 IP。
3. **HTTPS**：`certbot --nginx -d api.ifmix.com` 自动签发+续期，`ufw allow 443/tcp`。

---

## 附录四：日志按应用分目录（2026-09-20）

`/data/app/log/` 下所有日志混放 → 改为每个应用一个子目录：

```
/data/app/log/
├── core-api/core-api.log      # systemd app-core-api 的 stdout/stderr
├── core-job/core-job.log      # cron 跑批时重定向到此(见 core-job 定时任务)
└── nginx/{access,error}.log   # nginx 站点日志
```

改动：`app-core-api.service` 的 `StandardOutput/Error` append 路径、nginx 站点的 `access_log/error_log`、cron 示例的重定向目标，全部指向对应子目录。core-api/core-job 目录属主 `app`；nginx 目录属主 `app`（nginx 由 root master 写，无碍）。

> 新增应用时：在 `/data/app/log/<应用名>/` 建目录并把该应用日志指过去，保持一应用一目录。

验收：core-api 直连 `UP`、nginx 反代 `[200]`，`core-api/core-api.log` 与 `nginx/{access,error}.log` 均正常写入。

---

## 附录五：SSH 端口 22 → 20856（2026-09-20）

把 SSH 从默认 22 改到 20856（降低公网扫描/爆破噪音）。

### Ubuntu 26 是 socket 激活，改端口要改 `ssh.socket`

Ubuntu 26 默认用 **`ssh.socket`** 做 socket 激活（`ssh.service` 是 disabled，由 socket 拉起）。**改 `sshd_config` 的 `Port` 无效**，端口由 socket 的 `ListenStream` 决定。用 drop-in 覆盖：

```ini
# /etc/systemd/system/ssh.socket.d/listen.conf
[Socket]
ListenStream=
ListenStream=0.0.0.0:20856
ListenStream=[::]:20856
```
```bash
sudo systemctl daemon-reload && sudo systemctl restart ssh.socket
```

### 踩坑：裸端口 `ListenStream=22` 只绑 IPv6，导致 IPv4 全断（差点锁死）

最初 drop-in 写的是裸端口号 `ListenStream=22` / `ListenStream=20856`。systemd 覆盖默认值后**只在 `[::]`（IPv6）监听，丢了 `0.0.0.0`（IPv4）**——从公网（IPv4 弹性 IP）连任何端口都 `Connection refused`，几乎把自己锁在门外。

- 判据：`ss -tlnp` 只看到 `[::]:22`/`[::]:20856`，没有 `0.0.0.0:` 行。
- `Connection refused`（TCP RST）≠ fail2ban（fail2ban 是 DROP=超时），据此排除封禁，定位到监听地址族问题。
- 修复：`ListenStream` **必须显式列 IPv4 + IPv6 两条**（见上）。改完 `ss` 应看到每端口各一条 `0.0.0.0:` 和 `[::]:`。

> 教训：覆盖 socket 的 `ListenStream` 时，一旦用 `ListenStream=` 清空默认，就必须把 IPv4 和 IPv6 都显式写全，裸端口号不会自动双栈。

### 安全迁移顺序（不锁死自己）

1. 确认 AWS 安全组已放行 20856（本例 `repdev-test-sg` 已有）。
2. ufw 放行 20856。
3. drop-in 让 sshd **同时监听 22 和 20856**（v4+v6 都写全），**保持旧 22 连接不断**。
4. **另开新连接用 20856 验证**能登录 + `sudo` 可用。
5. 确认无误后再改 drop-in **移除 22**，ufw 删 22，安全组撤 22 入站。
6. 本地 `~/.ssh/config` 的 `Port` 改 20856。

### 最终状态

- sshd 只监听 **20856**（IPv4+IPv6）；22 关闭。
- ufw：20856 + 80；AWS 安全组 `repdev-ssh-sg` 的 22 入站已撤销（`repdev-test-sg` 保留 20856）。
- 本地 `~/.ssh/config` 的 `app_us1` 已设 `Port 20856`，`ssh app_us1` 直接可用。
- 验收：外网连 20856 `OK`（sudo=root），连 22 `Connection refused`。

> **恢复通道备忘**：该实例是弹性 IP（`eipalloc-0b3a016d76568fa40`），未注册 SSM，但账户级 EC2 Serial Console 已启用。若再次把自己锁死且无活 session，可走 Serial Console（需先给某用户设密码）或 stop→改 user-data→start（弹性 IP 不变）来带外恢复。
