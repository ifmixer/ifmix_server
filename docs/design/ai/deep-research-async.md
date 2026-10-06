# DeepResearch 异步化 + 历史版本（PG 存储）设计文档

- 日期：2026-10-03
- 状态：**已实现（V10/V11，2026-10-05 合入 main）**——2026-10-05 补充决策：缺 `premium_result` 按失败处理（见 §3.1）
- 模块：`core-api` / `modules/ai`
- 相关前端：`/Users/jason/ai/myprojects/antique`（需同步改造，前端 agent review）
- 取代：`2026-10-02-deep-research-async-r2-versioning-design.md`（R2 方案，因 PutObject 操作费对「小而多」写模式不划算而放弃）

## 1. 背景与目标

当前 `m_ai_deepResearch_run` 是**同步三步**：事务内更新图片 → 事务外跑 AI（1–3 min）→ 事务内写回，且按 `scanRecordId` 唯一键 upsert（覆盖旧结果）。要解决两个问题：

1. **同步调用易被网关超时**：AI 调用常 >1 min、偶达 3 min，HTTP 长挂起易触发网关超时。→ **异步化**：mutation 立即创建记录并返回，后台跑 AI，前端轮询状态。
2. **覆盖式更新丢历史**：当前覆盖旧结果。→ **每次新建记录**，保留全部历史版本用于分析。

### 1.1 存储选型：为什么是 PG 而非 R2

单条 deep research 结果很小（~6 KB）但数量很多。Cloudflare R2 的 Class A 操作（PutObject）$4.50/百万次——「每条一次 PutObject」的写模式下，**操作费远超存储费**（百万条 6 KB ≈ 6 GB 存储仅 $0.09/月，但百万次写 = $4.5）。因此结果**存回 PostgreSQL** 的 `premium_result` JSONB 列（即原有做法），不走对象存储。

> **archive（未来方向，本期不做）**：PG 中历史结果长期累积会膨胀。未来可写定期任务把老结果**批量打包**（按天/月合并为单个大文件，一次 PutObject）归档到 R2——批量写规避 Class A 费用，又享受 R2 廉价存储。本期不设计不实现，仅记录方向。
>
> archive 的**运行约束**（未来实现时必须满足，P2）：归档/删除某条 premium_result 前，必须确认它**不再是任一 scan 的 `latest_deep_research_id` 指向对象**，或读路径具备 archive fallback；否则置空/删除 JSONB 会让详情页结果消失。
>
> **PG 成本不是零（P2）**：R2 省掉的是操作费，但写入成本转移到了 PG。需保留监控：`premium_result` JSONB 大小、`core_ai_scan_deep_research` 表/索引膨胀、WAL 与备份增长。这些指标是未来决定何时启动 archive 的依据。

## 2. 关键设计决策（已确认）

| # | 决策 | 选择 |
|---|------|------|
| 1 | 结果存储 | **PG `premium_result` JSONB 列**（原做法），不走 R2 |
| 2 | 异步执行载体 | **进程内虚拟线程**（JDK 25），Spring 管理的 executor 托管，**事务提交后**才启动 |
| 3 | 配额扣减时机 | **成功才扣**（AI 成功 + 终态 CAS 命中的事务内原子自增）。创建时不扣，入口做预检；无退款逻辑 |
| 4 | 配额并发超额 | **接受轻微超额**：预检挡住绝大多数；成功自增到封顶不卡已完成结果 |
| 5 | 版本权威关系 | **`scan_record.latest_deep_research_id`**（nullable）为唯一权威指针。isLatest 由 `id == latestDeepResearchId` 推导 |
| 6 | latest 判定顺序 | **发起时间最新且成功**：比较用 `(created_at, id)`。旧任务晚完成只存历史，不覆盖 ScanRecord |
| 7 | 终态更新 | **CAS**：`WHERE id=? AND status=IN_PROGRESS`，仅 affected=1 的赢家执行回写；终态不互相覆盖 |
| 7b | pointer 并发 | 成功回写时对 scan 行 **`SELECT ... FOR UPDATE`** 串行化（仅锁该行、毫秒级）；锁内读 pointer 比较 (created_at,id)。非业务级锁，不限制并发发起（见 §3.4） |
| 8 | 僵死 IN_PROGRESS 兜底 | **仅查询惰性判定**（超 **5 min** 置 FAILED）。「成功才扣」下僵死记录不占配额，不做清理 job |
| 9 | 前端一致性 | **方案 B**：成功后重查权威 ScanRecord，basicResult + premiumResult 均以 API 为准 |
| 10 | archive | **本期不做**，未来批量打包归档 R2（见 §1.1） |

## 3. 整体数据流与状态机

### 3.1 状态机

```
CREATED(10) → IN_PROGRESS(20) → SUCCESS(30)
                              └→ FAILED(40)
```

- `10 CREATED`：语义预留，实际落库直接写 `20`。
- `20 IN_PROGRESS`：mutation 创建记录即此状态，后台进行中。
- `30 SUCCESS`：AI 成功 + 结果完整 + 终态 CAS 命中 + DB 回写完成。
- `40 FAILED`：AI 失败 / scan_status 非 SUCCESS·PARTIAL / 查询惰性超时 / 结果缺失（见下）。

**结果完整性判定（2026-10-05 决策）**：AI 请求正常返回但响应缺 `premium_result`（或为空）**不算成功**——视为 `AI_EMPTY_RESULT` 失败，走 runner 的 key 池重试（换 key/换模型重试 N 次，N 与 scan 复用同一 `attemptCount` 上限），重试耗尽后任务置 `FAILED`。不允许"成功落库 premium=null"：否则会白扣配额、给用户发"完成"push 却拿到空报告。

**所有 20→30 与 20→40 用 CAS**：`UPDATE ... SET status=? WHERE id=? AND status=20`。affected=0 表示已被其它路径终结，当前路径放弃后续动作（不重复扣配额/回写）。

### 3.2 配额语义（决策 3/4：成功才扣，无退款）

- 创建时不扣；mutation 入口预检 `used >= limit → QUOTA_EXCEEDED`，不创建任务。
- 仅在「AI 成功 + 终态 CAS 20→30 命中 + 成为 latest」的**同一事务**内原子自增（`count < limit` 才 +1，封顶）。
- 无退款：FAILED / 超时 / 僵死从未扣过配额。CAS 保证不重复终结、不重复 +1。

### 3.3 完整流程

```
前端 run deepResearch
  → mutation m_ai_deepResearch_run （立即返回, <1s）
      1. 事务内：更新 scan 的 images（owner-scoped 校验归属）
      2. 事务内：配额预检（used >= limit → QUOTA_EXCEEDED 回滚）
      3. 事务内：创建 ScanDeepResearch 记录 status=20, premium_result=null
      4. 事务提交后：经 Spring 管理 executor 启动虚拟线程后台任务
      5. 返回 { deepResearchId, status: 20 }
  → 后台任务 （1–3 min，脱离请求事务与 ActionContext）
      a. 调用 AI（事务外）
      b. AI 失败 OR scan_status 非 SUCCESS/PARTIAL：
           CAS 事务：status 20→40, 写 error_code/error_details(含 scan_status)
      c. AI 成功：**单条件事务**（见 §3.4）
           · CAS：status 20→30, 写 premium_result（JSONB 入 PG）
           · 若 (created_at,id) 比当前 latest 新：回写 scan_record AI 字段
             + latest_deep_research_id=当前 + 配额 +1（封顶）
           · 否则（旧任务晚完成）：仅置 SUCCESS 存历史，不动 scan_record pointer，不扣配额
  → 前端（方案 B，§7）
      mutation → deepResearchId
      轮询 q_ai_deepResearch_getStatus(deepResearchId) 每隔几秒（仅返回状态）
        - IN_PROGRESS：updated_at 超 5 min → CAS 置 FAILED(TIMEOUT)，返回 FAILED；否则继续轮询
        - FAILED：返回 status + error_code + scanStatus（供补拍提示）
        - SUCCESS：
            → q_ai_scan_getMyById(scanRecordId) 取权威 ScanRecord + latestDeepResearch{ premiumResult }
            → 以 API ScanRecord 为基础（basicResult + premiumResult 均来自 API）
            → SQLite 单事务覆盖本地
```

### 3.4 成功回写的权威指针与并发（决策 5/6/7）

权威关系用 `scan_record.latest_deep_research_id`（不用 is_latest 落库）：

- **唯一权威指针**：`scan_record.latest_deep_research_id`（nullable UUID）。
- **latest 定义**：发起时间最新且成功，比较用 `(created_at, id)`（id 兜底同时间）。
- **成功回写（单短事务，对同一 scan 行加行锁串行化）**：
  1. CAS：`UPDATE core_ai_scan_deep_research SET status=30, premium_result=? WHERE id=? AND status=20`。affected=0 → 已被终结，放弃（不扣配额）。
  2. **`SELECT ... FOR UPDATE` 锁住对应 `scan_record` 行**，在锁内读当前 `latest_deep_research_id`、按 `(created_at,id)` 比较：
     - **当前任务更新**：同一事务更新 `scan_record.basic_result / has_deep_search / prompt_version / latest_deep_research_id = 当前id`，配额 +1（封顶）。
     - **当前任务较旧**：仅保留为成功历史，不动 scan_record，不扣配额。
  3. 释放行锁（事务提交）。

> **为什么必须加行锁（P0 修正）**：仅靠「先读 pointer、再带旧 pointer 值做条件 UPDATE」的乐观锁在 **pointer=NULL** 初始态下有竞态——
> 任务 A（旧）、B（新）都读到 pointer=NULL：A、B 的 CAS 都成功 → A 先 `WHERE latest_deep_research_id IS NULL` 更新 pointer=A → B 的同条件不再命中返回 false → **较新的 B 虽成功却只成历史，pointer 错误停在 A**，违反 latest 定义。
> `SELECT ... FOR UPDATE` 在最终写回时**对同一 scan 行序列化**（只锁这一行、毫秒级持有），锁内读到的 pointer 一定是前一个赢家写入的最新值，比较 `(created_at,id)` 才正确。这**不是业务级全局锁**，不限制并发发起 IN_PROGRESS，只串行化同一 scan 的「最终写回」这一刻。

- **不限制并发 IN_PROGRESS**：允许随时重跑；异常任务不阻塞再次发起。并发期间前端可能短暂看到旧版本，最终以 latest pointer 收敛。
- **加锁顺序不变量（P2-2）**：恒为 **scan 行（FOR UPDATE）→ customer 行（配额自增）**。`saveNewScan` 是 scan 插入→customer，二者无反向路径、无环，无死锁。未来**禁止**在 customer 锁内再回头锁 scan 行。
- `isLatest` 不落库：按 `deepResearch.id == scanRecord.latestDeepResearchId` 推导。

## 4. 数据模型变更

### 4.1 `core_ai_scan_deep_research` 表

| 字段 | 变更 | 说明 |
|------|------|------|
| `scan_record_id` | **去掉 @Key 唯一约束**，改普通列 | 一个 scan 可有多条历史记录 |
| `premium_result` | **保留并继续写**（JSONB） | 结果存此；每条历史记录各存自己的 premium_result |
| `prompt_version` | 保留 | AI 提示词版本（String） |
| `status` | **新增** Int NOT NULL | 10/20/30/40（见 §3.1） |
| `error_code` | **新增** String?（nullable） | 稳定错误码：AI_FAILED / AI_STATUS_REJECTED / TIMEOUT / TASK_SUBMISSION_FAILED。对外暴露，不含内部异常 |
| `error_details` | **新增** JSONB?（nullable） | 结构化失败详情；AI_STATUS_REJECTED 时含 scan_status（供补拍）。不含异常栈 |

> **不新增** `file_key` / `doc_version`（无 R2 doc）。**不新增** `is_latest` 列（权威用指针）。

### 4.2 `core_ai_scan_record` 表

| 字段 | 变更 | 说明 |
|------|------|------|
| `latest_deep_research_id` | **新增** UUID?（nullable） | 权威指针：指向当前有效（最新且成功）的 deep research 记录。逻辑外键（不用 @ManyToOne） |

### 4.3 索引变更（**兼容性关键**）

- **删除** `scan_record_id` 唯一索引。V1 建索引时表名为 `ai_scan_deep_research`、索引名 `ai_scan_deep_research_scan_record_id_uidx`；V2 `RENAME TO core_ai_scan_deep_research` **不改索引名**，故线上索引仍为旧名。
  - **解法：§4.5 的迁移用 DO 块「按列（scan_record_id）+ 唯一 + 非主键」动态定位删除**，不依赖硬编码索引名、无需人工 `\d` 核实。否则若唯一约束未解除，第二次 insert 同 scanRecordId 会撞唯一约束（核心功能「一 scan 多历史版本」直接崩）。
- **新增**普通索引 `(scan_record_id, created_at)`：查某 scan 历史、按序比较 latest 用。

### 4.4 Entity 修改

- `ScanDeepResearch.kt`：去 `@Key`；`premiumResult` 保持 `@Serialized Map<String,Any?>?`（继续写）；新增 `status: Int`、`errorCode: String?`、`errorDetails: Map<String,Any?>?`（`@Serialized`，JSONB）。
- `ScanRecord.kt`：新增 `latestDeepResearchId: UUID?`。

### 4.5 迁移处理（本机已跑过作废的 R2 版 V10 → 回滚重写）

**现状**：R2 版 `V10__deep_research_async.sql` **仅本机 dev 执行过**（flyway_schema_history 有记录、checksum 已固定）；生产及其它环境从未执行。R2 版 V10 已写得完善（按列定位删唯一索引、`DISTINCT ON` 回填指针），**与 PG 版唯一的差别是多加了两列** `doc_version`、`file_key`（R2 残留）。

**方案 A（已选）：本机回滚作废 V10 → 删 V10 history → 重写 V10 为 PG 版 → 重新 migrate。** 迁移历史保持单一 V10，无 R2 残留、无 V11 技术债。

**本机一次性回滚操作（实现期执行，仅 dev；生产无需）：**

```sql
-- 1) 回滚 R2 版 V10 加的结构（只在本机 dev 执行）
ALTER TABLE public.core_ai_scan_record  DROP COLUMN IF EXISTS latest_deep_research_id;
DROP INDEX IF EXISTS public.ai_scan_deep_research_scan_record_created_idx;
ALTER TABLE public.core_ai_scan_deep_research
    DROP COLUMN IF EXISTS status,
    DROP COLUMN IF EXISTS doc_version,
    DROP COLUMN IF EXISTS file_key,
    DROP COLUMN IF EXISTS error_code,
    DROP COLUMN IF EXISTS error_details;
-- 注：R2 版删掉的旧唯一索引不恢复——反正 PG 版重写后也会再删一次，且本机无真实数据。

-- 2) 删除 flyway 历史中的 V10 行，让重写后的 V10 重新执行
DELETE FROM public.flyway_schema_history WHERE version = '10';
```

> 执行 1)、2) 后，把 `V10__deep_research_async.sql` 重写为下方 PG 版，再 `./gradlew :core-api:flywayMigrate`。正常流程（结构回滚 → 删 V10 history → 重写 V10 → migrate）**不应触发 checksum error**；若出现，**停下排查 schema/history 不一致，不要用 `flywayRepair` 自动抹平**（那会掩盖真实的状态偏差）。

**重写后的 V10（PG 版）**：

```sql
-- V10: DeepResearch 异步化 + 历史版本（PG premium_result 存储；取代作废的 R2 方案）

ALTER TABLE public.core_ai_scan_deep_research
    ADD COLUMN status smallint NOT NULL DEFAULT 30,   -- DEFAULT 仅为旧行回填
    ADD COLUMN error_code character varying(64),
    ADD COLUMN error_details jsonb;

-- 回填完成后去掉 DEFAULT：避免未来漏设 status 时静默生成 SUCCESS（P2#1）
ALTER TABLE public.core_ai_scan_deep_research ALTER COLUMN status DROP DEFAULT;

-- 按「列 + 唯一 + 非主键」定位删除旧唯一索引（V2 改表名未改索引名，不依赖硬编码名）
DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN
        SELECT i.relname AS index_name
        FROM pg_class t
        JOIN pg_index ix ON t.oid = ix.indrelid
        JOIN pg_class i ON i.oid = ix.indexrelid
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
        WHERE t.relname = 'core_ai_scan_deep_research'
          AND a.attname = 'scan_record_id'
          AND ix.indisunique AND NOT ix.indisprimary
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS public.%I', r.index_name);
    END LOOP;
END $$;

CREATE INDEX ai_scan_deep_research_scan_record_created_idx
    ON public.core_ai_scan_deep_research USING btree (scan_record_id, created_at);

ALTER TABLE public.core_ai_scan_record
    ADD COLUMN latest_deep_research_id uuid;

-- 回填指针：每 scan 取 (created_at,id) 最新一条（DISTINCT ON，兼容潜在「一 scan 多条」）
UPDATE public.core_ai_scan_record r
SET latest_deep_research_id = d.id
FROM (
    SELECT DISTINCT ON (scan_record_id) id, scan_record_id
    FROM public.core_ai_scan_deep_research
    ORDER BY scan_record_id, created_at DESC, id DESC
) d
WHERE d.scan_record_id = r.id;
```

- 相对 R2 版 V10：**删去 `doc_version` / `file_key` 两列**，新增 `ALTER COLUMN status DROP DEFAULT`，其余（error_code/error_details、按列删唯一索引、DISTINCT ON 回填）保留。

**回滚窗口时序约束（P1-2，实现期务必遵守）**：
1. **DB 回滚 SQL 与 Kotlin 代码回退必须同批落地后才启动 app**。本项目 Flyway 随 Spring Boot 启动自动 migrate；回滚窗口内若启动 app，一旦 R2 版代码仍引用已被 DROP 的 `file_key`/`doc_version` 列，启动即炸。顺序：执行回滚 SQL → 落地代码回退（含重写版 V10）→ 再启动。
2. `DELETE FROM flyway_schema_history WHERE version='10'` 后若忘了手动 migrate，重启 app 会自动把重写版 V10 跑上去——这是期望的兜底行为，但需知晓。

## 5. GraphQL Schema 变更（`schema/customer/ai.graphqls`）

### 5.1 Mutation `m_ai_deepResearch_run` 返回值

```graphql
type RunDeepResearchResult {
  "新建的 deep research 任务 id，前端据此轮询"
  deepResearchId: UUID!
  "任务状态：20=IN_PROGRESS（正常）；40=FAILED（executor 同步提交失败）"
  status: Int!
  "正常（status=20）为 null；executor 提交失败（status=40）时为 TASK_SUBMISSION_FAILED。前端据此免再查一次 status（P1）"
  errorCode: String
}
```

> executor 提交失败时 mutation 直接返回 `{ deepResearchId, status: 40, errorCode: "TASK_SUBMISSION_FAILED" }`，前端无需再调 `q_ai_deepResearch_getStatus` 即可拿到错误码（§7 的早检查据此构造 `DeepResearchTaskError`）。

### 5.2 新增轮询 Query（仅返回状态）

```graphql
extend type Query {
  "按 deepResearchId 轮询任务状态"
  q_ai_deepResearch_getStatus(deepResearchId: UUID!): DeepResearchStatus!
}

type DeepResearchStatus {
  deepResearchId: UUID!
  "20=IN_PROGRESS, 30=SUCCESS, 40=FAILED"
  status: Int!
  "FAILED 时返回：稳定错误码（AI_FAILED/AI_STATUS_REJECTED/TIMEOUT/TASK_SUBMISSION_FAILED）"
  errorCode: String
  "FAILED 且 AI_STATUS_REJECTED 时返回 scan_status（供前端提示补拍）"
  scanStatus: JSON
}
```

### 5.3 `ScanRecord.latestDeepResearch` 字段

```graphql
type ScanRecord {
  # ... 现有字段 ...
  "当前有效（最新且成功）的 deep research；按 latest_deep_research_id 加载"
  latestDeepResearch: ScanDeepResearch
}

type ScanDeepResearch {
  id: UUID!
  scanRecordId: UUID!
  "高级扫描结果（JSONB，直接来自 PG premium_result 列）"
  premiumResult: JSON
  createdAt: DateTime!
  updatedAt: DateTime
}
```

- `ScanRecord.deepResearch`（旧字段，按 scanRecordId 查）→ 改为 `latestDeepResearch`，按 `latest_deep_research_id` 加载。DataLoader key 改为 scanRecord 的 `latest_deep_research_id`。
- **`ScanDeepResearch` 不再有 `resultUrl` 字段**（R2 残留）：现有 persisted query（`m_ai_scan_createOne`、`m_ai_scan_updateMyOne`、`q_ai_scan_getMyById`、`q_ai_collectionItem_listMy`）里对 `latestDeepResearch { ... resultUrl ... }` 的选择必须全部去掉 `resultUrl`，否则 schema 校验失败。
- **哪些查询选 `latestDeepResearch { premiumResult }`（P1#4，premiumResult 是 JSONB 大字段，列表场景不选）**：

| 操作 | 是否选 latestDeepResearch.premiumResult | 理由 |
|------|------|------|
| `q_ai_scan_getMyById`（详情） | **选** | 详情页需要 premiumResult |
| `m_ai_scan_createOne` / `m_ai_scan_updateMyOne` 回包 | **选**（仅 scanRecord 回包需要时） | 回包即详情形状；createScan 时 latest 恒 null，开销可忽略 |
| `m_ai_deepResearch_run` 回包 | 不涉及（只返回 deepResearchId+status） | — |
| `q_ai_scan_listMy`（列表） | **不选** | 列表只需状态/基础字段 |
| `q_ai_collectionItem_listMy`（collection 列表） | **不选** | 同列表，避免每条带 JSONB 大字段 |
- premiumResult 直接是 PG `premium_result` 列值（无 R2、无 presign、无下载）。

## 6. 分层落点（遵循 AGENTS.md 约定）

| 层 | 文件 | 改动 |
|----|------|------|
| Schema | `schema/customer/ai.graphqls` | 改 `RunDeepResearchResult`；加 `DeepResearchStatus` + `q_ai_deepResearch_getStatus`；`ScanRecord.deepResearch`→`latestDeepResearch` |
| DataFetcher | `bff/graphql/customer/ai/AiFetcher.kt` | `runDeepResearch` 改为创建记录+预检+提交后启动后台任务；新增 `getDeepResearchStatus`；DataLoader 改按 latest_deep_research_id |
| Service | `modules/ai/DeepResearchTaskService.kt` | 后台任务协调：不可变上下文；AI 事务外；DB 阶段经 TxRunner 用 writer。**不在 Handler 开事务**。**无 R2 上传/doc 组装** |
| Facade | `modules/ai/AiFacade.kt` | 转发 `createDeepResearchTask`、`getDeepResearchStatus` |
| Handler | `modules/ai/handler/ScanAggHandler.kt` | 创建 IN_PROGRESS（预检）；成功回写 CAS（写 premium_result）+ 条件 pointer + 配额；惰性超时 CAS |
| Repository | `modules/ai/repo/ScanDeepResearchRepository.kt` | 去 upsert；`insert`、`findById`、`casSuccess`（写 premium_result）、`casFailed`、`findByIds` |
| Repository | `modules/ai/repo/ScanRecordRepository.kt` | `updateAiFieldsAndPointerIfNewer`（同事务改 AI 字段 + pointer，带 (created_at,id) 比较） |
| Repository | `modules/ai/repo/CustomerScanMetricsRepository.kt` | 复用现有 `tryIncrementDeepResearchCount`（成功时调用）。无 decrement |

### 6.1 后台任务上下文与事务边界

- 后台脱离请求线程/事务/ActionContext。启动时提取不可变上下文：`projectId`、`customerId`、`deepResearchId`、`scanRecordId`、`images`、`locale/country/currency`、`promptVersion`、`createdAt`。
- 后台构造的 ActionContext 设 `isMutation=true`（→ `preferReader=false` 走 writer），不复用请求级 globalTx（`globalTxSql=null`/`inGlobalTx=false`）。
- executor 由 Spring 管理（`newVirtualThreadPerTaskExecutor`），**创建记录事务提交后**才启动。
- AI 调用事务外；DB 阶段经 **TxRunner**（Facade/Service 层）开短事务，Handler 不自开事务。
- `scanRunner.run` 接收 `ActionContext`——后台构造脱离请求的 action 载体。

## 7. 前端改造要点（方案 B，供前端 agent review）

- `runDeepResearchFlow`：返回语义从 `Promise<ScanRecord>` 改为「拿 deepResearchId → 轮询 → SUCCESS 后重查权威 ScanRecord → SQLite 覆盖」。
- **mutation 返回即检查终态（P2-1）**：mutation 可能直接返回 `{ status: 40, errorCode: "TASK_SUBMISSION_FAILED" }`（executor 提交失败）。拿到返回后应**立即** `if (status === 40) { 清 pending; throw DeepResearchTaskError(errorCode) }`，不进轮询（否则多一次轮询且 pending 刚写入又要清）。`errorCode` 直接取自 mutation 回包（§5.1 已加该字段），无需再调 `q_ai_deepResearch_getStatus`。`DeepResearchTaskError` 需支持从 mutation 返回值构造（现仅从轮询结果构造，需扩展）。
- **方案 B 一致性**：
  - SUCCESS 后调 `q_ai_scan_getMyById(scanRecordId)` 取权威 ScanRecord + `latestDeepResearch { premiumResult }`。
  - basicResult **与** premiumResult **均以 API ScanRecord 为准**（premiumResult 直接从 `latestDeepResearch.premiumResult` 读，无需下载 R2）。
  - 若轮询任务 A 成功时服务端 latest 已是更新的 B，`findMyScanById` 返回的 latest 即 B，避免「A 的 basic 配 B 的 premium」。
- 轮询间隔几秒一次；处理 20/30/40 三态 UI。FAILED 的 AI_STATUS_REJECTED 从 `scanStatus` 读推荐补拍（沿用 `deepResearchRejectionReason`），其它 error_code 做通用失败提示。
- **本地数据契约（P1#3，前端 agent 纠正：API 形状与本地消费形状不一致，需显式转换）**：
  - API/codegen 的 `ScanRecord` 用的是 `latestDeepResearch`（`generated/graphql.ts`），但现有展示层 `premiumOf` 读的是 `record.deepResearch.premiumResult`（`scanColumns.ts`）。二者**不一致**，不能说"直接覆盖 SQLite 即可"。
  - **采用方案**：保留 SQLite/展示层的**本地兼容形状 `deepResearch`**，在 **repository 边界**把 API 的 `latestDeepResearch` 转换为本地 `deepResearch`（给 `LocalScanRecord` 定义明确类型，含 `deepResearch?.premiumResult`）。即 `mergeDeepResearchResult` 这类转换函数要配套扩展 `LocalScanRecord` 类型，消除当前 typecheck 失败。
  - 展示层 `premiumOf` 等消费者**不变**（仍读本地 `deepResearch.premiumResult`）。snake_case 内层键不变。
  - 老本地记录、新 API 响应、现有展示逻辑三者通过"API→本地形状在 repository 边界转换"衔接，不断裂。
- **单设备禁止重入（产品规则，本期必做）**：
  - 同一 scan 存在**未终态** DeepResearch 任务时，**禁用「重新深度研究」按钮并显示「分析中」**。
  - 本地持久化一个 `pendingDeepResearchId`（SQLite，scan 行 nullable 列）：mutation 成功写入；终态时清空。
  - **本地到期（5 min）不直接放开按钮**，而是先调 `q_ai_deepResearch_getStatus(pendingDeepResearchId)`，让服务端按 `updated_at` 执行惰性 CAS，按返回分支处理：

    | 返回 | 前端动作 |
    |------|---------|
    | IN_PROGRESS | 继续 loading、保持禁用（服务端还没到阈值） |
    | SUCCESS | 先拉权威 ScanRecord → 落 SQLite → 清 pending → 放开 |
    | FAILED | 清 pending → 放开，允许重试 |
    | NOT_FOUND | 仅清 pending → 放开（不清空 ScanRecord / 已有 deepResearch） |
    | 网络错误 | 保留 pending、继续禁用（避免离线时重复创建） |

  - 正常情况前端一直在轮询（几秒一次），通常到不了 5 min 就已拿到 SUCCESS/FAILED；5 min 本地触发器只是兜底。
  - **单设备单任务规则下，终态无条件清空 pending 成立**，不需要额外的 `clearIfMatches`。
  - 冷启动/回前台：有 pending ID 则恢复轮询。
- **此前端规则不替代服务端 `SELECT FOR UPDATE`（§3.4），也不消除跨设备并发**：两台设备可同时为同一 scan 发起、旧客户端/重放/网络重试也可能并发——服务端行锁 + (created_at,id) 比较是并发正确性的唯一保证。前端重入禁用只**降低**「图片 B 配结果 A」概率。
  - **已知边界（本期接受）**：跨设备并发下，同一 scan 的「图片与当前展示结果版本」可能**短暂错配**。彻底消除需任务级图片快照（本期不做，未来可选）。
  - 若产品要求"任何设备上同一 scan 同时只能一个任务"，须由**后端创建入口检查并拒绝已有 IN_PROGRESS**，且创建入口要先对超时任务 CAS 为 TIMEOUT——这与当前"允许随时重跑"冲突，**本期不采用**。

## 8. GraphQL 交付清单（Trusted Documents）

**服务端：**
- `schema/customer/ai.graphqls`：改 `RunDeepResearchResult`；加 `DeepResearchStatus` + `q_ai_deepResearch_getStatus`；`ScanRecord.deepResearch`→`latestDeepResearch`。
- `resources/graphql/persisted-queries/customer/customer.json`：加入新 Query 与改造后 mutation、`q_ai_scan_getMyById`（含 latestDeepResearch）条目。`m_ai_deepResearch_run` 的回包选择须含 `{ deepResearchId status errorCode }`（errorCode 供 §7 早检查用）。
- DGS codegen 类型随编译更新。

**前端（antique）：**
- `apps/shared/src/api/graphql.ts` 的 `_API_ENTRIES`：新增 `q_ai_deepResearch_getStatus`；更新 `m_ai_deepResearch_run`（返回值已改）与 `q_ai_scan_getMyById`（含 latestDeepResearch{premiumResult}）的 query 文本。
- 重新生成 schema 类型与 persisted-query，与服务端 `customer.json` 1:1 对齐（缺任一侧线上即被拒）。

## 9. 错误处理与边界

| 场景 | 处理 |
|------|------|
| AI 调用抛异常 | CAS 20→40，error_code=AI_FAILED，error_details 存安全摘要（不含栈） |
| AI scan_status 非 SUCCESS/PARTIAL | CAS 20→40，error_code=AI_STATUS_REJECTED，error_details 含 scan_status |
| 查询惰性超时 | IN_PROGRESS 且 updated_at 超 5 min → CAS 20→40，error_code=TIMEOUT |
| 重复终结 | CAS affected=0 → 放弃后续（不重复扣配额/回写） |
| 旧任务晚完成 | CAS 置 SUCCESS 存历史，(created_at,id) 未命中则不动 scan_record、不扣配额 |
| owner-scoped | 创建、查询均校验 projectId+customerId；非本人 NOT_FOUND |
| 对外错误 | 只回 error_code + 安全文案 +（补拍场景）scan_status；内部异常不外泄 |
| **executor 提交失败**（P1#5） | `executor.execute()` 在关闭/资源拒绝时抛错，此时记录已 IN_PROGRESS。**mutation 捕获该异常 → 短事务 CAS 20→40，error_code=TASK_SUBMISSION_FAILED**，并**仍返回 deepResearchId + 终态 status(40)**（前端能据此显示失败/重试，不会拿不到 id 无法恢复） |

**进程内执行器的限制（本期明确接受）**：进程崩溃时，已拿到 deepResearchId 的客户端由既有**惰性超时**（§3.3，超 5 min 置 FAILED）回收；无客户端轮询的僵死记录因「成功才扣」不占配额，放着无害。**本期不做**服务重启自动续跑（那需要持久队列或扫 IN_PROGRESS 的 job）。错误码新增 `TASK_SUBMISSION_FAILED`（连同 AI_FAILED/AI_STATUS_REJECTED/TIMEOUT）。

## 10. 测试计划（非框架、最小可运行校验）

1. **终态 CAS 幂等**：超时置 FAILED 与后台成功竞争 → 只有一个终态；配额只变化一次。
2. **乱序完成的 latest 规则（真实并发/事务测试，非 mock affected=0，P0#1）**：对**同一 scan**、pointer 初始为 NULL，用两个真实事务跑「旧任务 A」「新任务 B」的成功回写（含 `SELECT FOR UPDATE`），断言最终 `latest_deep_research_id` 指向 (created_at,id) **较新的 B**，无论谁先拿锁；A 只成历史。
   - ⚠ **FOR UPDATE 方案下的正确断言（精确化）**：先让 A 拿到 scan 行锁，此时启动 B——**B 会阻塞在获取同一行锁**（不是「A/B 同时读 pointer」，行锁已序列化）。断言 B 处于阻塞；提交/释放 A 后，B 获得锁、读到的 pointer **已是 A**，按 (created_at,id) 比较后覆盖为 B。
   - **对照无锁旧实现**：无锁实现下 A、B 都读到 pointer=NULL，A 先条件 UPDATE 成功、B 的 `WHERE ... IS NULL` 落空返回 false → B 丢失（指针错停 A）。测试需能让旧实现失败、加锁实现通过。H2 PostgreSQL mode 支持 `FOR UPDATE`，两个 KSqlClient 事务 + latch 可做。
3. **配额成功才扣**：失败/超时不扣；成功 +1；预检 used>=limit 拒绝。
4. **basicResult/pointer/premiumResult 原子性**：成功回写事务后，scan_record.basic_result 与 latest_deep_research_id 指向的 premium_result 来自同一次成功。
5. **owner-scope**：跨 customer 查询/创建 NOT_FOUND。
6. **多历史版本可插入**：同 scanRecordId 连续 insert 两条不报唯一约束（验证 §4.5 旧唯一索引已删）。
7. **executor 提交失败（P1#5）**：模拟 executor 拒绝 → 记录 CAS 为 FAILED(TASK_SUBMISSION_FAILED)，mutation 仍返回 deepResearchId + status=40。

## 11. 相对 R2 版本的删除清单（供实现对照回退）

若基于已写的 R2 版本代码改回本方案，需删除：

- `ScanDeepResearch.fileKey` / `docVersion` 字段及 entity 属性、迁移列。
- **`dto/ai/DeepResearchDoc.kt` 整体删除**（`DeepResearchDocs` doc 组装 + `objectKey` + `BUCKET_ID`）。
  - ⚠ **陷阱（P1-1）**：`DeepResearchTaskContext` 与 `ImageRefItem` **当前就在 `DeepResearchDoc.kt` 这个文件里**，直接删整文件会把后台任务上下文一起删掉。实现步骤：**先把 `DeepResearchTaskContext`（含 `ImageRefItem`）拆到独立文件** `dto/ai/DeepResearchTaskContext.kt`，并去掉其中的 `docVersion` 字段，**再**删除 `DeepResearchDoc.kt`。
- `DeepResearchTaskService` 中 R2 上传、`uploadWithRetry`、doc 组装、`buildDoc`、`findScanForSnapshot` 调用、`objectStorage`/`snakeCaseMapper` 注入（若仅为 R2 用）。
- `AiConfig` 的 `u2` bucket 配置 / `application*.yml` 的 u2（`deepResearchExecutor` bean 保留）。
- `ScanDeepResearch.resultUrl` GraphQL 字段 + `AiFetcher.deepResearchResultUrl` resolver + `ScanAggHandler.deepResearchResultUrl`。
- `DeepResearchErrorCodes.R2_UPLOAD_FAILED`（保留 AI_FAILED/AI_STATUS_REJECTED/TIMEOUT，**新增 TASK_SUBMISSION_FAILED**）。
- `casSuccess` 签名从 `(id, fileKey)` 改为 `(id, premiumResult)`——改为写 premium_result 列。
- `finalizeDeepResearchSuccess` 的 fileKey 参数改为 premiumResult。
- **保留** `DeepResearchStatuses.CREATED(10)`（语义预留，与状态机图一致，勿删）。
- **运维项**：u2 bucket 若已在 Cloudflare 控制台创建，删除代码引用后 bucket 本身可删（本期从未成功上传过数据）。

## 12. 实现期验证点

1. §6.1 后台构造脱离请求的 ActionContext 供 `scanRunner.run` 调用的具体方式。
2. §3.4 成功回写的 `SELECT ... FOR UPDATE`：确认 Jimmer KSqlClient 的行锁写法（`forUpdate()`）在 writer 事务内正确锁 `core_ai_scan_record` 行；并确认该回写事务走 writer（isMutation=true）。
3. §5.3 `getDeepResearchStatus` 的惰性超时 CAS 必须走 writer：确认 `globalTx.withTx` 对 query ctx（`isMutation=false`）是否强制 writer，或改为该查询显式用 writer / 单独 writer 事务。
4. 旧唯一索引删除已改为**按列定位**（§4.5 的 DO 块），不再依赖硬编码索引名——无需人工 `\d` 核实。
