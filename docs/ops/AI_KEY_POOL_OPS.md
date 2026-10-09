# AI Key 池生产排查手册

排障对象：`core_ai_apikey` 表 + `AiApiKeyStore`（进程内轮询/冷却）+ `SpringAiScanRunner`（失败分类、禁用）。
设计文档：[../design/ai/api-key-pool.md](../design/ai/api-key-pool.md)（轮询/冷却基线）、
[../design/ai/api-key-disable-and-probe-skip.md](../design/ai/api-key-disable-and-probe-skip.md)（401/403 自动禁用）。

本文是**运维/排障侧**的补充：生产环境怎么连、看哪些日志、有哪些容易误判的坑。

日期：2026-10-09 ｜ 状态：现行

---

## 一、结论速查（先读这里）

| 现象 | 真实原因 | 处理 |
|---|---|---|
| `AI API key invalid — disabling …` ERROR | 类型化 401/403 → 该 key 被永久禁用（`enabled=false`）+ Redis 冷却 1h | 确认是否误杀，恢复见 §六 |
| `AI API key possibly invalid (message-based) …` WARN | 只有 message 短语命中、无类型化异常 → **只冷却不禁用**，1h 后自动回池 | 正常路径，观察即可 |
| `disable was a no-op (already disabled or deleted)` INFO | 禁用 UPDATE 的 `affected=0`，行早就 disabled 或已删 | 见 §五坑 3 |
| `AI key-pool degraded: …` WARN | Redis 读/写失败（fail-open 放行）或 DB 写失败（30s 限频，会丢日志） | 先看 Redis，其次 PG |
| 池子里挑出的 key 401 很多、短时批量禁用 | 多半不是池子的 bug，是 provider 侧 token 集体失效（账号被封/过期） | 见 §七级联故障 |

**"已禁用的 key 为什么还在被挑出来"**：查询是带 `where enabled = true` 的（`AiConfig.loadKeys`），漏条件不成立。
真正的滞后来自进程内列表缓存 `REFRESH_TTL_SEC=300`——写 `enabled=false` 之后到下次列表重载（≤300s）之前，
本进程列表里它还在；这段窗口原先只靠同一条失败链写下的 Redis 1h 冷却兜。2026-10-09 已改为写库成功即从本地列表
立即剔除（`AiApiKeyStore.evict`），窗口归零。

---

## 二、怎么连生产

全部靠本机 `.env` 里的 `PROD_PG` 与 ssh config（`ssh app_us1` 已带 `Port 20856`）。**先确认 SSH 隧道活着**——
下面的 psql / redis-cli 转发端口都依赖它（`25432` PG、`26379` Redis、`23001` service）。

```bash
# 0) 连通性自检
nc -z -G 2 localhost 25432 && echo PG-OK
ssh -o BatchMode=yes app_us1 hostname          # 打印主机名即可（us1）

# 1) 生产 PG：本机 .env 里 PROD_PG 是 jdbc 串，去掉前缀直接给 psql
cd /path/to/ifmix_server && set -a && . ./.env && set +a
PGURL=$(echo "$PROD_PG" | sed 's|^jdbc:||')
psql "$PGURL" -P pager=off -c "select count(*) from core_ai_apikey;"
```

```bash
# 2) 生产 Redis（口令在服务器 env 文件里，不要回显）
ssh app_us1 'sudo bash -c "set -a; . /data/app/core-api/common/.env.prod; set +a
  P=\$(python3 -c \"import os,urllib.parse as u;print(u.unquote(u.urlparse(os.environ[chr(82)+chr(69)+chr(68)+chr(73)+chr(83)+chr(95)+chr(85)+chr(82)+chr(76)]).password or chr(0)),end=chr(0))\")
  redis-cli -a \$P --no-auth-warning info server | grep -E \"redis_version|uptime_in_days\"
  redis-cli -a \$P --no-auth-warning config get maxmemory maxmemory-policy
  redis-cli -a \$P --no-auth-warning --scan --pattern \"aikey:*\" | wc -l"'
```

> Redis 冷却键名格式：`aikey:{cd}:{keyId}`（`{cd}` 是 Redis Cluster hash tag，保证 MGET 命中同一 slot）。
> 口令变量名是 `REDIS_URL`（**不是** `SPRING_DATA_REDIS_PASSWORD`——那个变量生产环境没有）。
> 上面用 `chr(82)+…` 拼变量名只是为了避免把 `REDIS_URL` 写进 shell 的历史/输出污染，单纯 `os.environ["REDIS_URL"]` 一样可用。

```bash
# 3) 服务与日志
ssh app_us1
sudo systemctl status app-core-api            # unit 名带 app- 前缀
sudo systemctl show app-core-api -p ExecMainStartTimestamp
sudo tail -f /data/app/log/core-api/info.log   # logback 分 info/warn/error
sudo ss -ltnp | grep 3001                      # 是否只有一个实例在监听（多实例 = 多份列表缓存）
```

关键路径（都在 `/data/app`）：

| 用途 | 路径 |
|---|---|
| 环境变量（含 Redis/PG 口令） | `/data/app/core-api/common/.env.prod`（`root:app 640`） |
| 当前发布槽 | `/data/app/core-api/current` → 软链到 `a/` 或 `b/` |
| 日志 | `/data/app/log/core-api/{info,warn,error}.log` |

---

## 三、常用 SQL

```sql
-- 池子健康度（enabled=true 的行数应与日志 "AI API key pool loaded. count=N" 一致，允许 300s 滞后）
SELECT provider, enabled, count(*) FROM core_ai_apikey GROUP BY 1,2 ORDER BY 1,2;

-- 某个 key 的当前状态
SELECT id, enabled, type, email, created_at, updated_at, deleted_at
FROM core_ai_apikey WHERE id = '<keyId>';

-- 短期批量禁用（判断是否级联事故：看 updated_at 是否集中在几分钟内）
SELECT date_trunc('hour', updated_at) h, count(*)
FROM core_ai_apikey WHERE updated_at > now() - interval '2 days' GROUP BY 1 ORDER BY 1 DESC;

-- 日志里出现过的 keyId，逐个核是否在 enabled 集合（判断是否真的挑到了禁用 key）
SELECT id, enabled FROM core_ai_apikey
WHERE id IN ('<id1>','<id2>',...);
```

> **`updated_at` 不能用来推断"何时被自动禁用"**——见 §五坑 1。

---

## 四、日志怎么读

三条日志是一组的，必须配对看：

| 级别 | 日志 | 含义 |
|---|---|---|
| ERROR | `AI API key invalid — disabling (PG enabled=false, best-effort) + cooling down. reason=401 …` | 打在 runner，只表示**尝试了**禁用且没有抛异常 |
| WARN | `AI API key disabled (enabled=false). keyId=…` | 打在 `AiConfig`，`affected>0`——**真的写进去了** |
| INFO | `AI API key disable was a no-op (already disabled or deleted). keyId=…` | `affected=0`——没改到行（此前已禁用/行已删） |
| INFO | `AI API key evicted from local pool. keyId=… remaining=N` | 写成功 → 从本进程列表立即剔除（2026-10-09 起） |
| WARN | `AI key-pool degraded: …` | Redis/DB 故障降级（30s 限频，会漏） |

**注意日志会滚动**：`warn.log` / `info.log` 滚动清理后，历史 WARN/INFO 查不到，
只有 `error.log` 里的 ERROR 线索留存较久（但同样会滚）。要长期追，先备份日志再分析。

---

## 五、坑（都踩过）

1. **`updated_at` 不能当禁用时间。** 应用的禁用是 `createUpdate` 局部更新，只写 `enabled` 列，
   **不会动 `updated_at`**（生产验证：2026-10-07 被应用程序禁用的 8 行，`updated_at` 仍是 bulk insert 的时间）。
   反过来，`updated_at` 被改动的禁用行，多半是**人工/脚本 SQL**干的（会顺手写 `updated_at=now()`）。
   → 想靠 `updated_at` 圈时间段之前，先确认禁用来源是哪一种。
2. **300s 列表滞后（已修）。** 写 `enabled=false` ≠ 立刻从轮换里消失：进程内列表 `REFRESH_TTL_SEC=300`。
   修前有这道窗口（靠 Redis 冷却兜），修后写成功即剔除。**多实例部署时每个实例各一份缓存**——巡检时
   `ss -ltnp | grep 3001` 确认实例数。
3. **ERROR 措辞会误导。** `"disabling (PG enabled=false, best-effort)"` 只代表"没有抛异常"，
   不代表真的改到了行；`affected=0` 也打同一条。判断落地与否要看伴生的 WARN/INFO。
4. **Redis 重启会丢冷却。** 生产 Redis `maxmemory=0` + `noeviction`（不会被内存驱逐），
   但 `appendonly=no`、RDB `save 3600 1 300 100 60 10000`——**重启会丢掉尚未落盘的冷却键**。
   查故障先看 `info server` 的 `uptime_in_days`。Redis 故障期间 key 池是 fail-open 放行（预期行为）。
5. **`core_ai_apikey` 的 `unavailable_until` 是死字段**，全仓库无读写逻辑，不要拿它当禁用标记。
6. **唯一约束在 `key` 而不是 id**：重跑导入脚本用 `ON CONFLICT (key) DO NOTHING`，**不会把禁用的 key 重新启用**，
   同一批 key 也不会重复入库。
7. **不要只看 DB 现状下结论**：批量运维 SQL 会顺带改写 `updated_at`，把"何时禁用"这件事彻底抹平，
   出现时间相关问题时必须结合滚动日志判断。

---

## 六、恢复手册

```sql
-- 单个误杀恢复
UPDATE core_ai_apikey SET enabled = true WHERE id = '<keyId>';

-- 按批次恢复（例如把某次批量禁用整体回滚：先 SELECT 确认行数，再 UPDATE）
SELECT count(*) FROM core_ai_apikey
WHERE enabled = false AND updated_at >= '2026-10-09 15:39:00+00' AND updated_at < '2026-10-09 15:40:00+00';

UPDATE core_ai_apikey SET enabled = true
WHERE enabled = false AND updated_at >= '2026-10-09 15:39:00+00' AND updated_at < '2026-10-09 15:40:00+00';
```

恢复后**不用重启**：进程最多 300s 后列表重载自动纳入。想立刻生效就重启或用冷池空窗：
```bash
sudo systemctl restart app-core-api
```

---

## 七、级联故障（provider 侧鉴权失败）

设计文档「已知取舍」已写明：provider 鉴权故障 / 网关误配 / 本机 IP 被封时，全池 key 会被逐个类型化 401/403
永久禁用。现场特征：

- ERROR 日志在几分钟内成百上千增长，每个 keyId 各一次（禁用幂等 + 每个 key 至多一条）；
- `SELECT count(*) WHERE enabled=false` 短时间跳几千；
- 同时期的 enabled key 也大面积 401（这才是根因证据）。

恢复是一次批量 `UPDATE ... SET enabled = true`（见 §六）。熔断/阈值化属于已知取舍范围内不做。

---

## 八、本地核查 checklist（下次再遇到照着跑）

```bash
# 1 隧道 & 服务
nc -z -G 2 localhost 25432 && echo PG-OK
ssh app_us1 "sudo systemctl show app-core-api -p ExecMainStartTimestamp; sudo ss -ltnp | grep 3001"

# 2 DB
psql "$PGURL" -P pager=off -c "SELECT provider, enabled, count(*) FROM core_ai_apikey GROUP BY 1,2;"
psql "$PGURL" -P pager=off -c "SELECT date_trunc('hour',updated_at) h, count(*) FROM core_ai_apikey WHERE updated_at > now() - interval '2 days' GROUP BY 1 ORDER BY 1 DESC;"

# 3 Redis（uptime 短 = 冷却丢过；policy 不是 noeviction = 冷却可能被逐出）
#   见 §二-2

# 4 日志：把 ERROR/WARN/INFO 三条按 keyId 配对
ssh app_us1 'cd /data/app/log/core-api && sudo grep -h "AI API key\|key-pool\|AI key-pool" *.log *.gz | tail -50'
```
