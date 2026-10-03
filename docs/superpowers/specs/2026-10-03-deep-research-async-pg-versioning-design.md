# DeepResearch 异步化 + 历史版本（PG 存储）设计文档

- 日期：2026-10-03
- 模块：`core-api` / `modules/ai`
- 相关前端：`/Users/jason/ai/myprojects/antique`（需同步改造，前端 agent review）
- 取代：`2026-10-02-deep-research-async-r2-versioning-design.md`（R2 方案，因 PutObject 操作费对「小而多」写模式不划算而放弃）

## 1. 背景与目标

当前 `m_ai_runDeepResearch` 是**同步三步**：事务内更新图片 → 事务外跑 AI（1–3 min）→ 事务内写回，且按 `scanRecordId` 唯一键 upsert（覆盖旧结果）。要解决两个问题：

1. **同步调用易被网关超时**：AI 调用常 >1 min、偶达 3 min，HTTP 长挂起易触发网关超时。→ **异步化**：mutation 立即创建记录并返回，后台跑 AI，前端轮询状态。
2. **覆盖式更新丢历史**：当前覆盖旧结果。→ **每次新建记录**，保留全部历史版本用于分析。

### 1.1 存储选型：为什么是 PG 而非 R2

单条 deep research 结果很小（~6 KB）但数量很多。Cloudflare R2 的 Class A 操作（PutObject）$4.50/百万次——「每条一次 PutObject」的写模式下，**操作费远超存储费**（百万条 6 KB ≈ 6 GB 存储仅 $0.09/月，但百万次写 = $4.5）。因此结果**存回 PostgreSQL** 的 `premium_result` JSONB 列（即原有做法），不走对象存储。

> **archive（未来方向，本期不做）**：PG 中历史结果长期累积会膨胀。未来可写定期任务把老结果**批量打包**（按天/月合并为单个大文件，一次 PutObject）归档到 R2——批量写规避 Class A 费用，又享受 R2 廉价存储。本期不设计不实现，仅记录方向。

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
| 8 | 僵死 IN_PROGRESS 兜底 | **仅查询惰性判定**（超 10 min 置 FAILED）。「成功才扣」下僵死记录不占配额，不做清理 job |
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
- `30 SUCCESS`：AI 成功 + 终态 CAS 命中 + DB 回写完成。
- `40 FAILED`：AI 失败 / scan_status 非 SUCCESS·PARTIAL / 查询惰性超时。

**所有 20→30 与 20→40 用 CAS**：`UPDATE ... SET status=? WHERE id=? AND status=20`。affected=0 表示已被其它路径终结，当前路径放弃后续动作（不重复扣配额/回写）。

### 3.2 配额语义（决策 3/4：成功才扣，无退款）

- 创建时不扣；mutation 入口预检 `used >= limit → QUOTA_EXCEEDED`，不创建任务。
- 仅在「AI 成功 + 终态 CAS 20→30 命中 + 成为 latest」的**同一事务**内原子自增（`count < limit` 才 +1，封顶）。
- 无退款：FAILED / 超时 / 僵死从未扣过配额。CAS 保证不重复终结、不重复 +1。

### 3.3 完整流程

```
前端 run deepResearch
  → mutation m_ai_runDeepResearch （立即返回, <1s）
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
      轮询 q_ai_getDeepResearchStatus(deepResearchId) 每隔几秒（仅返回状态）
        - IN_PROGRESS：updated_at 超 10 min → CAS 置 FAILED(TIMEOUT)，返回 FAILED；否则继续轮询
        - FAILED：返回 status + error_code + scanStatus（供补拍提示）
        - SUCCESS：
            → q_ai_findMyScanById(scanRecordId) 取权威 ScanRecord + latestDeepResearch{ premiumResult }
            → 以 API ScanRecord 为基础（basicResult + premiumResult 均来自 API）
            → SQLite 单事务覆盖本地
```

### 3.4 成功回写的权威指针与并发（决策 5/6/7）

权威关系用 `scan_record.latest_deep_research_id`（不用 is_latest 落库）：

- **唯一权威指针**：`scan_record.latest_deep_research_id`（nullable UUID）。
- **latest 定义**：发起时间最新且成功，比较用 `(created_at, id)`（id 兜底同时间）。
- **成功回写（单条件事务）**：
  1. CAS：`UPDATE core_ai_scan_deep_research SET status=30, premium_result=? WHERE id=? AND status=20`。affected=0 → 已被终结，放弃。
  2. affected=1 后，判断当前任务是否比 `latest_deep_research_id` 指向的任务新：
     - **是**：同一事务更新 `scan_record.basic_result / has_deep_search / prompt_version / latest_deep_research_id = 当前id`，配额 +1（封顶）。
     - **否**：仅保留为成功历史，不动 scan_record，不扣配额。
  3. scan_record 的 AI 字段与 pointer 在**同一条件 UPDATE** 中一起改——保证 basic_result 与 latest_deep_research_id（进而其 premium_result）指向同一次成功（不会「一个 5 元、一个 10 元」）。
- **不加显式锁、不限制并发 IN_PROGRESS**：允许随时重跑；异常任务不阻塞再次发起。并发期间前端可能短暂看到旧版本，最终以 latest pointer 收敛。
- `isLatest` 不落库：按 `deepResearch.id == scanRecord.latestDeepResearchId` 推导。

## 4. 数据模型变更

### 4.1 `core_ai_scan_deep_research` 表

| 字段 | 变更 | 说明 |
|------|------|------|
| `scan_record_id` | **去掉 @Key 唯一约束**，改普通列 | 一个 scan 可有多条历史记录 |
| `premium_result` | **保留并继续写**（JSONB） | 结果存此；每条历史记录各存自己的 premium_result |
| `prompt_version` | 保留 | AI 提示词版本（String） |
| `status` | **新增** Int NOT NULL | 10/20/30/40（见 §3.1） |
| `error_code` | **新增** String?（nullable） | 稳定错误码：AI_FAILED / AI_STATUS_REJECTED / TIMEOUT。对外暴露，不含内部异常 |
| `error_details` | **新增** JSONB?（nullable） | 结构化失败详情；AI_STATUS_REJECTED 时含 scan_status（供补拍）。不含异常栈 |

> **不新增** `file_key` / `doc_version`（无 R2 doc）。**不新增** `is_latest` 列（权威用指针）。

### 4.2 `core_ai_scan_record` 表

| 字段 | 变更 | 说明 |
|------|------|------|
| `latest_deep_research_id` | **新增** UUID?（nullable） | 权威指针：指向当前有效（最新且成功）的 deep research 记录。逻辑外键（不用 @ManyToOne） |

### 4.3 索引变更（**兼容性关键**）

- **删除** `scan_record_id` 唯一索引。
  - ⚠ **索引实际名核查**：V1 建索引时表名为 `ai_scan_deep_research`，索引名 `ai_scan_deep_research_scan_record_id_uidx`；V2 `ALTER TABLE ... RENAME TO core_ai_scan_deep_research` **不会自动重命名索引**，故线上索引仍为旧名。迁移须用该旧名 DROP。
  - **合并前必须用 `\d core_ai_scan_deep_research` 核实线上真实唯一索引名**，确保 DROP 命中。否则唯一约束未解除，第二次 insert 同 scanRecordId 会撞唯一约束报错（核心功能「一 scan 多历史版本」直接崩）。
- **新增**普通索引 `(scan_record_id, created_at)`：查某 scan 历史、按序比较 latest 用。

### 4.4 Entity 修改

- `ScanDeepResearch.kt`：去 `@Key`；`premiumResult` 保持 `@Serialized Map<String,Any?>?`（继续写）；新增 `status: Int`、`errorCode: String?`、`errorDetails: Map<String,Any?>?`（`@Serialized`，JSONB）。
- `ScanRecord.kt`：新增 `latestDeepResearchId: UUID?`。

### 4.5 迁移处理（旧数据）

- 线上基本无真实数据。
- Flyway（新文件，版本号接续现有最大，如 `V10__...`；**文件内注释版本号须与文件名一致**）：
  - `ALTER TABLE core_ai_scan_deep_research ADD COLUMN status smallint NOT NULL DEFAULT 30, ADD COLUMN error_code varchar(64), ADD COLUMN error_details jsonb;`
  - `DROP INDEX IF EXISTS <核实后的旧唯一索引名>;`
  - `CREATE INDEX ... ON core_ai_scan_deep_research (scan_record_id, created_at);`
  - `ALTER TABLE core_ai_scan_record ADD COLUMN latest_deep_research_id uuid;`
  - 回填指针（旧数据一 scan 一条）：
    ```sql
    UPDATE core_ai_scan_record r
    SET latest_deep_research_id = d.id
    FROM core_ai_scan_deep_research d
    WHERE d.scan_record_id = r.id;
    ```
    （若线上曾存在一 scan 多条，须加 `ORDER BY d.created_at DESC` 取最新；本期假设一 scan 一条，见注释。）

## 5. GraphQL Schema 变更（`schema/customer/ai.graphqls`）

### 5.1 Mutation `m_ai_runDeepResearch` 返回值

```graphql
type RunDeepResearchResult {
  "新建的 deep research 任务 id，前端据此轮询"
  deepResearchId: UUID!
  "任务状态：20=IN_PROGRESS（创建即此值）"
  status: Int!
}
```

### 5.2 新增轮询 Query（仅返回状态）

```graphql
extend type Query {
  "按 deepResearchId 轮询任务状态"
  q_ai_getDeepResearchStatus(deepResearchId: UUID!): DeepResearchStatus!
}

type DeepResearchStatus {
  deepResearchId: UUID!
  "20=IN_PROGRESS, 30=SUCCESS, 40=FAILED"
  status: Int!
  "FAILED 时返回：稳定错误码（AI_FAILED/AI_STATUS_REJECTED/TIMEOUT）"
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
- **列表查询不加载 premiumResult**：列表视图只需状态/基础字段，premiumResult 仅详情页经 `latestDeepResearch` 加载。
- premiumResult 直接是 PG `premium_result` 列值（无 R2、无 presign、无下载）。

## 6. 分层落点（遵循 AGENTS.md 约定）

| 层 | 文件 | 改动 |
|----|------|------|
| Schema | `schema/customer/ai.graphqls` | 改 `RunDeepResearchResult`；加 `DeepResearchStatus` + `q_ai_getDeepResearchStatus`；`ScanRecord.deepResearch`→`latestDeepResearch` |
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
- **方案 B 一致性**：
  - SUCCESS 后调 `q_ai_findMyScanById(scanRecordId)` 取权威 ScanRecord + `latestDeepResearch { premiumResult }`。
  - basicResult **与** premiumResult **均以 API ScanRecord 为准**（premiumResult 直接从 `latestDeepResearch.premiumResult` 读，无需下载 R2）。
  - 若轮询任务 A 成功时服务端 latest 已是更新的 B，`findMyScanById` 返回的 latest 即 B，避免「A 的 basic 配 B 的 premium」。
- 轮询间隔几秒一次；处理 20/30/40 三态 UI。FAILED 的 AI_STATUS_REJECTED 从 `scanStatus` 读推荐补拍（沿用 `deepResearchRejectionReason`），其它 error_code 做通用失败提示。
- **premiumResult 读取路径**：`record.latestDeepResearch.premiumResult`（snake_case 内层键不变），与现有 `premiumOf` 消费一致。
- **SQLite 任务恢复（本期必做）**：
  - scan 行加 nullable `pendingDeepResearchId`；mutation 成功写入；SUCCESS/FAILED 清空。
  - 冷启动/回前台有 pending ID 则恢复轮询。
  - 查询返回 NOT_FOUND：仅把 `pendingDeepResearchId` 置 null，不清空 ScanRecord / 已有 deepResearch。
  - 后端 (created_at,id)+latest pointer 规则保证旧任务（恢复的 pending）不覆盖更新任务。

## 8. GraphQL 交付清单（Trusted Documents）

**服务端：**
- `schema/customer/ai.graphqls`：改 `RunDeepResearchResult`；加 `DeepResearchStatus` + `q_ai_getDeepResearchStatus`；`ScanRecord.deepResearch`→`latestDeepResearch`。
- `resources/graphql/persisted-queries/customer/customer.json`：加入新 Query 与改造后 mutation、`q_ai_findMyScanById`（含 latestDeepResearch）条目。
- DGS codegen 类型随编译更新。

**前端（antique）：**
- `apps/shared/src/api/graphql.ts` 的 `_API_ENTRIES`：新增 `q_ai_getDeepResearchStatus`；更新 `m_ai_runDeepResearch`（返回值已改）与 `q_ai_findMyScanById`（含 latestDeepResearch{premiumResult}）的 query 文本。
- 重新生成 schema 类型与 persisted-query，与服务端 `customer.json` 1:1 对齐（缺任一侧线上即被拒）。

## 9. 错误处理与边界

| 场景 | 处理 |
|------|------|
| AI 调用抛异常 | CAS 20→40，error_code=AI_FAILED，error_details 存安全摘要（不含栈） |
| AI scan_status 非 SUCCESS/PARTIAL | CAS 20→40，error_code=AI_STATUS_REJECTED，error_details 含 scan_status |
| 查询惰性超时 | IN_PROGRESS 且 updated_at 超 10 min → CAS 20→40，error_code=TIMEOUT |
| 重复终结 | CAS affected=0 → 放弃后续（不重复扣配额/回写） |
| 旧任务晚完成 | CAS 置 SUCCESS 存历史，(created_at,id) 未命中则不动 scan_record、不扣配额 |
| owner-scoped | 创建、查询均校验 projectId+customerId；非本人 NOT_FOUND |
| 对外错误 | 只回 error_code + 安全文案 +（补拍场景）scan_status；内部异常不外泄 |

## 10. 测试计划（非框架、最小可运行校验）

1. **终态 CAS 幂等**：超时置 FAILED 与后台成功竞争 → 只有一个终态；配额只变化一次。
2. **乱序完成的 latest 规则**：同 scan 两任务乱序完成 → `latest_deep_research_id` 指向 (created_at,id) 最新的成功任务；旧任务晚完成不覆盖 scan_record。
3. **配额成功才扣**：失败/超时不扣；成功 +1；预检 used>=limit 拒绝。
4. **basicResult/pointer/premiumResult 原子性**：成功回写事务后，scan_record.basic_result 与 latest_deep_research_id 指向的 premium_result 来自同一次成功。
5. **owner-scope**：跨 customer 查询/创建 NOT_FOUND。
6. **多历史版本可插入**：同 scanRecordId 连续 insert 两条不报唯一约束（验证 §4.3 旧唯一索引已删）。

## 11. 相对 R2 版本的删除清单（供实现对照回退）

若基于已写的 R2 版本代码改回本方案，需删除：

- `ScanDeepResearch.fileKey` / `docVersion` 字段及 entity 属性、迁移列。
- `dto/ai/DeepResearchDoc.kt`（DeepResearchDocs doc 组装 + DeepResearchTaskContext 的 docVersion 可留可删）。
- `DeepResearchTaskService` 中 R2 上传、`uploadWithRetry`、doc 组装、`buildDoc`、`findScanForSnapshot` 调用。
- `AiConfig` 的 `u2` bucket 配置 / `application*.yml` 的 u2。
- `ScanDeepResearch.resultUrl` GraphQL 字段 + `AiFetcher.deepResearchResultUrl` resolver + `ScanAggHandler.deepResearchResultUrl`。
- R2_UPLOAD_FAILED 错误码（保留 AI_FAILED/AI_STATUS_REJECTED/TIMEOUT）。
- `casSuccess` 签名从 `(id, fileKey)` 改为 `(id, premiumResult)`——改为写 premium_result 列。
- `finalizeDeepResearchSuccess` 的 fileKey 参数改为 premiumResult。

## 12. 实现期验证点

1. §6.1 后台构造脱离请求的 ActionContext 供 `scanRunner.run` 调用的具体方式。
2. §4.3 **线上旧唯一索引真实名称**（`\d core_ai_scan_deep_research`），确保 DROP 命中——否则多历史版本插入崩溃。
3. §5.3 `getDeepResearchStatus` 的惰性超时 CAS 必须走 writer：确认 `globalTx.withTx` 对 query ctx（`isMutation=false`）是否强制 writer，或改为该查询显式用 writer / 单独 writer 事务。
