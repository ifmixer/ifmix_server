# DeepResearch 异步化 + R2 存储 + 历史版本 设计文档

- 日期：2026-10-02（2026-10-03 按前端 agent review 大幅修订；命名/结构二次调整）
- 模块：`core-api` / `modules/ai`
- 相关前端：`/Users/jason/ai/myprojects/antique`（需同步改造，前端 agent review）

## 1. 背景与目标

当前 `m_ai_runDeepResearch` 是**同步三步**：事务内更新图片 → 事务外跑 AI（1–3 min）→ 事务内写回。存在三个问题，本次一并改造：

1. **结果体积大、无需 PG 查询**：`premium_result` 作为 JSONB 存 DB，数据大且几乎只整体读取。→ 移到 R2，DB 只存指针（`file_key`）。
2. **同步调用易被网关超时**：AI 调用常 >1 min、偶达 3 min，HTTP 请求长挂起易触发网关超时。→ 异步化：mutation 立即创建记录并返回，后台跑 AI，前端轮询。
3. **覆盖式更新丢历史**：当前按 `scanRecordId` 唯一键 upsert，新结果覆盖旧。→ 每次新建记录，保留全部历史版本用于分析。

## 2. 关键设计决策（已确认）

| # | 决策 | 选择 |
|---|------|------|
| 1 | 异步执行载体 | **进程内虚拟线程**（JDK 25，零新增依赖），由 Spring 管理的 executor 托管，**事务提交后**才启动 |
| 2 | R2 上传失败处理 | **有限次重试（2–3 次固定间隔）后再 FAILED**。AI 成本高，不因 R2 抖动丢结果 |
| 3 | 配额扣减时机 | **成功才扣**（AI 成功 + 终态 CAS 命中的事务内原子自增）。创建时不扣，但 mutation 入口做配额预检；无退款逻辑 |
| 4 | 配额并发超额 | **接受轻微超额**：预检挡住绝大多数；成功自增到封顶不卡已完成结果 |
| 5 | 前端读取结果方式 | **presigned download URL**（由 `ScanRecord.latestDeepResearch.resultUrl` 提供，非 status 查询），前端直连 R2 下载 doc 取 premiumResult |
| 6 | 结果 bucket | **独立私有 bucket `u2`**（dev=`u2dev` / prod=`u2p`）。**presign 用 R2 原始 S3 endpoint**，不用 custom domain |
| 7 | 版本权威关系 | **`scan_record.latest_deep_research_id`**（nullable）为唯一权威指针。废弃 is_latest 落库；isLatest 由 `id == latestDeepResearchId` 推导 |
| 8 | latest 判定顺序 | **发起时间最新且成功**：比较用 `(created_at, id)`。旧任务晚完成只存历史，不覆盖 ScanRecord |
| 9 | 终态更新 | **CAS**：`WHERE id=? AND status=IN_PROGRESS`，仅 affected=1 的赢家执行回写；终态不互相覆盖 |
| 10 | 旧数据迁移 | 保留 `premium_result` 列，不迁移旧数据；读取双路径（file_key 优先，回退列） |
| 11 | doc 内容结构 | 完整 `scanRecordSnapshot`（分析留档）+ `deepResearch{premiumResult 嵌套}` + promptVersion + docVersion |
| 12 | 僵死 IN_PROGRESS 兜底 | **仅查询惰性判定**（超 10 min 置 FAILED，写 error 信息）。因「成功才扣」，僵死记录不占配额，故不做清理 job |
| 13 | 前端一致性 | **方案 B**：成功后重查权威 ScanRecord，basicResult 以 API 为准，仅从 doc 摘 premiumResult |

## 3. 整体数据流与状态机

### 3.1 状态机

```
CREATED(10) → IN_PROGRESS(20) → SUCCESS(30)
                              └→ FAILED(40)
```

- `10 CREATED`：语义预留，实际落库直接写 `20`。
- `20 IN_PROGRESS`：mutation 创建记录即此状态，后台进行中。
- `30 SUCCESS`：AI 成功 + 结果已传 R2 + 终态 CAS 命中 + DB 回写完成。
- `40 FAILED`：AI 失败 / scan_status 非 SUCCESS·PARTIAL / R2 上传最终失败 / 查询惰性超时。

**所有 20→30 与 20→40 的转换都用 CAS**：`UPDATE ... SET status=? WHERE id=? AND status=20`。affected=0 表示已被其它路径终结（或超时判定抢先），当前路径放弃后续动作（不重复扣配额、不重复回写）。

### 3.2 配额语义（决策 3/4：成功才扣，无退款）

- **创建时不扣**：mutation 仅创建 IN_PROGRESS 记录。
- **入口预检**：mutation 创建前 `used >= limit` 直接抛 QUOTA_EXCEEDED，不创建任务。
- **成功才扣**：仅在「AI 成功 + 终态 CAS 20→30 命中」的**同一事务**内原子自增 `deep_research_count`（`count < limit` 才 +1，到 limit 封顶；超额不卡已完成结果）。
- **无退款**：FAILED / 超时 / 僵死记录从未扣过配额，无需退还。僵死记录放着即可（既不占额度，也不影响权威指针——pointer 只在成功时更新）。
- CAS 保证同一任务不会被重复终结、不会重复 +1。

### 3.3 完整流程

```
前端 run deepResearch
  → mutation m_ai_runDeepResearch （立即返回, <1s）
      1. 事务内：更新 scan 的 images（owner-scoped 校验归属）
      2. 事务内：配额预检（used >= limit → QUOTA_EXCEEDED 回滚）
      3. 事务内：创建 ScanDeepResearch 记录 status=20, file_key=null, docVersion=当前号
      4. 事务提交后：经 Spring 管理 executor 启动虚拟线程后台任务
      5. 返回 { deepResearchId, status: 20 }
  → 后台任务 （1–3 min，脱离请求事务与 ActionContext）
      a. 调用 AI（事务外）
      b. AI 失败 OR scan_status 非 SUCCESS/PARTIAL：
           CAS 事务：status 20→40, 写 error_code/error_details(含 scan_status)
      c. AI 成功：
           - 组装 doc JSON（见 §5）
           - 上传 R2 bucket=u2，key 见 §4（重试 2–3 次固定间隔）
           - R2 最终失败：CAS 事务 status 20→40, error_code=R2_UPLOAD_FAILED
           - R2 成功：**单条件 UPDATE**（见 §3.4）
               · CAS：status 20→30, 写 file_key
               · 若 (created_at,id) 比当前 latest 新：回写 scan_record AI 字段
                 + latest_deep_research_id=当前 + 配额 +1（封顶）
               · 否则（旧任务晚完成）：仅置 SUCCESS 存历史，不动 scan_record pointer，不扣配额
                 （ponytail: 旧任务不计配额——配额跟随「成为 latest 的那次成功」，避免重复计数）
  → 前端（方案 B，§7）
      mutation → deepResearchId
      轮询 q_ai_getDeepResearchStatus(deepResearchId) 每隔几秒（仅返回状态，不含 resultUrl）
        - IN_PROGRESS：updated_at 超 10 min → CAS 置 FAILED(error_code=TIMEOUT)，返回 FAILED；
                       否则返回 IN_PROGRESS 继续轮询
        - FAILED：返回 status + error_code + scanStatus（供补拍提示）
        - SUCCESS：
            → q_ai_findMyScanById(scanRecordId) 取权威 ScanRecord + latestDeepResearch{id, resultUrl}
            → 按 latestDeepResearch.resultUrl 下载 doc，取顶级 premiumResult
            → 校验 doc 的 scanRecordId / deepResearchSnapshot.id 与 API 元数据一致
            → 以 API ScanRecord 为基础，挂上 premiumResult
            → SQLite 单事务覆盖本地
```

### 3.4 成功回写的权威指针与并发（决策 7/8/9，前端 agent P0#1/#2）

废弃 `is_latest` 落库权威语义，改用 `scan_record.latest_deep_research_id`：

- **唯一权威指针**：`scan_record.latest_deep_research_id`（nullable UUID）。
- **latest 定义**：发起时间最新且成功的任务，比较用 `(created_at, id)`（id 兜底同时间）。
- **成功回写（单条件事务）**：
  1. CAS：`UPDATE core_ai_scan_deep_research SET status=30 WHERE id=? AND status=20`。affected=0 → 已被终结，放弃。
  2. affected=1 后，判断当前任务是否比 `latest_deep_research_id` 指向的任务新（比 `(created_at,id)`）：
     - **是**：同一事务更新 `scan_record.basic_result / has_deep_search / prompt_version / latest_deep_research_id = 当前id`，并配额 +1（封顶）。
     - **否**：仅保留为成功历史，不动 scan_record，不扣配额。
  3. ScanRecord 的 AI 字段与 pointer 必须在**同一条件 UPDATE** 中一起改——保证 scan_record.basic_result 与 latest_deep_research_id（进而其 file_key 指向的 premiumResult）永远指向同一次成功（不会「一个 5 元、一个 10 元」）。
- **不加显式锁、不限制并发 IN_PROGRESS**：允许用户随时重跑；异常任务不阻塞再次发起。并发期间前端可能短暂看到旧版本，但最终以 latest pointer 收敛。PostgreSQL 行级 UPDATE 的毫秒级同步足够，无业务级锁。
- `isLatest` 不落库：需要时按 `deepResearch.id == scanRecord.latestDeepResearchId` 推导。

## 4. R2 对象 key 格式与 bucket

```
data/project=antique/type=deep_research/year=2026/month=10/day=02/{deepResearchId}.json
```

- Hive 风格分区路径，便于离线分析。`{deepResearchId}` 为记录主键 UUID。日期用创建时间（UTC）。Content-Type `application/json`。

**bucket 配置**（私有，仅 presignDownload）：

| 环境 | bucket-name | public-url |
|------|-------------|-----------|
| dev | `u2dev` | 留空（私有） |
| prod | `u2p` | 留空（私有） |

**presign host（前端 agent P0#3）**：presigned URL **必须使用 R2 原始 S3 API endpoint**（`*.r2.cloudflarestorage.com`）。Cloudflare 明确不支持用 custom domain 签名（会 SignatureDoesNotMatch）。自定义域名 `u2dev.ifmix.com`/`u2.ifmix.com` **不参与签名**，本期不做 custom domain 下载。现有 `S3ObjectStorage.presignDownload` 从共享 presigner endpoint 生成 host，符合此要求，直接复用。

## 5. doc JSON 内容结构（决策 11）

```json
{
  "docVersion": 1,
  "promptVersion": "v10",
  "scanRecordSnapshot": {
    "id": "...",
    "status": 20,
    "locale": "...", "country": "...", "currency": "...",
    "images": [ ... ],
    "basicResult": { ... },
    "hasDeepSearch": true,
    "...": "AI 回写后的完整 scan_record 快照（分析留档）"
  },
  "deepResearchSnapshot": {
    "id": "...",
    "scanRecordId": "...",
    "status": 30,
    "...": "改完后的 deep_research 记录信息（分析留档）"
  },
  "premiumResult": { ... }
}
```

- **docVersion**：doc JSON 结构 schema 版本（区别于 promptVersion）。DB 也冗余存一份，供将来 doc 迁移时不下载文件即可按版本筛选。
- **scanRecordSnapshot**：AI 成功那一刻的完整 ScanRecord 快照，**纯分析留档**。命名即表明「快照、非权威现值」——前端方案 B **不**用它覆盖本地（basicResult 权威以 `q_ai_findMyScanById` 为准）。此快照解决前端 agent P1#7：doc 完整可分析，但不作为前端覆盖源，故不会覆盖用户在 AI 运行期间改的收藏/备注。
- **deepResearchSnapshot**：改完后的 deep_research 记录信息（id/scanRecordId/status 等），纯分析留档。
- **premiumResult**：**顶级字段**。前端只从 doc 摘此字段，挂到 API 返回的 ScanRecord 的 `latestDeepResearch` 上。前端读取路径从 doc 顶层 `premiumResult` 取后，映射到本地 `record.deepResearch.premiumResult`（与现有 `premiumOf` 消费路径一致）。

## 6. 数据模型变更

### 6.1 `core_ai_scan_deep_research` 表

| 字段 | 变更 | 说明 |
|------|------|------|
| `scan_record_id` | **去掉 @Key 唯一约束**，改普通列 | 一个 scan 可有多条历史记录 |
| `premium_result` | **保留**（不再写新数据） | 旧数据回退读；新记录为 null，结果在 R2 |
| `prompt_version` | 保留 | AI 提示词版本 |
| `status` | **新增** Int NOT NULL | 10/20/30/40（见 §3.1） |
| `doc_version` | **新增** Int NOT NULL | doc JSON 结构版本（默认当前值，如 1），供将来 doc 迁移筛选 |
| `file_key` | **新增** String?（nullable） | 对象存储 key（R2，未来可换 S3，故不叫 r2_key）；SUCCESS 后写入；旧数据/未成功为 null |
| `error_code` | **新增** String?（nullable） | 稳定错误码：AI_FAILED / AI_STATUS_REJECTED / R2_UPLOAD_FAILED / TIMEOUT。对外暴露，不含内部异常细节 |
| `error_details` | **新增** JSONB?（nullable） | 结构化失败详情；AI_STATUS_REJECTED 时含 scan_status（供前端提示补拍）。不原样回传内部异常栈 |

> 不再新增 `is_latest` 列（决策 7 废弃落库语义）。latest 由 `scan_record.latest_deep_research_id` 推导。

### 6.2 `core_ai_scan_record` 表

| 字段 | 变更 | 说明 |
|------|------|------|
| `latest_deep_research_id` | **新增** UUID?（nullable） | 权威指针：指向当前有效（最新且成功）的 deep research 记录。逻辑外键（不用 @ManyToOne） |

### 6.3 索引变更

- **删除** `scan_record_id` 唯一索引。
- **新增**普通索引 `(scan_record_id, created_at)`：查某 scan 历史、按序比较 latest 用。
- **不再**需要 is_latest 的 partial unique index（权威改为单指针，无双 latest 概念）。

### 6.4 Entity 修改

- `ScanDeepResearch.kt`：去 `@Key`；`premiumResult` 保持 `@Serialized` 可空；新增 `status: Int`、`docVersion: Int`、`fileKey: String?`、`errorCode: String?`、`errorDetails: ...?`（JSONB，`@Serialized Map<String,Any?>?` 或专用类型）。
- `ScanRecord.kt`：新增 `latestDeepResearchId: UUID?`。

### 6.5 迁移处理（旧数据，决策 10）

- 线上基本无真实数据，不做结果迁移。
- Flyway：为新列设默认；现有旧 deep_research 行设 `status=30`、`doc_version=1`、`file_key=null`、`error_*=null`；对应 `scan_record.latest_deep_research_id` 回填为该 scan 唯一一条旧记录的 id（旧数据一 scan 一条）。
- 读取双路径：`latest_deep_research_id` 指向的记录，`file_key != null` 走 R2 doc，否则回退读 `premium_result` 列。

## 7. GraphQL Schema 变更（`schema/customer/ai.graphqls`）

### 7.1 Mutation `m_ai_runDeepResearch` 返回值

```graphql
type RunDeepResearchResult {
  "新建的 deep research 任务 id，前端据此轮询"
  deepResearchId: UUID!
  "任务状态：20=IN_PROGRESS（创建即此值）"
  status: Int!
}
```

### 7.2 新增轮询 Query（仅返回状态，不读 R2 内容，不含 resultUrl）

轮询只报任务状态。SUCCESS 后前端另调 `q_ai_findMyScanById` 从权威 ScanRecord 的 `latestDeepResearch` 取 resultUrl（职责分离：状态归状态，权威结果指针归 ScanRecord）。

```graphql
extend type Query {
  "按 deepResearchId 轮询任务状态"
  q_ai_getDeepResearchStatus(deepResearchId: UUID!): DeepResearchStatus!
}

type DeepResearchStatus {
  deepResearchId: UUID!
  "20=IN_PROGRESS, 30=SUCCESS, 40=FAILED"
  status: Int!
  "FAILED 时返回：稳定错误码（AI_FAILED/AI_STATUS_REJECTED/R2_UPLOAD_FAILED/TIMEOUT）"
  errorCode: String
  "FAILED 且 AI_STATUS_REJECTED 时返回 scan_status（供前端提示补拍）"
  scanStatus: JSON
}
```

### 7.3 `ScanRecord.latestDeepResearch` 字段

```graphql
type ScanRecord {
  # ... 现有字段 ...
  "当前有效（最新且成功）的 deep research；按 latest_deep_research_id 加载"
  latestDeepResearch: ScanDeepResearch
}

type ScanDeepResearch {
  id: UUID!
  scanRecordId: UUID!
  "SUCCESS 时返回：presigned download URL（前端据此下载 premiumResult）"
  resultUrl: String
  "旧数据兼容：仅旧 DB 行走 premium_result 列返回；新 R2 数据为 null，走 resultUrl"
  premiumResult: JSON
  createdAt: DateTime!
  updatedAt: DateTime
}
```

- `ScanRecord.deepResearch`（旧字段）→ 改为 `latestDeepResearch`，按 `latest_deep_research_id` 加载（不再按 is_latest 任意查）。DataLoader key 改为 scanRecord 的 `latest_deep_research_id`。
- **列表查询移除 premiumResult**（前端 agent P1#8）：避免每条记录触发一次 R2 GET。premiumResult 仅在详情页经 `resultUrl` 下载获取。
- `premiumResult` 字段仅为旧 DB 数据兼容保留（走 premium_result 列）；新数据恒为 null，前端走 `resultUrl`。

## 8. 分层落点（遵循 AGENTS.md 架构约定；前端 agent P1#5）

| 层 | 文件 | 改动 |
|----|------|------|
| Schema | `schema/customer/ai.graphqls` | 改 `RunDeepResearchResult`；加 `DeepResearchStatus` + `q_ai_getDeepResearchStatus`；`ScanRecord.deepResearch`→`latestDeepResearch`；`ScanDeepResearch` 加 `resultUrl` |
| DataFetcher | `bff/graphql/customer/ai/AiFetcher.kt` | `runDeepResearch` 改为创建记录+预检+提交后启动后台任务；新增 `getDeepResearchStatus`；DataLoader 改按 latest_deep_research_id |
| Facade/Service | **新增** `modules/ai/DeepResearchTaskService.kt`（或 Facade） | 后台任务协调：接收不可变任务上下文；AI/R2 在事务外；DB 阶段经 TxRunner 用 writer。**不在 Handler 开事务** |
| Facade | `modules/ai/AiFacade.kt` | 转发 `createDeepResearchTask`、`getDeepResearchStatus` |
| Handler | `modules/ai/handler/ScanAggHandler.kt` | 创建 IN_PROGRESS 记录（预检）；成功回写的 CAS + 条件 pointer 更新 + 配额自增；惰性超时 CAS |
| Repository | `modules/ai/repo/ScanDeepResearchRepository.kt` | 去 upsert；新增 `insert`、`findById`(owner-scoped)、`casUpdateStatus`（CAS 终态）、按 scan 查序列 |
| Repository | `modules/ai/repo/ScanRecordRepository.kt` | 新增条件回写：`updateAiFieldsAndPointerIfNewer`（同事务改 AI 字段 + latest_deep_research_id，带 (created_at,id) 比较条件） |
| Repository | `modules/ai/repo/CustomerScanMetricsRepository.kt` | 配额自增复用现有 `tryIncrementDeepResearchCount`（成功时调用）。**无需** decrement（无退款） |
| Infra | `infra/storage/` + `application*.yml` | 新增 bucket `u2`（dev=`u2dev`/prod=`u2p`，public-url 留空）。presign 走原始 endpoint，无改造 |
| DTO | `dto/ai/DeepResearchResult.kt` | 扩展：doc 组装、docVersion 常量、error_code 映射 |

### 8.1 后台任务上下文与事务边界（前端 agent P1#5）

- 后台任务脱离 HTTP 请求线程/请求事务/请求 ActionContext。启动时**提取不可变上下文**传入：`projectId`、`customerId`、`deepResearchId`、`scanRecordId`、`images`、`locale/country/currency`、`promptVersion`。
- 后台构造的 ActionContext/ModuleCtx 必须设 `globalTxSql=null`、`inGlobalTx=false`、`preferReader=false`（走 writer，不复用请求级 globalTx）。
- 线程由 **Spring 生命周期管理的 executor** 启动（非散落裸虚拟线程），且在创建记录的**事务提交之后**才启动（否则后台可能读不到未提交的记录）。
- AI 调用在事务外；DB 阶段经 **TxRunner（Facade/Service 层接缝）** 开短事务，**不在 Handler 自开事务**（`GlobalTxRunner` 属 DataFetcher 层，Handler 不得用）。
- `scanRunner.run` 当前接收 `ActionContext`——后台需构造脱离请求的等价 action 载体。**实现期验证点**。

## 9. 错误处理与边界

| 场景 | 处理 |
|------|------|
| AI 调用抛异常 | CAS 20→40，error_code=AI_FAILED，error_details 存安全摘要（不含栈） |
| AI scan_status 非 SUCCESS/PARTIAL | CAS 20→40，error_code=AI_STATUS_REJECTED，error_details 含 scan_status（供补拍） |
| R2 上传失败 | 重试 2–3 次固定间隔；仍失败 CAS 20→40，error_code=R2_UPLOAD_FAILED |
| 查询惰性超时 | IN_PROGRESS 且 updated_at 超 10 min → CAS 20→40，error_code=TIMEOUT |
| 重复终结 | CAS affected=0 → 放弃后续（不重复扣配额/回写） |
| 旧任务晚完成 | CAS 置 SUCCESS 存历史，(created_at,id) 条件未命中则不动 scan_record、不扣配额 |
| owner-scoped | 创建、查询均校验 projectId+customerId；非本人 NOT_FOUND |
| 对外错误 | 只回 error_code + 安全文案 + （补拍场景）scan_status；内部异常不外泄 |

## 10. 测试计划（非框架、最小可运行校验）

1. **doc 组装纯函数**：断言 doc JSON 结构（docVersion/promptVersion/scanRecordSnapshot/deepResearchSnapshot/顶级 premiumResult）。
2. **终态 CAS 幂等**（P0#1）：模拟「超时置 FAILED」与「后台成功」竞争 → 只有一个终态落定；配额只变化一次（成功赢则 +1，超时赢则不扣且后台成功路径 CAS 落空不扣）。
3. **乱序完成的 latest 规则**（P0#2）：同 scan 两任务乱序完成 → `latest_deep_research_id` 指向 (created_at,id) 最新的成功任务；旧任务晚完成不覆盖 scan_record。
4. **配额成功才扣**：失败/超时不扣；成功 +1；预检 used>=limit 拒绝；并发轻微超额封顶（决策 4）。
5. **basicResult/pointer 原子性**（P1#7）：成功回写事务后，scan_record.basic_result 与 latest_deep_research_id 来自同一次成功。
6. **owner-scope**：跨 customer 查询/创建 NOT_FOUND（沿用 ScanOwnerScopeTest）。
7. R2 上传走 `ObjectStorage` 假实现，验证重试次数与失败落 FAILED。

## 11. 前端改造要点（方案 B，供前端 agent review）

- `runDeepResearchFlow`：`deepResearch(...)` 返回语义从 `Promise<ScanRecord>` 改为「拿 deepResearchId → 轮询 → SUCCESS 后重查权威 ScanRecord → 下载 premiumResult → 合并 → SQLite 覆盖」。
- **方案 B 一致性**（前端 agent P0 一致性 / P1#7）：
  - SUCCESS 后调 `q_ai_findMyScanById(scanRecordId)` 取权威 ScanRecord + `latestDeepResearch{id, resultUrl}`。
  - basicResult 以 **API ScanRecord 为准**（不取 doc 的 scanRecordSnapshot）。
  - 按 `latestDeepResearch.resultUrl` 下载 doc，取**顶级 `premiumResult`**。
  - 校验 doc 的 `scanRecordId` 与 `deepResearchSnapshot.id` 与 API 元数据一致，再把 premiumResult 挂到 API 的 ScanRecord 的 `latestDeepResearch` 上。
  - 若轮询任务 A 成功时服务端 latest 已是更新的 B，`findMyScanById` 返回的 latest 即 B，前端自动下载 B，避免「A 的 basicResult 配 B 的 premiumResult」。
  - 下载失败：保留旧本地记录，仅重试查询+下载。
- 轮询间隔几秒一次；处理 20/30/40 三态 UI。FAILED 的 AI_STATUS_REJECTED 从 `scanStatus` 读推荐补拍（沿用 `deepResearchRejectionReason`），其它 error_code 做通用失败提示。
- **premiumResult 读取路径**：从 doc 顶级 `premiumResult` 取后，映射到本地 `record.deepResearch.premiumResult`，与现有 `premiumOf` 消费路径一致，解析不变。
- **SQLite 任务恢复（本期必做，前端 agent P1#8）**：
  - scan 行加 nullable `pendingDeepResearchId`；mutation 成功写入；SUCCESS/FAILED 清空。
  - 冷启动/回前台有 pending ID 则恢复轮询。
  - 查询返回 NOT_FOUND：仅把 `pendingDeepResearchId` 置 null，**不**清空 ScanRecord / 已有 deepResearch。
  - 后端 (created_at,id)+latest pointer 规则保证旧任务（恢复的 pending）不覆盖更新任务。

## 12. GraphQL 交付清单（Trusted Documents，前端 agent P2#9）

**服务端（ifmix_server）：**
- `schema/customer/ai.graphqls`：改 `RunDeepResearchResult`；加 `DeepResearchStatus` + `q_ai_getDeepResearchStatus`；`ScanRecord.deepResearch`→`latestDeepResearch`；`ScanDeepResearch` 加 `resultUrl`。
- `resources/graphql/persisted-queries/customer/customer.json`：加入新 Query 与改造后 mutation、`q_ai_findMyScanById`（若字段变更）条目。
- DGS codegen 类型随编译更新。

**前端（antique）：**
- `apps/shared/src/api/graphql.ts` 的 `_API_ENTRIES`（现 graphql.ts:394-401 一带只注册 mutation）：新增 `q_ai_getDeepResearchStatus`；更新 `m_ai_runDeepResearch`（返回值已改）与 `q_ai_findMyScanById`（含 latestDeepResearch）的 query 文本。
- 重新生成 schema 类型（`apps/shared/src/api/graphql/generated/graphql.ts`）与前端 persisted-query，与服务端 `customer.json` 1:1 对齐（缺任一侧线上即被拒）。

## 13. R2 对象生命周期（删除策略）

- **用户删除 scan**：`ScanRecord` 实现 `SoftDeletableProps`，为**逻辑删除**，R2 doc 保留（历史分析需要）。无需删 R2。
- **历史版本**：每次新建记录 + R2 对象，旧版本保留做分析，长期留存——设计意图，非泄漏。
- **匿名客户物理清理**（core-job `physicalDeleteByCustomers`，PHYSICAL 硬删）：硬删 DB 行后 R2 doc 成孤儿。**本期已知限制**：不在清理路径删 R2，`ObjectStorage` 不加 delete。后续如需回收，用 R2 lifecycle retention 或补 core-job 删除逻辑（运维/后续项，不阻塞本期）。

## 14. 实现期验证点

1. §8.1 后台构造脱离请求的 ActionContext 以供 `scanRunner.run` 调用的具体方式。
2. §4 确认复用 `S3ObjectStorage.presignDownload`（原始 endpoint 签名）即满足；无需 custom domain 改造。
3. §6 Jimmer 对 JSONB `error_details` 的映射方式（`@Serialized` 或专用类型）。
