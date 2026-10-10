# issue：PG 每几天被自动重启一次（`pg-writer - Failed to validate connection … This connection has been closed.`）

- **日期**：2026-10-10
- **状态**：**已处置（2026-10-10）**——自动更新全量关闭 + needrestart 禁止自动重启有状态服务。代码侧兜底（`max-lifetime`）未做
- **触发**：生产 `warn.log` 一批 Hikari WARN
  `pg-writer - Failed to validate connection org.postgresql.jdbc.PgConnection@68a0d15a (This connection has been closed.). Possibly consider using a shorter maxLifetime value.`
- **根因**：`unattended-upgrades` 每天 ~06:17 UTC 跑自动更新；升级 `libxml2-16`（PG 的依赖）后被 **needrestart 检测到并自动重启 postgresql**，连接池里那批 idle 连接在随后 30s 周期的 housekeeper 验证里被发现已关闭。**不是** maxLifetime 配置问题，也不是 PG 端 idle timeout。

通用连接/日志命令见 [../PRODUCTION_ACCESS.md](../PRODUCTION_ACCESS.md)。

---

## 一、证据链

时间严丝合缝，全部落在 2026-10-09 的同一分钟：

| 时刻 (UTC) | 证据 | 来源 |
|---|---|---|
| 06:17:02 | `Starting unattended upgrades script` | `/var/log/unattended-upgrades/unattended-upgrades.log` |
| 06:17:05 | `Packages that will be upgraded: libpng16-16t64 libxml2-16` | 同上 |
| 06:17:06 | apt `Start-Date`（`/usr/bin/unattended-upgrade`） | `/var/log/apt/history.log` |
| **06:17:09.092** | **`received fast shutdown request`** → `aborting any active transactions` → 10 条 `FATAL: terminating connection due to administrator command`(`app@core_api`) | `/data/postgresql/log/postgresql-2026-10-09_*.log` |
| 06:17:09.171 | `database system is shut down` | 同上 |
| 06:17:09.333 | `database system is ready to accept connections`（**0.2s 内完成重启**） | 同上 |
| 06:17:13 → 06:18:51 | 10 条 `Failed to validate connection … This connection has been closed.`（writer 5 + reader 5） | `/data/app/log/core-api/warn.log` |

两条排除项（都验证过）：

- **不是 PG 的空闲超时**：`idle_session_timeout=0`、`idle_in_transaction_session_timeout=0`、`statement_timeout=0`（全默认）。
- **不是 OOM**：`journalctl` 里 10-08～10-09 无任何 oom / killed process；内存 7.8G 中尚可 6G，swap 未用。

而 PG 日志里 `received fast shutdown request` 的历史统计也印证「周期性」：

```
2026-09-19  2    2026-09-26  2    2026-10-03  2
2026-09-20  8    2026-09-30  2    2026-10-09  2
2026-09-21  2
```

每 3～7 天一次，都是同一个剧本（10-07 那批自动更新甚至带了 `redis-server` 一起升级，同样会被 needrestart 重启）。

## 二、影响面

**很小，但不是零。**

- Hikari 在 borrow 时会先做 `isValid()` 校验，校验失败的连接不会发给业务使用，这批 WARN 全部来自 housekeeper 的 idle 巡检——即「坏连接被及时发现并踢掉重建」，属于自愈路径；
- 但 fast shutdown 那一瞬间**正在执行的事务会被 `FATAL: terminating connection due to administrator command` 打断**（10 条连接被 terminate）。若此时有写事务在途就会失败一次；
- 重启那一刻的业务表现取决于运气，白天有流量时更容易撞上。

所以：这条 WARN **本身无害**（不要据此去调 maxLifetime——那是 Hikari 的通用建议文案，误导性很强），真正要治的是「谁在没通知的情况下重启了 PG」。

## 三、处置（2026-10-10 已执行）

### 1. 关闭全部自动更新 ⭐ 根治

Ubuntu 26.04 默认 `unattended-upgrades` + 两个 apt timer 都是 enabled，`20auto-upgrades` 里 `Unattended-Upgrade "1"`。
已执行（备份在 `/var/backups/ifmix/20auto-upgrades.bak.20261010`）：

```bash
# /etc/apt/apt.conf.d/20auto-upgrades
APT::Periodic::Update-Package-Lists "0";
APT::Periodic::Unattended-Upgrade "0";

sudo systemctl disable --now apt-daily.timer apt-daily-upgrade.timer unattended-upgrades.service
sudo systemctl mask    apt-daily.timer apt-daily-upgrade.timer unattended-upgrades.service   # 防止将来被包升级重新 enable

# 顺带：云主机没有固件可更新，禁掉固件元数据刷新
sudo systemctl disable --now fwupd-refresh.timer

# snap 不走 systemd timer，由 snapd 内部调度（上限 90 天，需续期）
sudo snap set system refresh.hold="$(date -u -d '+90 days' '+%Y-%m-%dT%H:%M:%S+00:00')"
```

> 备份文件**不要放在 `/etc/apt/apt.conf.d/` 下**——扩展名不合法，会让每次 `apt` 都吐一行
> `N: Ignoring file 'xxx.bak' ... invalid filename extension`。已移到 `/var/backups/ifmix/`。

### 2. needrestart 不要把有状态服务自动重启（第二道防线）

`/etc/needrestart/needrestart.conf` 的 `blacklist` 只匹配**进程可执行文件路径**（且只列了 sudo/apt-get/memfd），
所以 PG 的 `/usr/lib/postgresql/*/bin/postgres` 被判定为需重启。

最终用 **`override_rc` 而不是 `blacklist`**——前者按 **unit 名**匹配、值为 `0` 表示「不自动重启，只打印提示命令」，
比 blacklist 的「完全忽略」更合适：升级后仍能拿到提示，由人在维护窗口执行。

```perl
# /etc/needrestart/conf.d/ifmix-stateful-services.conf
$nrconf{override_rc}{qr(^postgresql)} = 0;
$nrconf{override_rc}{qr(^redis-server)} = 0;
```

校验（确认 drop-in 被合并、值生效）：

```bash
sudo perl -e 'our %nrconf; do "/etc/needrestart/needrestart.conf";
  do $_ for sort </etc/needrestart/conf.d/*.conf>;
  print scalar keys %{$nrconf{override_rc}}, " rules; pg=", $nrconf{override_rc}{qr(^postgresql)}, "\n";'
# → 57 rules; pg=0
```

> ⚠️ 代价：库升级后 PG/Redis 会继续跑旧库，需要人工在维护窗口重启。
> 这与「凌晨自动闪断 + 偶发事务失败」相比可控得多。

### 3. 连接池侧兜底（**未做**）

`ClusterRegistry.createDataSource` 目前只设 `maximumPoolSize` / `minimumIdle`，其余走 Hikari 默认（`maxLifetime` 30min、`keepaliveTime` 关闭）。
可选显式 `max-lifetime: 600000` + `keepalive-time: 120000`。

**未做的原因**：这只是降低「陈旧连接」概率，对 PG 被突发重启无能为力（那时必然断），而根因已在上游消除。
留作后续可选优化，别把它当成这次问题的修复。

### 4. 副作用：安全更新不再自动打

自动更新关掉后，CVE 补丁需要手动。建议的维护节奏（**尚未纳入任何自动化**）：每月一次维护窗口手工执行
`apt update && apt upgrade -y` + `needrestart -r a`（此时 `override_rc` 会拦住 PG/Redis，按提示在窗口内手动重启）。

## 四、排查备忘

```bash
# 谁重启了 PG（一次看齐三条时间线）
ssh app_us1 'sudo zgrep -h "received fast shutdown request\|database system is ready" /data/postgresql/log/*.log /data/postgresql/log/*.gz | tail -20'
ssh app_us1 'sudo grep -n -A6 "Starting unattended upgrades script" /var/log/unattended-upgrades/unattended-upgrades.log | grep -E "upgrades script|Packages that will be upgraded"'
ssh app_us1 'sudo grep -B1 -A3 "Start-Date: <日期>" /var/log/apt/history.log'

# PG 是否在维护窗口外重启过（systemd 时间戳可能不准——needrestart 不走 systemctl）
ssh app_us1 'sudo systemctl show postgresql -p ExecMainStartTimestamp'
```

> **坑**：`systemctl show postgresql -p ExecMainStartTimestamp` 显示的是 **2026-09-25**，与 PG 日志里 10-09 的重启对不上——
> needrestart 是直接给 PG 发 shutdown 信号而不是通过 systemd unit，**systemd 视角的启动时间无法反映真实重启**。
> 查 PG 是否重启过，唯一可靠来源是 PG 自己的日志。
