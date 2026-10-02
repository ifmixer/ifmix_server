# DeepResearch 异步化 + R2 存储 + 历史版本 设计文档

- 日期：2026-10-02
- 模块：`core-api` / `modules/ai`
- 相关前端：`/Users/jason/ai/myprojects/antique`（需同步改造，前端 agent review）

## 1. 背景与目标

当前 `m_ai_runDeepResearch` 是**同步三步**：事务内更新图片 → 事务外跑 AI（1–3 min）→ 事务内写回。存在三个问题，本次一并改造：

1. **结果体积大、无需 PG 查询**：`premium_result` 作为 JSONB 存在 `core_ai_scan_deep_research`，数据大且几乎只整体读取。→ 移到 R2（对象存储），DB 只存指针（`r2_key`）。
2. **同步调用易被网关超时**：AI 调用常 >1 min、偶达 3 min，HTTP 请求长时间挂起易触发网关超时。→ 异步化：mutation 立即创建记录并返回，后台虚拟线程跑 AI，前端轮询状态。
3. **覆盖式更新丢失历史**：当前按 `scanRecordId` 唯一键 upsert，新结果覆盖旧结果。→ 每次新建记录 + `is_latest` 标记，保留全部历史版本用于分析。

## 2. 关键设计决策（已确认）

| # | 决策 | 选择 |
|---|------|------|
| 1 | 异步执行载体 | **进程内虚拟线程**（JDK 25，零新增依赖）。进程重启丢任务，靠超时兜底 |
| 2 | R2 上传失败处理 | **有限次重试（2–3 次固定间隔）后再 FAILED**。AI 成本高，不因 R2 抖动丢结果 |
| 3 | 配额扣减时机 | **创建时扣 + 失败退还**。防并发刷配额；AI/R2 失败退还额度 |
| 4 | 前端读取结果方式 | **presigned download URL（私有）**，前端直连 R2 下载 doc，大 JSON 不过 API 服务器 |
| 5 | 结果 bucket | **独立 bucket `u2`**（与 image 的 `ugc`/u1 分开），私有，仅 presignDownload |
| 6 | is_latest 并发安全 | **PostgreSQL partial unique index** 兜底 + 应用层同事务两步 UPDATE |
| 7 | 旧数据迁移 | **保留 `premium_result` 列，不迁移旧数据**；读取双路径（r2_key 优先，回退列） |
| 8 | doc 内容结构 | 完整 scanRecord（含 basicResult）+ deepResearch（含 premiumResult 嵌套）+ promptVersion + docVersion |
| 9 | 僵死 IN_PROGRESS 兜底 | **查询时惰性判定 + 落库**：超 10 min 置 FAILED、退配额、写 error_reason |

## 3. 整体数据流与状态机

### 3.1 状态机

```
CREATED(10) → IN_PROGRESS(20) → SUCCESS(30)
                              └→ FAILED(40)
```

- `10 CREATED`：语义完整性预留，实际落库直接写 `20`。
- `20 IN_PROGRESS`：mutation 创建记录即此状态，后台任务进行中。
- `30 SUCCESS`：AI 成功 + 结果已上传 R2 + DB 回写完成。
- `40 FAILED`：AI 失败 / AI scan_status 非 SUCCESS·PARTIAL / R2 上传最终失败 / 超时僵死。

### 3.2 完整流程

```
前端 run deepResearch
  → mutation m_ai_runDeepResearch （立即返回, <1s）
      1. 事务内：更新 scan 的 images（owner-scoped 校验归属）
      2. 事务内：创建 ScanDeepResearch 记录
           status=IN_PROGRESS(20), is_latest=false,
           doc_version=当前版本号, r2_key=null
      3. 事务内：原子扣配额（+1，达上限则抛 QUOTA_EXCEEDED 并回滚）
      4. 提交后：启动虚拟线程跑后台任务（传入 deepResearchId + 必要上下文）
      5. 返回 { deepResearchId, status: IN_PROGRESS }
  → 虚拟线程后台任务 （1–3 min，脱离请求事务与 ActionContext）
      a. 调用 AI（事务外）
      b. AI 失败 OR scan_status 非 SUCCESS/PARTIAL：
           事务内：status=FAILED, error_reason=原因, 退还配额(-1)
      c. AI 成功：
           - 组装 doc JSON（见 §5）
           - 上传 R2 bucket=u2，key 见 §4（重试 2–3 次固定间隔）
           - R2 最终失败：事务内 status=FAILED, error_reason="r2 upload failed", 退配额
           - R2 成功：事务内（单事务，顺序如下）
               i.   回写 scan_record.basicResult + hasDeepSearch + promptVersion
               ii.  同 scanRecordId 的旧记录 is_latest = false
               iii. 当前记录 status=SUCCESS(30), is_latest=true, r2_key=...
  → 前端轮询 q_ai_getDeepResearchStatus(deepResearchId) 每隔几秒
      - IN_PROGRESS：
          · 若 updated_at 超 10 min → 惰性判定：落库 status=FAILED,
            error_reason="timeout: exceeded 10min", 退配额，返回 FAILED
          · 否则返回 IN_PROGRESS，前端继续轮询
      - FAILED：返回 status + error_reason（+ scan_status 若有，供修正提示）
      - SUCCESS：返回 presigned download URL → 前端下载 doc → 覆盖本地整条记录
```

### 3.3 配额语义（决策 3：创建时扣 + 失败退还）

- **扣减**：步骤 2–3 在**同一事务**内创建记录并原子自增 `deep_research_count`；仅当 `count < limit` 时 +1，否则 `QUOTA_EXCEEDED` 回滚（防止用户并发狂点刷爆配额——每次发起都先占额度）。
- **退还**：后台任务 FAILED 分支、以及查询惰性超时判定分支，均原子自减 `deep_research_count`（-1，下限 0）。退还与状态落库在同一事务。
- 复用现有 `CustomerScanMetricsRepository`：新增 `tryIncrement...`（已存在）对称的 `decrement...` 方法（原子 `-1`，`GREATEST(count-1, 0)` 防负）。

## 4. R2 对象 key 格式

```
data/project=antique/type=deep_research/year=2026/month=10/day=02/{deepResearchId}.json
```

- Hive 风格分区路径，便于后续离线分析（按 project/type/日期扫描）。
- `{deepResearchId}` 为记录主键 UUID，保证唯一。
- bucket：`u2`（私有）。日期用记录创建时间（UTC）。
- Content-Type：`application/json`。

## 5. doc JSON 内容结构

```json
{
  "docVersion": 1,
  "promptVersion": "v10",
  "scanRecord": {
    "id": "...",
    "status": 20,
    "locale": "...", "country": "...", "currency": "...",
    "images": [ ... ],
    "basicResult": { ... },
    "hasDeepSearch": true,
    "...": "DB 回写后的完整 scan_record 快照"
  },
  "deepResearch": {
    "id": "...",
    "scanRecordId": "...",
    "status": 30,
    "isLatest": true,
    "createdAt": "...",
    "premiumResult": { ... }
  }
}
```

- **docVersion**：doc JSON 的结构 schema 版本（区别于 promptVersion）。读取方据此判断按哪种结构解析；DB 也冗余存一份（见 §6 理由）。
- **premiumResult 嵌在 `deepResearch.premiumResult`**（决策 8/B）：与前端 `scanSelectors.premiumOf(record.deepResearch?.premiumResult)` 现有读取路径一致，前端下载 doc 覆盖本地后读取逻辑不变。
- **scanRecord 为 DB 回写后的完整快照**：前端下载后直接覆盖本地整条记录（与现有 `runDeepResearchFlow` 返回整个 ScanRecord 覆盖本地的模式一致）。
- `scanRecord.basicResult` 既在 R2 doc（给前端覆盖），也回写 DB `scan_record` 列（列表页 `extractColumns`/`scanColumns` 依赖它做查询副本）。二者同源，无冲突。

## 6. 数据模型变更

### 6.1 `core_ai_scan_deep_research` 表

| 字段 | 变更 | 说明 |
|------|------|------|
| `scan_record_id` | **去掉 @Key 唯一约束**，改普通列 | 一个 scan 可有多条历史记录 |
| `premium_result` | **保留**（不再写新数据） | 旧数据回退读；新记录为 null，结果在 R2 |
| `prompt_version` | 保留 | AI 提示词版本 |
| `status` | **新增** Int NOT NULL | 10/20/30/40（见 §3.1） |
| `is_latest` | **新增** Boolean NOT NULL default false | 最新版标记 |
| `doc_version` | **新增** Int NOT NULL | doc JSON 结构版本（默认当前值，如 1）。DB 存它的用途：将来 doc 结构迁移时，不下载文件即可按版本批量筛选/定位需迁移的记录 |
| `r2_key` | **新增** String?（nullable） | R2 对象 key；SUCCESS 后写入；旧数据/未成功为 null |
| `error_reason` | **新增** String?（nullable） | FAILED 时记录原因（AI 失败 / r2 upload failed / timeout 等），供排查与前端提示 |

### 6.2 索引变更

- **删除** `scan_record_id` 唯一索引。
- **新增**普通索引 `(scan_record_id, is_latest)`：查最新版（`is_latest=true`）与查历史列表共用。
- **新增** partial unique index（决策 6 兜底）：
  ```sql
  CREATE UNIQUE INDEX ux_deep_research_latest
    ON core_ai_scan_deep_research (scan_record_id)
    WHERE is_latest = true;
  ```
  数据库强制每个 scan 最多一条 latest。应用层两步 UPDATE（先旧全置 false、再当前置 true）在同一事务内按序执行，靠此索引根除并发产生双 latest 的可能。

### 6.3 Entity 修改（`ScanDeepResearch.kt`）

- 去掉 `scanRecordId` 上的 `@Key`。
- `premiumResult` 保持 `@Serialized` 可空（兼容旧数据）。
- 新增：`status: Int`、`isLatest: Boolean`、`docVersion: Int`、`r2Key: String?`、`errorReason: String?`。

### 6.4 迁移处理（旧数据，决策 7）

- 线上基本无真实数据，不做结果迁移。
- Flyway 迁移为新列设默认，并把现有旧行统一为**有效当前版本**：`status=30(SUCCESS)`、`is_latest=true`、`doc_version=1`、`r2_key=null`、`error_reason=null`。
- 读取逻辑统一：查 `is_latest=true` 的记录，`r2_key != null` 走 R2 取 doc，否则回退读 `premium_result` 列（旧数据）。

## 7. GraphQL Schema 变更（`schema/customer/ai.graphqls`）

### 7.1 Mutation `m_ai_runDeepResearch` 返回值改造

异步化后立即返回，不再返回最终 scanRecord。改为返回任务句柄：

```graphql
type RunDeepResearchResult {
  "新建的 deep research 任务 id，前端据此轮询"
  deepResearchId: UUID!
  "任务状态：20=IN_PROGRESS（创建即此值）"
  status: Int!
}
```

### 7.2 新增轮询 Query

```graphql
extend type Query {
  "按 deepResearchId 轮询任务状态；SUCCESS 时附 presigned download URL"
  q_ai_getDeepResearchStatus(deepResearchId: UUID!): DeepResearchStatus!
}

type DeepResearchStatus {
  deepResearchId: UUID!
  "20=IN_PROGRESS, 30=SUCCESS, 40=FAILED"
  status: Int!
  "SUCCESS 时返回：R2 doc 的 presigned 下载 URL（私有，短时有效）"
  resultUrl: String
  "FAILED 时返回：失败原因"
  errorReason: String
  "FAILED 且来自 AI scan_status 时返回，供前端提示用户修正"
  scanStatus: JSON
}
```

### 7.3 历史版本查询（分析用，可选纳入本期）

```graphql
extend type Query {
  "列出某 scan 的全部 deep research 历史版本（按 createdAt 倒序）"
  q_ai_listDeepResearchVersions(scanRecordId: UUID!): [DeepResearchStatus!]!
}
```

> 若本期暂不需要历史分析查询接口，可仅建表与写入侧（保留历史记录），查询接口留待后续。设计上数据已齐备。

### 7.4 `ScanDeepResearch` type 调整

现有 `ScanRecord.deepResearch` DataLoader 字段的 `premiumResult` 来源改为：从 `is_latest=true` 记录的 R2 doc 读取（有 r2_key）或回退 `premium_result` 列。DataLoader 需相应调整为按 `(scanRecordId, is_latest=true)` 取当前版本。

> 注意：若 `deepResearch.premiumResult` 走 R2，DataLoader 批量读取会触发 R2 拉取（N 条 scan → N 次 R2 GET）。考虑是否保留该字段的服务端解析，或改为前端始终走 `resultUrl` 下载。**本期建议**：前端详情页统一走 `q_ai_getDeepResearchStatus` 的 `resultUrl` 下载 doc，`ScanRecord.deepResearch.premiumResult` 字段仅为旧数据/兼容保留（走 premium_result 列）。此点需前端 review 确认。

## 8. 分层落点（遵循 AGENTS.md 架构约定）

| 层 | 文件 | 改动 |
|----|------|------|
| Schema | `schema/customer/ai.graphqls` | 改 `RunDeepResearchResult`，加 `DeepResearchStatus` + 两个 Query |
| DataFetcher | `bff/graphql/customer/ai/AiFetcher.kt` | `runDeepResearch` 改为创建记录+扣配额+启动虚拟线程；新增 `getDeepResearchStatus`（+ 可选 `listDeepResearchVersions`）；DataLoader 调整 |
| Facade | `modules/ai/AiFacade.kt` | 转发新方法：`createDeepResearchTask`、`getStatus`、`runDeepResearchAsync`（后台入口） |
| Handler | `modules/ai/handler/ScanAggHandler.kt` | 创建 IN_PROGRESS 记录+扣配额；后台任务编排（AI→R2→回写）；惰性超时判定；is_latest 切换 |
| Repository | `modules/ai/repo/ScanDeepResearchRepository.kt` | 去唯一键相关逻辑；新增 `insert`（非 upsert）、`findLatestByScanRecordId`、`markOthersNotLatest`、`updateStatus`、`findById`（owner-scoped） |
| Repository | `modules/ai/repo/CustomerScanMetricsRepository.kt` | 新增原子 `decrementDeepResearchCount`（退还，`GREATEST(count-1,0)`） |
| Infra | `infra/storage/StorageConfig.kt` + `application*.yml` | 新增 bucket `u2` 配置（私有，public-url 留空） |
| DTO | `dto/ai/DeepResearchResult.kt` | 保留并可能扩展（doc 组装、docVersion 常量） |

### 8.1 虚拟线程后台任务的上下文传递

后台任务脱离 HTTP 请求线程与请求事务，需注意：

- 不能持有请求级 `ActionContext`（请求结束即失效）。启动任务时**提取并传入**必要值：`projectId`、`customerId`（actorId）、`deepResearchId`、`scanRecordId`、`images`、`locale/country/currency`、`promptVersion`。
- 后台任务内自建 `ModuleCtx`（经 `ModuleCtxFactory`，不依赖 `ActionContextHolder.current()`）。
- AI 调用在事务外；DB 回写各步各自开事务（经 Facade/GlobalTxRunner 等价机制）。
- `scanRunner.run` 当前接收 `sc.action`（ActionContext）——需确认后台可用的等价调用方式，或构造一个脱离请求的轻量 action 载体。**此点为实现期需验证的技术细节**。

## 9. 错误处理与边界

- **AI 调用抛异常**：捕获 → status=FAILED，error_reason=异常摘要，退配额。
- **AI 返回 scan_status 非 SUCCESS/PARTIAL**（INSUFFICIENT_IMAGE / NON_PHYSICAL_SUBJECT）：status=FAILED，error_reason=该状态，scan_status 存入（供前端提示补拍）。*（沿用现有 `DeepResearchResult.isSuccess` 判定逻辑）*
- **R2 上传失败**：固定间隔重试 2–3 次；仍失败 → FAILED，error_reason="r2 upload failed"，退配额。
- **僵死超时**：查询时 IN_PROGRESS 且 updated_at 超 10 min → 落库 FAILED，error_reason="timeout: exceeded 10min"，退配额。
- **owner-scoped**：创建、查询、历史列表均校验 `projectId + customerId` 归属；非本人统一 NOT_FOUND（沿用现有约定）。
- **配额并发**：扣减用原子条件自增（`count < limit` 才 +1），退还用原子自减（下限 0）。

## 10. 测试计划（非框架、最小可运行校验）

遵循现有测试风格（`DeepResearchResultTest` 等纯函数测试 + repo DB 测试）：

1. **doc 组装纯函数测试**：给定 scanRecord + deepResearch + premiumResult，断言 doc JSON 结构（docVersion / promptVersion / 嵌套 premiumResult）正确。
2. **is_latest 切换测试**（repo/DB）：插入多条同 scanRecordId，调用「置旧为 false + 当前为 true」后，断言恰好一条 is_latest=true；并验证 partial unique index 阻止双 latest。
3. **配额扣减/退还测试**：创建扣 1、FAILED 退 1，断言计数；达上限创建抛 QUOTA_EXCEEDED。
4. **惰性超时判定测试**：构造 updated_at 超 10 min 的 IN_PROGRESS，查询后断言落库为 FAILED + error_reason + 配额退还。
5. **owner-scope 测试**：跨 customer 查询/创建抛 NOT_FOUND（沿用 `ScanOwnerScopeTest` 模式）。
6. R2 上传走抽象 `ObjectStorage`，测试用假实现验证重试次数与失败落库。

## 11. 前端改造要点（供前端 agent review）

- `runDeepResearchFlow`：`deepResearch(...)` 调用改为「调用 mutation 拿 deepResearchId → 轮询 `q_ai_getDeepResearchStatus` → SUCCESS 后用 `resultUrl` 下载 doc → 覆盖本地整条记录」。原先返回整个 ScanRecord 的语义，改由下载 doc 的 `scanRecord` 字段提供。
- 轮询间隔建议几秒一次；需处理 IN_PROGRESS / SUCCESS / FAILED 三态 UI。
- FAILED 分支沿用现有 `deepResearchRejectionReason`：INSUFFICIENT_IMAGE / NON_PHYSICAL_SUBJECT 从 `scanStatus` 读，提示补拍；其它 error_reason 做通用失败提示。
- doc 的 `deepResearch.premiumResult` 嵌套结构与现有 `premiumOf` 读取路径一致，解析逻辑不变。
- 本地 DB 覆盖：用 doc 的 `scanRecord`（含 basicResult）覆盖本地记录，`deepResearch` 覆盖 `record.deepResearch`。

## 12. 未决 / 需 review 确认点

1. §7.3 历史版本查询接口是否纳入本期（或仅写入侧保留历史）。
2. §7.4 `ScanRecord.deepResearch.premiumResult` 是否仍由服务端解析 R2（DataLoader N 次 R2 GET），还是前端统一走 `resultUrl`。倾向后者。
3. §8.1 后台虚拟线程中 `scanRunner.run` 的 ActionContext 等价调用方式（实现期验证）。
