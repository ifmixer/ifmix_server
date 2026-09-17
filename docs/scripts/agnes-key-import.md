# Agnes AI Key 导入

把 Agnes AI 的 API key 导入 `core_api_local.ai_agnes_key` 表。脚本位于 `scripts/agnes_keys/`。

## 目标表 `ai_agnes_key`（infra 级全局资源，不按 project 隔离）

| 列 | 说明 |
|----|------|
| `id` | UUID，导入时用 `gen_random_uuid()`（v4）生成 |
| `key` | API key，**唯一索引** `agnes_key_uq`，去重靠它 |
| `email` | 关联邮箱（见配对规则） |
| `type` | **NOT NULL 无默认值**。`10`=PERSONAL（`sk-` 前缀）/ `20`=ENTERPRISE（`wk-` 前缀）。码表见 `entity/ai/AgnesKey.kt` 的 `AgnesKeyTypes` |
| `rate_limit` / `window_sec` | 走表默认（-1 / 86400） |

`type` 码表登记于 `entity/ai/AgnesKey.kt`（`AgnesKeyTypes`）和 `docs/DATABASE.md` 枚举表。

---

## `scripts/agnes_keys/import_from_register.sh`（从 `agnes_register.accounts` 批量导入）

从注册系统的 `agnes_register.accounts` 表批量导入。**日常新增账号后重复执行**这个即可（幂等）。

### 配对规则（一个 account 最多产 2 行，分开存）

| 行类型 | 触发条件 | `key` | `email` | `type` |
|--------|----------|-------|---------|--------|
| personal | `personal_api_key` 非空 | `personal_api_key` | `reg_email` | 10 |
| enterprise | `ent_api_key` 非空 | `ent_api_key` | `COALESCE(ent_email, reg_email)`（无 ent_email 回退 reg_email） | 20 |

- 两个 key 都为空 → **跳过**。
- **不按 `status` 过滤**，只看 key 有无（`completed`/`pending`/`failed` 都会导，只要有 key）。

### 用法

```bash
# 默认：agnes_register -> core_api_local，localhost，postgres/postgres
bash scripts/agnes_keys/import_from_register.sh

# 换库/换连接（环境变量覆盖）
SRC_DB=agnes_register DST_DB=core_api PGHOST=some-host PGUSER=admin PGPASSWORD=secret \
  bash scripts/agnes_keys/import_from_register.sh
```

支持的环境变量：`PGHOST`（默认 localhost）、`PGUSER`（postgres）、`PGPASSWORD`（postgres）、`SRC_DB`（agnes_register）、`DST_DB`（core_api_local）。

### 去重逻辑（两层）

1. **源内去重**：同一 key 可能挂多个账号（例：同一 `ent_api_key` 挂两个 account）。导入前 `SELECT DISTINCT ON (key)` 取任意一行，避免同批冲突。
2. **跨批去重**：`INSERT ... ON CONFLICT (key) DO NOTHING`。已存在的 key（含手动导入的、上次跑的）直接跳过 → **幂等，可反复执行**，只增量补新 key。

### 实现方式

源库和目标库是**同一 PostgreSQL 实例的两个 database**，普通 SQL 不能跨库。脚本用 psql `\copy` 把配对结果导出成临时 CSV，再 `\copy` 进目标库临时表，最后 `INSERT ... SELECT gen_random_uuid() ... ON CONFLICT DO NOTHING`。临时 CSV 用 `mktemp` 创建、`trap` 自动清理。

---

## 历史备注：`import_keys.sql`（一次性种子，已废弃）

早期曾用一个 `import_keys.sql` 从 `keys.json` 手工导入少量 key（约 13 条，一次性 seed，已并入正式数据）。文件已不在仓库中，仅备注历史。这些 key 若仍在 `agnes_register.accounts`，重跑上面的脚本会自动覆盖到（`ON CONFLICT` 去重）。

---

## 注意事项

- **type NOT NULL 无默认**：导入必须显式给 `type`。应用侧插入用 `AgnesKeyTypes.PERSONAL/ENTERPRISE`，漏传会被 NOT NULL 拦下。
- **按列定 type，不看前缀**：type 由数据所在列决定（`personal_api_key`→10 / `ent_api_key`→20）。源数据里若 `ent_api_key` 列混入 `sk-` 前缀的 key，仍会被归为 enterprise(20)——这是源数据情况，非脚本 bug。
- **id 用 UUIDv4**：应用侧正常用 UuidV7（时间有序），这里是一次性 seed，key 不按 id 分页，v4 可接受。
- **email 可能为空的历史行**：表里可能有早期遗留、email 为空且 `type=100` 的行（未登记 type）。脚本导入的行 email 一定非空（来自 reg_email）。
- **换目标环境**：改 `DST_DB` 即可。目标库必须已跑过 `V1__baseline.sql`（表和唯一索引存在），且 postgres 版本支持 `gen_random_uuid()`（PG13+ 内置）。

## 验证 SQL

```sql
-- 按 type 分组
SELECT type, count(*) FROM ai_agnes_key GROUP BY type ORDER BY type;
-- 重复 key（应为 0）
SELECT count(*) FROM (SELECT key FROM ai_agnes_key GROUP BY key HAVING count(*)>1) d;
-- 源 key 是否全部落库（在源库导出 key 列表后跨库比对，missing 应为 0）
```
