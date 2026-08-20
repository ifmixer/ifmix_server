# 剩余整理计划 — 对齐 layering-refactor

> 创建时间: 2026-08-20
> 状态: 待执行
> 前提: 分层重构主体已完成（ModuleCtx / AggHandler / GlobalTxRunner / CrudRepoTemplate）
> 对照: `docs/superpowers/plans/2026-08-20-layering-refactor.md` 验证清单

---

## 问题总览

| # | 严重度 | 问题 | 涉及文件 |
|---|--------|------|----------|
| 1 | 🔴 CRITICAL | OperationContextHolder 未设置，DataLoader 运行时崩 | OperationContextProvider / AiFetcher |
| 2 | 🔴 CRITICAL | newScan 外部 AI 调用在 globalTx.withTx 事务内 | AiFetcher / ScanAggHandler |
| 3 | 🟠 HIGH | AiFetcher 直接注入 repo + mcFactory（跨层） | AiFetcher |
| 4 | 🟠 HIGH | WebhookController 直接注入 repo（跨层） | WebhookController |
| 5 | 🟠 HIGH | ScanRecordsDataLoader 逐个 findById（O(N)） | AiFetcher |
| 6 | 🟡 MEDIUM | partialUpdate 未实现 unset 优先逻辑 | TodoRepo / ScanRecordRepo |
| 7 | 🟡 MEDIUM | ai 模块 findByFilter 未接入 FilterGroupResolver | ScanAggHandler / ScanRecordRepo |
| 8 | 🟡 MEDIUM | Payload → Result 未重命名 | 全部 .graphqls + codegen 引用 |
| 9 | 🟡 MEDIUM | Operation 前缀未缩短 query_/mutation_ → q_/m_ | 全部 .graphqls + DataFetcher |
| 10 | 🟢 LOW | BaseCrudRepositoryTest 引用已删除类 | test 文件 |
| 11 | 🟢 LOW | ScanRecordRepo 仍用 table.get<UUID>("appId") | ScanRecordRepo |
| 12 | 🟢 LOW | authJwtService 在 Config @Bean 注册 | AuthConfig |

---

## Phase 1: 运行时 Bug 修复（立即）

### 1.1 OperationContextHolder 设置

**问题**: `OperationContextHolder.current()` 在 `ScanRecordsDataLoader` 和 `AiFetcher.scanRecord()` 中被调用，但从未 `set()`。运行时必抛 `IllegalStateException`。

**方案 A（推荐）**: 在 `OperationContextProvider.fromDfe()` 末尾设置 ThreadLocal：

```kotlin
// OperationContextProvider.kt
fun fromDfe(dfe: DgsDataFetchingEnvironment): OperationContext {
    // ... 构建 ctx ...
    OperationContextHolder.set(ctx)  // ← 新增
    return ctx
}
```

并在 DGS 请求生命周期结束时 `clear()`（通过 `DgsExecutionResult` 回调或 request filter）。

**方案 B**: 改 DataLoader 为 `MappedBatchLoaderWithContext`，通过 DGS GraphQL context 传递 opCtx，不依赖 ThreadLocal。更安全（避免 Virtual Threads + ThreadLocal 陷阱），但改动更大。

**建议**: 先用方案 A 快速修复，后续迁移到方案 B。

**涉及文件**:
- `infra/graphql/OperationContextProvider.kt` — 加 `OperationContextHolder.set(ctx)`
- 新增 filter 或 DGS interceptor 做 `OperationContextHolder.clear()`

---

### 1.2 newScan AI 调用移出事务

**问题**: `AiFetcher.newScan` 把整个 `aiService.newScan()` 包在 `globalTx.withTx` 内。`ScanAggHandler.saveNewScan()` 内部调用 `scanRunner.run()` — 这是外部 HTTP AI 调用，耗时数秒，占住事务连接。

**修复**:

```kotlin
// AiFetcher.kt — 修改 newScan
@DgsMutation(field = "mutation_ai_createScan")
fun newScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: NewScanInput): NewScanPayload {
    val ctx = ctxProvider.fromDfe(dfe)
    // Step 1: AI 调用在事务外
    val aiResult = aiService.runAiScan(ctx, input)
    // Step 2: DB 写入在事务内
    val record = globalTx.withTx(ctx) { txCtx -> aiService.saveScanRecord(txCtx, aiResult) }
    return NewScanPayload(scanRecord = record)
}
```

```kotlin
// AiFacade.kt — 拆为两个方法
fun runAiScan(opCtx: OperationContext, input: NewScanInput): AiScanResult =
    scanHandler.runAiScan(mcFactory.forApp(opCtx), input)

fun saveScanRecord(opCtx: OperationContext, result: AiScanResult): ScanRecord =
    scanHandler.saveScanRecord(mcFactory.forApp(opCtx), result)
```

```kotlin
// ScanAggHandler.kt — 拆分
fun runAiScan(mc: ModuleCtx, input: NewScanInput): AiScanResult {
    // 解析 images, 调用 scanRunner.run(), 返回结果（不写 DB）
}

fun saveScanRecord(mc: ModuleCtx, result: AiScanResult): ScanRecord {
    // 只写 DB
}
```

**涉及文件**:
- `bff/graphql/customer/ai/AiFetcher.kt`
- `modules/ai/AiFacade.kt`
- `modules/ai/handler/ScanAggHandler.kt`
- 新增 `dto/ai/AiScanResult.kt`（中间结果数据类）

---

## Phase 2: 分层违规修复

### 2.1 AiFetcher 去除 repo + mcFactory 直注入

**问题**: AiFetcher 注入了 `ScanRecordRepository` 和 `ModuleCtxFactory`，违反"DataFetcher 只注入 Facade + GlobalTxRunner + ctxProvider"。

**修复**:

1. `AiFetcher.scanRecord()` 关联字段解析 → 改为 DataLoader（已有 `ScanRecordsDataLoader`），让 DataLoader 通过 Facade 或直接 repo 加载（DataLoader 允许注入 repo，因为它是 infra 层协作者）。
2. 从 AiFetcher 删除 `scanRecordRepo` 和 `mcFactory` 字段。

```kotlin
// AiFetcher — 关联字段改走 DataLoader
@DgsData(parentType = "ScanCollectionItem", field = "scanRecord")
fun scanRecord(dfe: DgsDataFetchingEnvironment): CompletableFuture<ScanRecord> {
    val item = dfe.getSource<ScanCollectionItem>()
    val scanRecordId = item.scanRecordId  // 需要 entity 暴露此字段
    val loader = dfe.getDataLoader<UUID, ScanRecord>(ScanRecordsDataLoader.NAME)
    return loader.load(scanRecordId)
}
```

**涉及文件**:
- `bff/graphql/customer/ai/AiFetcher.kt`

### 2.2 WebhookController 去除 repo 直注入

**问题**: `WebhookController` 注入 `AppConfigRepository` + `ModuleCtxFactory` 来查询 bundleId → appId 映射。

**修复**: 在 `AppConfigFacade` 中暴露 `findAppIdByBundleId` / `findAppIdByAndroidPackage` 方法，WebhookController 通过 Facade 调用。

```kotlin
// AppConfigFacade.kt — 新增
fun findAppIdByBundleId(bundleId: String): UUID? {
    // 这里需要一个不依赖 opCtx 的查询（webhook 无 appId header）
    // 用 mcFactory 的 default 或特殊 webhook context
}
```

**涉及文件**:
- `modules/app/AppConfigFacade.kt`
- `modules/app/handler/AppConfigAggHandler.kt`
- `bff/webhooks/WebhookController.kt`

---

### 2.3 ScanRecordsDataLoader 改为批量查询

**问题**: 逐个 `findById` → O(N) DB 调用。

**修复**:
```kotlin
override fun load(ids: Set<UUID>): CompletionStage<Map<UUID, ScanRecord>> {
    val opCtx = OperationContextHolder.current()
    val mc = mcFactory.forApp(opCtx)
    val appId = opCtx.mustGetAppId()
    val records = scanRecordRepo.findByIds(mc, appId, ids)  // 单次批量查询
    val map = records.associateBy { it.id }
    return CompletableFuture.completedFuture(ids.associateWith { map[it]!! })
}
```

**涉及文件**:
- `bff/graphql/customer/ai/AiFetcher.kt`（ScanRecordsDataLoader 类）

---

## Phase 3: 业务逻辑补全

### 3.1 partialUpdate 实现 unset 优先

**问题**: GraphQL schema 已定义 `unset: [XxxUnsetField!]`，但后端完全忽略。

**修复模式**:

```kotlin
// TodoRepository.partialUpdate
fun partialUpdate(mc: ModuleCtx, appId: UUID, input: UpdateTodoInput) {
    val set = input.set
    val unset = input.unset?.toSet() ?: emptySet()

    // 没有任何更新请求
    if (set == null && unset.isEmpty()) return

    mc.sql.createUpdate(Todo::class) {
        where(table.appId eq appId)
        where(table.id eq input.id)

        // unset 优先：如果字段同时出现在 set 和 unset，以 unset 为准
        if (TodoUnsetField.NOTE in unset) {
            set(table.note, null as String?)
        } else {
            set?.note?.let { set(table.note, it) }
        }

        if (TodoUnsetField.TITLE !in unset) {
            set?.title?.let { set(table.title, it) }
        }

        if (TodoUnsetField.DONE !in unset) {
            set?.done?.let { set(table.done, it) }
        }
    }.execute()
}
```

同样需要修复 `ScanRecordRepository.partialUpdate`。

**涉及文件**:
- `modules/demo/repo/TodoRepository.kt`
- `modules/demo/repo/TodoItemRepository.kt`
- `modules/ai/repo/ScanRecordRepository.kt`

### 3.2 ai 模块接入 FilterGroupResolver

**修复**:

```kotlin
// ScanRecordRepository.kt — 新增
companion object {
    private val tpl = CrudRepoTemplate(ScanRecord::class, appId = "appId")
    val FILTERABLE = listOf(
        ScanRecordProps.STATUS,
        ScanRecordProps.COLLECTED,
        ScanRecordProps.LANG,
        ScanRecordProps.CREATED_AT,
    )
}

fun findByFilter(mc: ModuleCtx, appId: UUID, filter: FilterGroup?, cursor: UUID?, limit: Int): Page<ScanRecord> {
    val rows = mc.sql.createQuery(ScanRecord::class) {
        where(table.appId eq appId)
        FilterGroupResolver.apply(this, filter, FILTERABLE)
        cursor?.let { where(table.id lt it) }
        orderBy(table.id.desc())
        select(table)
    }.limit(limit + 1).execute()
    return Page.of(rows, limit) { it.id.toString() }
}
```

```kotlin
// ScanAggHandler.kt — 替换 stub
fun findByFilter(sc: ModuleCtx, filter: FilterGroup?, cursor: String?, limit: Int?): Page<ScanRecord> {
    val appId = sc.op.mustGetAppId()
    val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
    val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    return scanRepo.findByFilter(sc, appId, filter, cursorUuid, effectiveLimit)
}
```

**涉及文件**:
- `modules/ai/repo/ScanRecordRepository.kt`
- `modules/ai/handler/ScanAggHandler.kt`

---

## Phase 4: Schema 重命名（可选，需客户端配合）

### 4.1 Payload → Result

所有 GraphQL schema 中 `type XxxPayload` 改为 `type XxxResult`。

**影响范围**: 6 个 .graphqls 文件、17 个 type、所有 DataFetcher 的 return type import。

**注意**: 这是 **API Breaking Change**。需要客户端同步更新。建议：
- 新增 `XxxResult` type 并 deprecate `XxxPayload`（过渡期）
- 或在客户端下一个版本强制升级后一刀切

### 4.2 Operation 前缀 query_/mutation_ → q_/m_

同为 **API Breaking Change**。

**建议**: 4.1 和 4.2 放同一个 breaking release 一起做，减少客户端更新次数。

---

## Phase 5: 清理

### 5.1 删除 BaseCrudRepositoryTest

```bash
rm core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudRepositoryTest.kt
```

### 5.2 ScanRecordRepo 强类型替换

`table.get<UUID>("appId")` → `table.appId`（需要 import KSP 生成的扩展属性）

### 5.3 authJwtService 改为 @Service（可选）

将 `AuthJwtService` 标注 `@Service`，构造器接收 `@Value` 参数，从 AuthConfig 中移除 `@Bean fun authJwtService()`。

---

## 执行顺序

```
Phase 1 (立即 — 运行时 bug)
  1.1 OperationContextHolder.set → 编译验证
  1.2 newScan AI 调用移出事务 → 编译验证

Phase 2 (短期 — 分层违规)
  2.1 AiFetcher 去除 repo 注入 → 编译验证
  2.2 WebhookController 去除 repo 注入 → 编译验证
  2.3 ScanRecordsDataLoader 批量查询 → 编译验证

Phase 3 (短期 — 业务逻辑)
  3.1 partialUpdate unset 优先 → 单元测试
  3.2 ai FilterGroup 接入 → 编译验证

Phase 4 (计划中 — Breaking Change，需客户端配合)
  4.1 Payload → Result
  4.2 q_/m_ 前缀

Phase 5 (随时 — 清理)
  5.1~5.3 逐个清理
```

---

## 验收标准

- [ ] `./gradlew :core-api:compileKotlin` 零错误
- [ ] DataLoader 和关联字段不抛 OperationContextHolder 异常
- [ ] AI 扫描请求不在事务内执行外部 HTTP
- [ ] DataFetcher 中无 repo/mcFactory 直接注入
- [ ] ScanRecordsDataLoader 单次批量查询
- [ ] partialUpdate 正确处理 unset（unset 优先于 set）
- [ ] ai 模块 findByFilter 真正解析 FilterGroup
