# issue：AI API key 已禁用却仍在被 pick（`AI API key invalid — disabling`）

- **日期**：2026-10-09
- **状态**：已定位 + 已修复（方案 A），生产未见复发
- **触发**：生产 `error.log` 一条 ERROR
  `AI API key invalid — disabling (PG enabled=false, best-effort) + cooling down. reason=401 … keyId=f443ed75-…`
  用户提问：是不是查询漏了 `enabled=true` 条件？
- **结论**：**条件没漏**。2026-10-07 那条是该 key 的**首次** 401 → 首次禁用，不是"禁用了还在用"。
  但代码里确实存在一道最多 300s 的滞后窗口（依赖 Redis 冷却兜），已修。

连接生产（SSH/PG/Redis/日志/重启）的通用命令见 [../PRODUCTION_ACCESS.md](../PRODUCTION_ACCESS.md)，本文不重复。

---

## 一、结论速查

| 现象 | 真实原因 | 处理 |
|---|---|---|
| `AI API key invalid — disabling …` ERROR | 类型化 401/403 → 该 key 被永久禁用（`enabled=false`）+ Redis 冷却 1h | 确认是否误杀，恢复见 §五 |
| `AI API key possibly invalid (message-based) …` WARN | 只有 message 短语命中、无类型化异常 → **只冷却不禁用**，1h 后自动回池 | 正常路径，观察即可 |
| `disable was a no-op (already disabled or deleted)` INFO | 禁用 UPDATE 的 `affected=0`，行早就 disabled 或已删 | 见 §六坑 3 |
| `AI key-pool degraded: …` WARN | Redis 读/写失败（fail-open 放行）或 DB 写失败（30s 限频，会丢日志） | 先看 Redis，其次 PG |
| 池子里挑出的 key 401 很多、短时批量禁用 | 多半不是池子的 bug，是 provider 侧 token 集体失效 | 见 §七级联故障 |

**"已禁用的 key 为什么还在被挑出来"**：查询确实带 `where enabled = true`（`AiConfig.loadKeys`），漏条件不成立。
真正的滞后来自进程内列表缓存 `REFRESH_TTL_SEC=300`——写 `enabled=false` 之后到下次列表重载（≤300s）之前，
本进程列表里它还在；这段窗口原先只靠同一条失败链写下的 Redis 1h 冷却兜。2026-10-09 改为写库成功即从本地列表
立即剔除（`AiApiKeyStore.evict`），**窗口归零**。

---

## 二、排查过程（生产证据）

连库命令见 [../PRODUCTION_ACCESS.md](../PRODUCTION_ACCESS.md) §三。

1. **池子健康度**：`core_ai_apikey` 3169 行 = enabled **2316** / disabled **853**。
   日志里 `pool loaded. count=2316` 与 enabled 行数**完全对得上** → 取挑确实没挑到 disabled 行。
   ```sql
   SELECT provider, enabled, count(*) FROM core_ai_apikey GROUP BY 1,2 ORDER BY 1,2;
   ```
2. **目标 keyId 只有 1 条日志**（10-07 20:23 那条），之后没再被 pick 过；
   当日实际用过的 5 个 keyId 全部 `enabled=true`。
   → 是"首次 401 → 首次禁用"，**否定**了"禁用后仍被使用"的假设。
3. **当天 15:39 UTC 有两笔批量 UPDATE**（500 + 343 行）把 key 批量禁用，带 `updated_at=now()` → 是一次人工运维动作，
   不是应用级联禁用。
4. **排除项**：单实例（`ss -ltnp | grep 3001` 只有一个 PID，无多份缓存）；
   Redis `noeviction` + `maxmemory=0`（不会被逐出）；禁用 UPDATE 不在事务内（不会回滚）；
   导入脚本 `ON CONFLICT (key) DO NOTHING`（不会重新启用已禁用的 key）。
5. **潜雷**：代码上 300s 窗口真的存在，而生产 Redis `appendonly=no`、**uptime 仅 1 天**——重启会丢冷却键。
   这次没炸，但不修迟早炸。

---

## 三、修复（2026-10-09，方案 A）

改两个文件：`modules/ai/service/AiApiKeyStore.kt` + `modules/ai/service/AiConfig.kt`

- `disableKeyFn` 签名改为 `(String) -> Boolean`
  （`true` = DB 已确认该 key 不在 enabled 集合，**含 `affected=0` 的重复禁用**；异常 → `false`）
- 新增 `AiApiKeyStore.evict()`；`disableKey()` **写成功才剔除**——
  写失败不剔除（那时 Redis 冷却 + 300s 重载是唯一兜底，提前剔除反而让还能用的 key 白掉一个轮换位）
- 剔除后列表为空时 `cached()` 判空 → 下次 `pick()` 立即重载，不会误报"空池"

测试：`AiApiKeyStoreTest` 新增 3 个用例（禁用后立刻不再被 pick / 写失败不剔除 / 剔除到空触发重载），**436 个测试通过**。
（`AiConfigDisableKeyDbTest` 需 Testcontainers 起 PG，本机 Docker daemon 未起时会失败，与本次改动无关。）

---

## 四、怎么读这个模块的日志

三条日志是一组的，**必须配对看**：

| 级别 | 日志 | 含义 |
|---|---|---|
| ERROR | `AI API key invalid — disabling (PG enabled=false, best-effort) + cooling down. reason=401 …` | 打在 runner，只表示**尝试了**禁用且没有抛异常 |
| WARN | `AI API key disabled (enabled=false). keyId=…` | 打在 `AiConfig`，`affected>0`——**真的写进去了** |
| INFO | `AI API key disable was a no-op (already disabled or deleted). keyId=…` | `affected=0`——没改到行（此前已禁用/行已删） |
| INFO | `AI API key evicted from local pool. keyId=… remaining=N` | 写成功 → 从本进程列表立即剔除（2026-10-09 起） |
| WARN | `AI key-pool degraded: …` | Redis/DB 故障降级（30s 限频，会漏） |

捞日志（JSON 解析版本见通用手册）：
```bash
ssh app_us1 'cd /data/app/log/core-api && sudo zgrep -h "AI API key\|key-pool" *.log *.gz | tail -50'
```

---

## 五、常用 SQL / 恢复手册

```sql
-- 池子健康度（enabled 行数应与日志 "AI API key pool loaded. count=N" 一致，修复后允许 ≤300s 滞后）
SELECT provider, enabled, count(*) FROM core_ai_apikey GROUP BY 1,2 ORDER BY 1,2;

-- 某个 key 的当前状态
SELECT id, enabled, type, email, created_at, updated_at, deleted_at
FROM core_ai_apikey WHERE id = '<keyId>';

-- 短期批量禁用（判断是否级联事故）
SELECT date_trunc('hour', updated_at) h, count(*)
FROM core_ai_apikey WHERE updated_at > now() - interval '2 days' GROUP BY 1 ORDER BY 1 DESC;

-- 日志里出现过的 keyId，逐个核是否在 enabled 集合
SELECT id, enabled FROM core_ai_apikey WHERE id IN ('<id1>','<id2>',...);
```

恢复（**先看行数再 UPDATE**）：
```sql
-- 单个误杀恢复
UPDATE core_ai_apikey SET enabled = true WHERE id = '<keyId>';

-- 按批次回滚某次批量禁用
SELECT count(*) FROM core_ai_apikey
WHERE enabled = false AND updated_at >= '2026-10-09 15:39:00+00' AND updated_at < '2026-10-09 15:40:00+00';
```

恢复后**不用重启**：最多 300s 后列表重载自动纳入。想立刻生效：
```bash
ssh app_us1 "sudo systemctl restart app-core-api"
```

---

## 六、坑（都踩过）

1. **`updated_at` 语义（2026-10-10 已修）**：现在的禁用会顺带写 `updated_at=now()`，
   所以 **`updated_at` = "状态被改成 disabled 的时刻"**，可以直接用来圈时间段 ✅。
   但两个前提别忘了：
   - **2026-10-10 之前**被自动禁用的行，`updated_at` 仍是插入时刻（当时只写 `enabled` 列），
     那批历史行**不能**靠 `updated_at` 推断禁用时间，只能翻滚动日志；
   -  UPDATE 带 `where enabled = true`，重复禁用是真幂等（`affected=0`，不再碰 `updated_at`）——
     否则 updated_at 会被刷成「最后一次重试时间」。
   人工脚本 SQL 的批量禁用同样会写 `updated_at`（见 §二-3 那次 15:39 的 843 行），两者从此口径一致。
2. **300s 列表滞后（已修）。** 写 `enabled=false` ≠ 立刻从轮换里消失。
   **多实例部署时每个实例各一份缓存**——巡检时 `ss -ltnp | grep 3001` 确认实例数。
3. **ERROR 措辞会误导。** `"disabling (PG enabled=false, best-effort)"` 只代表"没有抛异常"，不代表真的改到了行；
   `affected=0` 也打同一条。判断落地与否要看伴生的 WARN/INFO。
4. **Redis 重启会丢冷却。** 生产 `maxmemory=0` + `noeviction`（不会被驱逐），
   但 `appendonly=no`、RDB `save 3600 1 300 100 60 10000`——**重启会丢掉未落盘的冷却键**。
   查故障先看 `info server` 的 `uptime_in_days`。Redis 故障期间 key 池是 fail-open 放行（预期行为）。
5. **`core_ai_apikey` 的 `unavailable_until` 是死字段**，全仓库无读写逻辑，不要拿它当禁用标记。
6. **唯一约束在 `key` 而不是 id**：导入脚本用 `ON CONFLICT (key) DO NOTHING`，**不会把禁用的 key 重新启用**，
   同一批 key 也不会重复入库。
7. **不要只看 DB 现状下结论**：批量运维 SQL 会顺带改写 `updated_at`，把"何时禁用"这件事抹平，
   时间相关问题必须结合滚动日志判断。

---

## 六点五、后续变更（2026-10-10）

- **禁用补写 `updated_at`**：`AiConfig.disableKeyFn` 的 `createUpdate` 增加 `set(updatedAt, Instant.now())`，
  并把 where 收紧为 `enabled eq true`（保证重复禁用幂等、不刷新时间戳）。
  坑 1 的描述已随之改写；**2026-10-10 之前的历史禁用行 updated_at 仍不可信**。
- 相关：AI 图片拉取失败（400 + "loading IMAGE data … Timed out while downloading media URL"）原来是
  「不可重试」直接失败，现已加入有限换 key 重试——见
  [2026-10-10-ai-image-fetch-timeout-retry.md](2026-10-10-ai-image-fetch-timeout-retry.md)。

---

## 七、级联故障（provider 侧鉴权失败）

设计文档「已知取舍」已写明：provider 鉴权故障 / 网关误配 / 本机 IP 被封时，全池 key 会被逐个类型化 401/403 永久禁用。
现场特征：

- ERROR 日志几分钟内成百上千增长，每个 keyId 各一次（禁用幂等 + 每个 key 至多一条）；
- `SELECT count(*) WHERE enabled=false` 短时间跳几千；
- 同时期的 enabled key 也大面积 401（这才是根因证据）。

恢复 = 一次批量 `UPDATE ... SET enabled = true`（见 §五）。熔断/阈值化属于已知取舍范围内不做。

---

## 相关

- 设计：[../../design/ai/api-key-pool.md](../../design/ai/api-key-pool.md)（轮询/冷却基线）
- 设计：[../../design/ai/api-key-disable-and-probe-skip.md](../../design/ai/api-key-disable-and-probe-skip.md)（401/403 自动禁用）
- 通用接入：[../PRODUCTION_ACCESS.md](../PRODUCTION_ACCESS.md)
