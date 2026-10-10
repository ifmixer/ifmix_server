# Redis 持久化：从「只 RDB 快照」改为「AOF + RDB」（2026-10-10）

日期：2026-10-10 ｜ 状态：已实施并验证

> 连接 Redis / sudo / 日志位置的通用命令不在此重复，见 [../PRODUCTION_ACCESS.md](../PRODUCTION_ACCESS.md) §四。
> **本文是 Redis 组件的常驻知识**：以后 Redis 落盘、容量、恢复相关问题继续在本文件追加。

---

## 一、为什么要改

Redis 原本只有 RDB 快照（`appendonly=no`），而 Debian/Ubuntu 默认没写 `save` 到 `redis.conf`，
生效的是内置默认策略 `save 3600 1 300 100 60 10000` —— 意味着：

- 写量少时（现网就是这个情况，**1 小时内变更 < 1 次都可能**），快照最长要等 **3600s** 才触发；
- 只有 `systemctl stop/restart` 这种带 SIGTERM 的关停会在退出前补一次 RDB；
- **崩溃、`kill -9`、宿主强制重启不会保存** → 丢最后一次快照之后的所有写。

依赖 Redis 的东西都是「分钟内有效」的状态：限流计数（`RateLimiter`，`INCRBY` + `PEXPIRE`）、
AI key 冷却（见 [2026-10-09-ai-api-key-invalid-disabling](2026-10-09-ai-api-key-invalid-disabling.md)）、
CacheAside 缓存。丢这些数据不会丢账，但会**放行一批本该被限流的请求 / 让刚失败过的 key 立刻复活**。

## 二、生产证据（改动前）

```bash
ssh app_us1 'sudo bash -s' <<'EOF'
set -a; . /data/app/core-api/common/.env.prod; set +a
PW=$(printf '%s' "$REDIS_URL" | sed -E 's|^redis://:||; s|@.*$||')
R() { redis-cli --no-auth-warning -a "$PW" "$@"; }
R info persistence | grep -E '^(aof_enabled|rdb_last_bgsave_status|rdb_changes_since_last_save)'
R config get appendonly appendfsync save dir maxmemory maxmemory-policy | paste - -
R info memory | grep -E '^(used_memory_human|maxmemory_human)'
R dbsize
EOF
```

结果：

| 项 | 改前值 |
|---|---|
| Redis 版本 | 8.0.5（Ubuntu 官方源） |
| `appendonly` | **no** |
| `appendfsync` | everysec（一直是对的） |
| `save` | `3600 1 300 100 60 10000`（默认） |
| `dir` | `/data/redis` |
| `maxmemory` / 策略 | `0` / `noeviction` |
| 数据量 | `used_memory 1.00M`，`dbsize 1` |
| 数据盘可用 | `/data` 26G / 共 30G |

结论：**容量完全不构成约束**（AOF 要到 64MB 才触发 rewrite，`auto-aof-rewrite-min-size` 也是 64mb），
唯一缺的就是 AOF。

## 三、处置（2026-10-10）

原则：**先在线生效，再写回配置文件，最后重启验证**——避免直接改文件 + 重启导致中间状态无人负责。

```bash
ssh app_us1 'sudo bash -s' <<'EOF'
set -eu
set -a; . /data/app/core-api/common/.env.prod; set +a
PW=$(printf '%s' "$REDIS_URL" | sed -E 's|^redis://:||; s|@.*$||')
R() { redis-cli --no-auth-warning -a "$PW" "$@"; }

# 1) 备份配置（按 /var/backups/ifmix/ 的约定，不放 /etc 下）
mkdir -p /var/backups/ifmix
cp -a /etc/redis/redis.conf "/var/backups/ifmix/redis.conf.$(date +%F).bak"

# 2) 在线开启 AOF，等 background rewrite 完成
R config set appendonly yes
R config set appendfsync everysec
until [ "$(R info persistence | tr -d '\r' | grep -c '^aof_rewrite_in_progress:0$')" = 1 ]; do sleep 1; done

# 3) 写回配置，保证重启后仍是 yes（只改锚定的一行，不动注释）
sed -i 's/^appendonly no$/appendonly yes/' /etc/redis/redis.conf
diff /var/backups/ifmix/redis.conf.$(date +%F).bak /etc/redis/redis.conf   # 应只有 1 行差

# 4) 重启验证
systemctl restart redis-server
R info persistence | grep -E '^(aof_enabled|aof_last_bgrewrite_status|aof_last_write_status)'
R config get appendonly | paste - -
curl -fsS http://127.0.0.1:3001/actuator/health
EOF
```

改动本身：`/etc/redis/redis.conf` **一行**，`appendonly no` → `appendonly yes`（其它全部保持默认）。

### 为什么没用 `CONFIG REWRITE`

`CONFIG REWRITE` 会重写整个 `redis.conf`，重排/丢弃原始注释与排版（Debian 那份注释是主要的说明文档）。
只改一行更可审计、diff 更干净 —— 代价是以后**改 Redis 持久化配置要记得同时改两处**（运行时 + 文件）。

### 为什么保留 RDB（`save` 未改）

- AOF 负责「少丢」（`everysec` → 最多 1s 增量）；
- RDB 快照负责「快速载入 + 有一份独立的时间点副本」，且 `kill -9` 场景下 AOF 也能恢复后，RDB 只是锦上添花；
- 数据量 1MB 级别，`BGSAVE` 几乎无开销（fork-CoV ~0MB），没必要为了省这点 IO 而关掉。

## 四、验证结果

| 检查 | 结果 |
|---|---|
| 重启后 `appendonly` | `yes`（说明配置文件已生效，不是只在内存里） |
| 重启后 `aof_enabled` | `1`，`aof_last_bgrewrite_status:ok`、`aof_last_write_status:ok` |
| AOF 目录 | `/data/redis/appendonlydir/`（`appendonly.aof.1.base.rdb` + `.incr.aof` + `manifest`），属主 `redis:redis` |
| systemd 沙箱 | 未阻写（此前已加 drop-in `ReadWritePaths=-/data/redis`，见 DEPLOY.md 附录二） |
| 数据保留 | 重启前后 `dbsize` 与 key 一致 |
| 写路径落盘 | 写入临时 key 后 2s，`SET`/`PXAT` 已出现在 `appendonly.aof.1.incr.aof` 里 |
| core-api | `{"status":"UP"}`，未重启（Lettuce 自动重连） |

## 五、回退

回退到「只 RDB」要**连带清理 AOF 文件**，否则 restart 仍会从 AOF 加载：

```bash
R config set appendonly no
sed -i 's/^appendonly yes$/appendonly no/' /etc/redis/redis.conf
systemctl restart redis-server
# 确认 aof_enabled:0 后再删（留着不删只是占空间、且下次手动开 AOF 会用它当 base）
rm -rf /data/redis/appendonlydir
```

> 反向操作（临时需要纯内存跑）用 `R config set appendonly no`，**不要**用 `config set save ""` 关 RDB
> ——后者会让 SIGTERM 关停不再落盘，两头不靠。

## 六、Redis 排障要点（下次同模块出问题先看这里）

### 6.1 一条命令看持久化健康度

```bash
R info persistence | grep -E '^(loading|aof_enabled|aof_last_write_status|aof_last_bgrewrite_status|aof_rewrite_in_progress|rdb_last_bgsave_status|rdb_changes_since_last_save)'
```

判定：

- `aof_last_write_status:ok` —— AOF 写异常时这里会不是 ok，**此时 Redis 会拒绝写入**（见 §6.4）；
- `rdb_last_bgsave_status:err` —— 多半是 `/data` 满了或权限问题，看 `/data/redis/redis.log`；
- `aof_rewrite_in_progress:1` 长期不降 —— 磁盘 IO 卡住或 rewrite 被拒绝。

### 6.2 坑：`redis-cli -u "$REDIS_URL"` 在生产认证失败

本例会直接 `WRONGPASS invalid username-password pair`（即使把提取出的口令重新拼成 URL 也一样），
但**同一个口令用 `-a` 就通**。所以连 Redis 一律用：

```bash
PW=$(printf '%s' "$REDIS_URL" | sed -E 's|^redis://:||; s|@.*$||')
redis-cli --no-auth-warning -a "$PW" ping      # ← 用这个
redis-cli -u "$REDIS_URL" ping                 # ✗ 别用
```

（`--no-auth-warning` 是为了去掉 stderr 上那句 "Using a password ... may not be safe"，避免污染脚本输出。）

### 6.3 容量与 rewrite

`auto-aof-rewrite-percentage 100` + `auto-aof-rewrite-min-size 64mb`：AOF 增长到 64MB 且比上次 rewrite 后
大一倍时自动压缩。当前数据量离 64MB 极远，**短期内不会触发 rewrite**。要监控的是 `/data` 剩余空间
（`df -h /data`）而不是 Redis `used_memory`。

### 6.4 写入失败的默认行为（重要）

Redis 在 `appendfsync` 连续失败 / RDB bgsave 失败且 `stop-writes-on-bgsave-error yes`（现设）时，
**会停止接受写命令**。本服务对 Redis 是 fail-open 设计（见 ai-api-key 那篇的降级说明），
但 Redis 报错变多时先看持久化状态，而不是先看应用代码。

> 权衡说明：这里刻意**保留** `stop-writes-on-bgsave-error yes`（宁可写失败暴露问题，也不要静默写进一个落不了盘的实例）。
> 若以后 Redis 换成「可接受随时清空」的纯缓存语义，再把它改成 `no`。

### 6.5 遗留项

1. **`vm.overcommit_memory=0`**：Redis 启动日志仍有 `WARNING Memory overcommit must be enabled!`。
   背景保存依赖 fork CoW，值 0 时在内存紧张下可能失败。当前数据量小（1MB）不会触发，暂未改；
   真要改：`sysctl vm.overcommit_memory=1` + 写 `/etc/sysctl.conf`（属主机配置变更，需走变更记录）。
2. **`db0` 里有一个无 TTL 的 key `a`**（`type=string`，疑似早期手工测试残留）。未删除——删生产数据前确认。
3. Redis 现在的数据基本为空（`dbsize 1`），现网几乎没有写流量；AOF 的价值在「万一有状态的那一刻」而不是当下。

---

## 相关

- [../PRODUCTION_ACCESS.md](../PRODUCTION_ACCESS.md) — SSH/隧道、连 Redis/连 PG、日志、重启回滚的通用入口
- [2026-10-09-ai-api-key-invalid-disabling](2026-10-09-ai-api-key-invalid-disabling.md) — Redis 冷却键丢失时的误判风险
