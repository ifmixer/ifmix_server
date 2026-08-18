# 事务分层设计

> 日期: 2026-08-18

## 两层事务边界

```
DataFetcher (分布式事务 / 跨模块编排)
  → GlobalTxRunner.withTx — 开启全局 workflow 事务
  → 调 FacadeService（检测到全局事务 → 复用）

FacadeService (模块内事务)
  → 构建 SvcCtx + tx.withTx — 开启模块事务
  → 调 Internal Service

Internal Service (纯实现)
  → 接收 SvcCtx（已在事务中）
  → 不处理事务，调 repo
```

## FacadeService — 构建 SvcCtx + 开启模块事务

```kotlin
@Service
class TodoFacadeService(
    private val queries: TodoQueries,
    private val commands: TodoCommands,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext): SvcCtx {
        // 如果 DataFetcher 已开启全局事务，复用其 DSLContext
        val dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl
        val inTx = opCtx.inGlobalTx
        return SvcCtx(op = opCtx, dsl = dsl, inTransaction = inTx)
    }

    fun findById(opCtx: OperationContext, id: UUID): Todo? =
        queries.findById(svc(opCtx), id)

    fun createTodo(opCtx: OperationContext, input: CreateTodoInput): UUID =
        tx.withTx(svc(opCtx)) { sc -> commands.create(sc, input) }

    fun updateTodo(opCtx: OperationContext, input: UpdateTodoInput): Boolean =
        tx.withTx(svc(opCtx)) { sc -> commands.update(sc, input) }
}
```

## Internal Service — 只接收 SvcCtx，不开事务

```kotlin
@Component
internal class TodoCommands(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
) {
    fun create(sc: SvcCtx, input: CreateTodoInput): UUID {
        val appId = sc.mustGetAppId()
        val id = UuidV7.generate()
        repo.insert(sc, Todo(...))
        itemRepo.batchInsert(sc, items)
        return id
    }

    fun update(sc: SvcCtx, input: UpdateTodoInput): Boolean {
        if (!repo.exists(sc, sc.mustGetAppId(), input.id)) throw ApiError(ErrorCode.NOT_FOUND)
        repo.partialUpdate(sc, sc.mustGetAppId(), input)
        return true
    }
}

@Component
internal class TodoQueries(
    private val repo: TodoRepository,
    factory: CrudServiceOpsFactory,
) {
    private val ops = factory.create(Todo::class.java, "todo") { it.id }

    fun findById(sc: SvcCtx, id: UUID): Todo? = ops.findById(sc, id, repo::findById)
}
```

## GlobalTxRunner — DataFetcher 级全局事务

```kotlin
@Component
class GlobalTxRunner(private val workflowDsl: DSLContext) {

    /**
     * 开启全局事务。
     * FacadeService 检测到 opCtx.inGlobalTx=true → 复用 DSLContext，不嵌套。
     *
     * ponytail: 当前单库，globalTxDsl 和模块 dsl 相同。
     * 将来拆分后改为 saga/补偿。此函数是切换点。
     */
    fun <R> withTx(opCtx: OperationContext, body: (OperationContext) -> R): R {
        return workflowDsl.transactionResult { config ->
            val txOpCtx = opCtx.copy(globalTxDsl = config.dsl(), inGlobalTx = true)
            body(txOpCtx)
        }
    }
}
```

## DataFetcher 使用

```kotlin
@DgsComponent
class TodoFetcher(
    private val todoService: TodoFacadeService,
    private val ctxProvider: OperationContextProvider,
    private val globalTx: GlobalTxRunner,
) {
    // 简单场景 — 单模块，不需要 global tx
    @DgsMutation(field = "mutation_todo_createTodo")
    fun createTodo(dfe, input): CreateTodoPayload {
        val opCtx = ctxProvider.fromDfe(dfe)
        val id = todoService.createTodo(opCtx, input)
        ...
    }

    // 复杂场景 — 跨模块
    @DgsMutation(field = "mutation_scan_createScanAndCollect")
    fun createScanAndCollect(dfe, input): ... {
        val opCtx = ctxProvider.fromDfe(dfe)
        return globalTx.withTx(opCtx) { txOpCtx ->
            val scanId = scanService.createScan(txOpCtx, ...)
            collectionService.addItem(txOpCtx, ...)
            ...
        }
    }
}
```

## OperationContext 新增字段

```kotlin
data class OperationContext(
    val req: RequestContext,
    val opName: String?,
    val isMutation: Boolean,
    // 全局事务支持
    val globalTxDsl: DSLContext? = null,
    val inGlobalTx: Boolean = false,
)
```

## TxRunner（模块级）行为

```kotlin
// FacadeService 调 tx.withTx(svcCtx) 时：
// - svcCtx.inTransaction = false → 开新事务
// - svcCtx.inTransaction = true（已被全局事务覆盖）→ 跳过，直接执行
```

## 将来拆分

```kotlin
// 当前（单体）— GlobalTxRunner 真正开 DB 事务
globalTx.withTx(opCtx) { txOpCtx ->
    scanService.createScan(txOpCtx, ...)
    collectionService.addItem(txOpCtx, ...)
}

// 将来（拆分后）— GlobalTxRunner 改为 saga
globalTx.withSaga(opCtx) { saga ->
    val scanId = saga.step { scanClient.createScan(...) }
        .compensate { scanClient.deleteScan(scanId) }
    saga.step { collectionClient.addItem(scanId) }
        .compensate { collectionClient.removeItem(scanId) }
}
```

切换点在 `GlobalTxRunner`，FacadeService/Internal Service 代码不变。

## 目录

```
infra/jooq/
├── TxRunner.kt           # 模块内事务（SvcCtx 级）
└── GlobalTxRunner.kt     # 全局事务（OperationContext 级，DataFetcher 用）
```
