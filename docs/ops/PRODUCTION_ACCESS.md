# 生产环境通用接入手册

一线排障的**入口文档**：怎么连机器、怎么连 PG/Redis、日志在哪、怎么重启/回滚。
业务相关的排查请去 [issues/](issues/) 找对应记录；部署方案见 [DEPLOY.md](DEPLOY.md)。

> **只读优先。** 排障时默认不要改生产数据；确需 UPDATE 先 `SELECT` 圈定行数，再执行。
> **凭据一律不回写到仓库**（含本文）。口令只存在于本机 `.env` 与服务器 `/data/app/core-api/common/.env.prod`；
> 命令里用变量引用，**不要把明文口令贴进 issue / commit / 聊天窗口**。

日期：2026-10-10 ｜ 状态：现行

---

## 一、速查表

| 项 | 值 |
|---|---|
| 主机别名 | `ssh app_us1`（`~/.ssh/config`： IP、`User`、`IdentityFile`、`Port 20856`） |
| SSH 端口 | **20856**（非 22，Ubuntu 26 走 `ssh.socket` 而非改 `sshd_config`） |
| 端口转发（本机） | `25432`→PG `5432`、`26379`→Redis `6379`、`23001`→core-api `3001` |
| 生产 PG | 库 `core_api`（业务）/ `core_job`（Batch 元数据）/ `app_ops`（预留），用户 `app` |
| 生产 Redis | `localhost:6379`，口令变量名 **`REDIS_URL`**，持久化 **AOF(everysec) + RDB**（详见 §四） |
| systemd unit | **`app-core-api.service`**（带 `app-` 前缀，裸 `core-api` 查不到） |
| 环境变量 | `/data/app/core-api/common/.env.prod`（`root:app 640`） |
| 发布槽 | `/data/app/core-api/{a,b}`，`current` 软链指向 active 槽 |
| 日志 | `/data/app/log/core-api/{info,warn,error}.log`（logback）+ `core-api.log`（systemd stdout） |
| Nginx | `/data/app/log/nginx/{access,error}.log`，站点 `/etc/nginx/sites-available/api.ifmix.com` |
| 时区 | 服务器与日志时间戳均为 **UTC**（日志 pattern 显式声明 UTC） |

---

## 二、SSH 与隧道

### 2.1 隧道是「会话存活期间」才有效

`~/.ssh/config` 给 `app_us1` 配了 `LocalForward`，但 **LocalForward 随 ssh 会话存在**——
没有活跃会话时本地 `25432/26379/23001` 是关的，`psql` 会直接 `Connection refused`。

```bash
# 排障前先确认隧道端口
for p in 25432 26379 23001; do nc -z -G 2 localhost $p && echo "$p OPEN" || echo "$p CLOSED"; done
```

`CLOSED` 时起一条后台持久隧道（`ServerAliveInterval 30` 已在 config 里，够稳）：

```bash
ssh -fN app_us1          # -f 后台、-N 不执行命令；只建立转发
ssh -O check app_us1     # 若有 ControlMaster，查复用会话状态
```

> 常用一条：先跑任意 `ssh app_us1 'hostname'` 打通会话，端口转发即随之建立（会话结束转发也结束）。
> 长时间排障建议单开一个 terminal 保持连接。

### 2.2 服务器上的 sudo

普通登录用户执行管理命令需要 `sudo`（访问 `/data` 下 root 属主的 env、读日志、systemctl）：

```bash
ssh app_us1 "hostname"                      # 普通命令，不需要 sudo
ssh app_us1 "sudo systemctl restart app-core-api"
```

---

## 三、连数据库

### 3.1 本机直连（推荐，走 25432 隧道）

本机 `.env` 只有 `PROD_PG` 一个变量，值是 **jdbc 串**，剥掉 `jdbc:` 前缀即可给 `psql`：

```bash
cd /path/to/ifmix_server && set -a && . ./.env && set +a
PGURL=$(echo "$PROD_PG" | sed 's|^jdbc:||')
psql "$PGURL" -P pager=off -c "select 1;"
```

> `PROD_PG` 指向的是 `localhost:25432`，**依赖 §二 的隧道**。隧道挂了先起隧道，别怀疑密码。

常用参数：`-P pager=off`（不开分页）、`-v ON_ERROR_STOP=1`（脚本中断言）、`-c "..."` 可重复传多条。

### 3.2 服务器内连（本机没 psql 或需要绕过隧道）

```bash
ssh app_us1 "sudo -u postgres psql -d core_api -P pager=off -c 'select count(*) from core_ai_apikey;'"
```

`sudo -u postgres` 走 peer 认证，不需要口令；改 systemd/PG 参数时用它。

### 3.3 迁移（Flyway，不随启动自动执行）

```bash
DB_URL="jdbc:postgresql://localhost:25432/core_api" DB_USER=app DB_PASSWORD="$PG_PASSWORD" \
  ./gradlew :core-api:flywayMigrate
# checksum 漂移时：./gradlew :core-api:flywayRepair（只修 flyway 历史，不改表结构）
```

### 3.4 连接池上限（改配置前必读）

`max_connections=300`（PG 已调），应用侧 writer/reader 池各 50。**所有客户端池之和必须小于 `max_connections`**，
否则直接拒绝连接。详见 [DEPLOY.md](DEPLOY.md)「数据库与凭据 / 生产数据库调优」。

---

## 四、连 Redis

### 4.1 本机直连（走 26379 隧道）

口令只在服务器 env 文件里（`redis://:<password>@localhost:6379` 的形式）。两种连法**同一套 helper**：
服务器上直连 `6379`，本机走隧道时用 `26379`（`-h/-p` 换一下即可）。

```bash
# 服务器内直连（推荐，省一道转发）
ssh app_us1 'sudo bash -s' <<'EOF'
set -a; . /data/app/core-api/common/.env.prod; set +a
PW=$(printf '%s' "$REDIS_URL" | sed -E 's|^redis://:||; s|@.*$||')   # 剥出口令，不回显整串
R() { redis-cli --no-auth-warning -a "$PW" "$@"; }
R ping
R info server | grep -E "redis_version|uptime_in_days"
R info memory | grep -E "used_memory_human|maxmemory_human"
R info persistence | grep -E "^(aof_enabled|aof_last_write_status|aof_last_bgrewrite_status|rdb_last_bgsave_status)"
R config get appendonly appendfsync save dir maxmemory maxmemory-policy | paste - -
R dbsize
EOF
```

本机（走 26379 隧道）连法一样，只是加 `-h/-p`：把上面 helper 改成
`R() { redis-cli --no-auth-warning -a "$PW" -h 127.0.0.1 -p 26379 "$@"; }`（`$PW` 用 §4 的方式取回，别回显整串 URL）。

> ⚠️ **别用 `redis-cli -u "$REDIS_URL"`**：本机会报 `WRONGPASS invalid username-password pair`
> （把提取出的口令重新拼成 URL 也一样），同一个口令用 `-a` 才通。

> 变量名是 `REDIS_URL`，**没有** `SPRING_DATA_REDIS_PASSWORD` 这种拆分变量——按后者去找会白找一圈。
> 生产实例 `maxmemory=0` + `noeviction`（不会被内存驱逐）。
> **持久化（2026-10-10 起）：AOF `appendonly=yes` + `appendfsync=everysec`，同时保留 RDB 快照**
> （`save 3600 1 300 100 60 10000`）；数据目录 `/data/redis`（含 `appendonlydir/`），`systemctl restart` 不丢 key。
> 在此之前只有 RDB，`appendonly=no`，崩溃/`kill -9` 最多丢近 1 小时增量——见
> [issues/2026-10-10-redis-aof-persistence.md](issues/2026-10-10-redis-aof-persistence.md)。
> 排查「状态是不是丢了」时先看 `aof_last_write_status` / `rdb_last_bgsave_status`（不为 ok = 落盘失败），
> 而不是只看 `uptime_in_days`。

---

## 五、日志

### 5.1 位置与分工

| 文件 | 来源 | 说明 |
|---|---|---|
| `/data/app/log/core-api/info.log` | logback | INFO 及以上（含 INFO/WARN/ERROR 全量） |
| `/data/app/log/core-api/warn.log` | logback | WARN 及以上 |
| `/data/app/log/core-api/error.log` | logback | 仅 ERROR |
| `/data/app/log/core-api/core-api.log` | systemd stdout/stderr | 启动日志、JVM 参数、未捕获异常栈、**logback 起来之前的输出** |
| `/data/app/log/nginx/{access,error}.log` | Nginx | 公网入口访问 / 反代错误 |

滚动：按天 + 50MB → gzip，**留 30 天 / 3GB 上限**。只有今天（和尚未滚动的那几个）的 `warn.log` / `info.log` 是完整的，
**历史 WARN/INFO 会随时间被清掉**，追旧问题要连 `*.gz` 一起扫，或先备份日志再分析。

### 5.2 日志格式（2026-10-10 起为纯文本）

一行一条，格式（`logback-spring.xml` 的 `LOG_PATTERN`）：

```
2026-10-07 17:04:07.159 [virtual-70] ERROR c.i.c.a.m.a.s.SpringAiScanRunner [rid=0199…, pid=antique] - AI API key invalid … reason=401 keyId=ddc72b86… duration=941ms
└─ 时间(UTC) ─┘  └─ 线程 ─┘ 级别  └──── logger(缩写) ────┘ └─ MDC 上下文 ─┘   └─ message（本身是 key=value 自描述）
```

- `[…]` 里是 [LogContext](../../core-api/src/main/kotlin/com/ifmix/core/api/infra/http/LogContext.kt) 写入的
  MDC 上下文（`rid` `pid` `iid` `cid` `ip` `bot` `plat` `av` `ov` `loc` `cur` `cty`，见 `ActionContext.logFields()`），
  **虚拟线程/线程池里的日志默认没有**（MDC 不继承，见 5.3）。无上下文时是空的 `[]`。
- **异常栈在下一行**（不带异常时没有），所以按行过滤时带上 `-A` 才看得全：

```bash
# 直接捞（记得带 *.gz，否则只看到今天未滚动的部分）
ssh app_us1 'cd /data/app/log/core-api && sudo zgrep -h "关键字" *.log *.gz | tail -50'

# 带异常栈一起看（-A 20 覆盖栈）
ssh app_us1 'cd /data/app/log/core-api && sudo zgrep -h -A 20 "关键字" *.log *.gz | tail -80'

# 只要某级别（级别字段在第 3 列，用 awk 精确匹配避免误命中 message 里的 "ERROR"）
ssh app_us1 'cd /data/app/log/core-api && sudo zcat -f *.log *.gz | awk "\$3==\"ERROR\"" | tail -30'
```

> 时间戳是 **UTC**（pattern 里显式声明了 `UTC`）；跟本地时间对不上先换算（`date -u` 同步口径）。
>
> **2026-10-10 之前的历史日志是 JSON**（logstash，字段 `@timestamp`/`level`/`logger_name`/`message`），
> 两种格式会同时存在于滚动文件里。要解析旧格式：

```bash
ssh app_us1 'cd /data/app/log/core-api && sudo python3 - <<PY
import json, glob, gzip
rows=[]
for f in glob.glob("*.gz"):            # 旧格式只在历史滚动包里
    for l in gzip.open(f, "rt", errors="ignore"):
        l=l.strip()
        if not l.startswith("{"): continue     # 跳过新的文本行
        try: rows.append(json.loads(l))
        except Exception: continue
for d in sorted(rows, key=lambda d: d.get("@timestamp","")):
    if "关键字" in d["message"]:
        print(d["@timestamp"], d["level"], d["logger_name"].split(".")[-1], d["message"][:150])
PY'
```

### 5.3 虚拟线程里的日志没有上下文（MDC 不继承）

MDC 是 ThreadLocal，虚拟线程（`ThreadPerTaskExecutor`）与线程池**都不继承**父线程的值，
所以异步任务里的日志默认 `[]` 空的。要带上下文，在提交任务处快照、任务内恢复：

```kotlin
val snapshot = LogContext.snapshot()                      // 提交线程（servlet）有 rid
executor.execute { LogContext.runWith(snapshot) { work() } }   // 任务内恢复，finally 自动还原
```

任务内还可以 `LogContext.bind(ctx)` 补 `pid`/`cid` 等（一次性，本虚拟线程后续日志都带）。
参考实现见 `ScanTaskService.submit()` / `doRun()`。

---

## 六、服务管理

```bash
# 状态 / 启动时间（判断是不是刚重启过）
ssh app_us1 "sudo systemctl status app-core-api --no-pager"
ssh app_us1 "sudo systemctl show app-core-api -p ExecMainStartTimestamp -p NRestarts"

# 重启（改动生效、清进程内缓存的最快手段）
ssh app_us1 "sudo systemctl restart app-core-api"

# journald 视角（systemd 捕获的输出，含 JVM 崩溃信息）
ssh app_us1 "sudo journalctl -u app-core-api -n 100 --no-pager"

# 健康检查
ssh app_us1 "curl -fsS http://127.0.0.1:3001/actuator/health"     # 服务器本机
curl -fsS http://localhost:23001/actuator/health                  # 本机走隧道

# 实例数（多实例 = 多份进程内缓存，排查「缓存不一致」要先确认）
ssh app_us1 "sudo ss -ltnp | grep 3001"
```

### 发布 / 回滚 / 改环境变量

```bash
scripts/deploy/sync-core-api.sh            # 日常增量发布（rsync 业务层 → 切软链 → 重启 → 健康检查）
scripts/deploy/sync-core-api.sh --no-build # 跳过 gradle，用已有产物
scripts/deploy/sync-core-api.sh --full     # 应急：整包单 fat jar
scripts/deploy/rollback-core-api.sh        # 切回另一个槽（上一版）
scripts/deploy/rollback-core-api.sh --status
scripts/deploy/push-env.sh                 # 本机 .env.prod → 服务器（自动备份 + 640 root:app）
scripts/deploy/push-env.sh --restart       # 上传后重启 + 健康检查
```

> 发布脚本默认连 `ssh app_us1`（可用 `DEPLOY_SSH_HOST` 覆盖）。发布细节见 [DEPLOY.md](DEPLOY.md)。
> **只改配置不换代码**时用 `push-env.sh --restart`，不要走 `sync-core-api.sh`。

### 依赖与网络边界

Nginx（80）→ `127.0.0.1:3001` core-api → localhost `5432` PG + `6379` Redis。
PG / Redis / 3001 都**只监听 localhost**，公网入口只有 Nginx——这也是为什么几乎所有排查都得先 ssh 上服务器
（或用隧道把 localhost 端口转到本机）的原因。

### 系统自动更新：已全部关闭（2026-10-10）

原因：Ubuntu 26.04 默认的 `unattended-upgrades` 会在凌晨升级 `libxml2` 等库，随后 **needrestart 自动重启 PG**，
导致连接池连接被杀、在途事务中断。详见
[issues/2026-10-10-pg-connection-closed-by-unattended-upgrade.md](issues/2026-10-10-pg-connection-closed-by-unattended-upgrade.md)。

已执行的禁用（三处，都别轻易改回）：

```bash
# 1) apt：开关置 0 + timer 全部 mask（mask 是为了防止将来某个包升级又把它 enable 回来）
cat /etc/apt/apt.conf.d/20auto-upgrades    # Update-Package-Lists "0" / Unattended-Upgrade "0"
systemctl is-enabled apt-daily.timer apt-daily-upgrade.timer unattended-upgrades.service   # 均 masked

# 2) needrestart：有状态服务不自动重启（值 0 = 只打印提示命令，不重启）
cat /etc/needrestart/conf.d/ifmix-stateful-services.conf

# 3) snap：hold 到 90 天后（snapd 上限，需人工续期）+ fwupd 固件刷新已 disable
snap get system refresh
systemctl is-enabled fwupd-refresh.timer   # disabled
```

**手动更新流程**（自动关了，补丁得自己打，建议每月一次维护窗口）：

```bash
ssh app_us1
sudo apt update && sudo apt upgrade -y
sudo needrestart -r a          # PG/Redis 会被 override_rc 拦住，只打印提示
# → 按提示在窗口内手动重启：sudo systemctl restart postgresql / redis-server / app-core-api
sudo snap refresh              # 需要时手工拉一次
```

> **坑**：apt 配置备份文件不能放 `/etc/apt/apt.conf.d/`，扩展名不合法会让每次 `apt` 都报警告
> （`N: Ignoring file 'xxx.bak' ... invalid filename extension`）。备份放 `/var/backups/ifmix/`。
>
> **恢复自动更新**（若将来要改回去）：`systemctl unmask ... && systemctl enable --now ...`，
> 再把 `20auto-upgrades` 两项改回 `"1"`。

---

## 七、目录地图

```
/data/app/
├── core-api/
│   ├── a/                 # 版本槽 A（瘦 jar + lib/）
│   ├── b/                 # 版本槽 B
│   ├── current  -> b      # 软链，systemd WorkingDirectory/ExecStart 指向它
│   ├── common/.env.prod   # 环境变量（含全部口令，root:app 640）
│   ├── scripts/           # run-job.sh 等
│   └── core-api.jar.bak   # 切换到增量发布前留下的整包备份
└── log/
    ├── core-api/{info,warn,error,core-api}.log
    ├── core-job/
    └── nginx/{access,error}.log
```

一切都在 `/data`（数据盘 30GB，独立挂载）——**只需备份 `/data` 即覆盖 程序 + 配置 + 密钥 + 日志全套**，
这也是为什么 systemd unit 的 `WorkingDirectory`、`EnvironmentFile`、日志路径全部指向这里。

---

## 八、坑（都踩过）

1. **隧道随会话消失。** `nc -z localhost 25432` 不通 ≠ 密码错 ≠ PG 挂了，先看有没有活着的 ssh 会话（§二）。
2. **unit 名带前缀。** `systemctl status core-api` 找不到，必须是 `app-core-api.service`。
3. **Redis 变量名是 `REDIS_URL`**，不是 `SPRING_DATA_REDIS_PASSWORD`。
4. **读日志/看 env 要 sudo。** `/data/app/core-api/common/.env.prod` 是 `root:app 640`，日志目录同理。
5. **日志会滚动。** 按 30 天 / 3GB 上限清理，`info.log` / `warn.log` 的滚动比 `error.log` 快得多——
   历史 WARN/INFO 很容易查不到。追旧问题必须连 `*.gz` 一起扫，或先把日志备份下来再分析。
6. **时间是 UTC。** 跟本地时间对着看会差 8 小时。
7. **`zgrep` 要显式带 `*.gz`**，否则只能看到尚未滚动的那部分（会误判"没有这条日志"）。
8. **重启 Redis 会丢未落盘数据**（`appendonly=no`）。重启前先确认有没有状态依赖它。
9. **发布 ≠ 迁移。** Flyway 不随应用启动跑（已刻意关闭），上线含建表/改列的变更要手动 `flywayMigrate`。

---

## 九、常备 checklist（接到告警先跑这一串）

```bash
# 1. 隧道与实例
for p in 25432 26379 23001; do nc -z -G 2 localhost $p && echo "$p OK" || echo "$p DOWN"; done
ssh app_us1 "sudo systemctl show app-core-api -p ExecMainStartTimestamp -p NRestarts; sudo ss -ltnp | grep 3001"

# 2. 服务健康
ssh app_us1 "curl -fsS http://127.0.0.1:3001/actuator/health; for s in nginx postgresql redis-server; do echo -n \"\$s \"; systemctl is-active \$s; done"

# 3. 最近错误
ssh app_us1 'cd /data/app/log/core-api && sudo tail -n 50 error.log; sudo journalctl -u app-core-api -n 50 --no-pager'

# 4. Redis uptime 短 = 可能丢过冷却/会话类数据
ssh app_us1 'sudo bash -c "set -a; . /data/app/core-api/common/.env.prod; set +a; redis-cli -u \"\$REDIS_URL\" info server | grep uptime"'
```

---

## 相关文档

- [DEPLOY.md](DEPLOY.md) — 服务器初始化、蓝绿发布、Nginx、端口/凭据的完整明细
- [Changelog.md](Changelog.md) — 面向客户端/数据库的变更时间线
- [issues/](issues/) — 具体问题的排查记录，`YYYY-MM-DD-标题.md`
  （每份记录里不会再重复本文的连接命令，排障时按需回来看）
