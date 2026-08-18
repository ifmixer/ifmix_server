# 剩余整理任务: Context 分层重构

> 日期: 2026-08-18

## 目标

建立三层 Context 结构，职责清晰分离：

```
RequestContext (per-request, HTTP 层)
  ↓ 被包含
OperationContext (per-operation, DataFetcher 层)
  ↓ 被包含
SvcCtx (per-service-call, Service 层)
```

## 三层 Context 设计

### RequestContext — per-request（从 HTTP header 解析）

```kotlin
data class RequestContext(
    val appId: UUID?,
    val installId: UUID?,
    val userId: UUID?,
    val lang: String?,
    val currency: String?,
    val country: String?,
    val clientPlatform: ClientPlatform?,
    val clientIp: String?,
)
```

一个 HTTP request 一个，多个 operation 共享。纯请求信息，无业务决策。

### OperationContext — per-operation（DataFetcher 层构建）

```kotlin
data class OperationContext(
    val req: RequestContext,       // 包含 request 信息
    val opName: String?,           // e.g. "mutation_todo_createTodo"
    val isMutation: Boolean,
) {
    // 便捷委托
    val appId get() = req.appId
    val userId get() = req.userId
    val installId get() = req.installId
    fun mustGetAppId() = req.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST)
    fun mustGetUserId() = req.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
    fun mustGetInstallId() = req.installId ?: throw ApiError(ErrorCode.INVALID_REQUEST)
}
```

由 `OperationContextProvider.fromDfe()` 构建。只含"这是什么操作"的信息，不含基础设施决策。

### SvcCtx — per-service-call（Service 层构建）

```kotlin
data class SvcCtx(
    val op: OperationContext,     // 包含 operation 信息
    val dsl: DSLContext,          // 当前集群的 DSLContext
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
    val readFromReplica: Boolean = !op.isMutation,  // 读写路由决策
    val readCache: Boolean = !op.isMutation,         // 缓存决策
) {
    // 便捷委托
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
    val readCache get() = op.readCache
    fun mustGetAppId() = op.mustGetAppId()
    fun mustGetUserId() = op.mustGetUserId()
    fun mustGetInstallId() = op.mustGetInstallId()
}
```

由 Service 自己构建（根据路由逻辑决定 dsl）。传给 repo 层。TxRunner 操作此层。

## 数据流

```
HTTP Request
  → OperationContextProvider.fromDfe(dfe)
    → 构建 RequestContext (from headers)
    → 构建 OperationContext (from execution info + RequestContext)
  → DataFetcher 拿到 OperationContext, 传给 Service

Service
  → 根据 opCtx.appId 等信息决定集群
  → 构建 SvcCtx(op = opCtx, dsl = clusterRouter.forApp(appId))
  → tx.withTx(svcCtx) { txCtx -> repo.xxx(txCtx, ...) }
  → CrudServiceOps 内部也用 svcCtx

Repo
  → 接收 SvcCtx, 用 svcCtx.dsl 执行 SQL
```

## TxRunner

```kotlin
@Component
class TxRunner {
    fun <R> withTx(svcCtx: SvcCtx, propagation: TxPropagation = REQUIRED, body: (SvcCtx) -> R): R {
        return when (propagation) {
            REQUIRED -> if (svcCtx.inTransaction) body(svcCtx) else newTx(svcCtx, body)
            REQUIRES_NEW -> newTx(svcCtx.copy(inTransaction = false), body)
            SUPPORTS -> body(svcCtx)
            NOT_SUPPORTED -> body(svcCtx.copy(inTransaction = false))
        }
    }

    private fun <R> newTx(svcCtx: SvcCtx, body: (SvcCtx) -> R): R =
        svcCtx.dsl.transactionResult { config ->
            body(svcCtx.copy(dsl = config.dsl(), inTransaction = true))
        }
}
```

## CrudServiceOps

```kotlin
class CrudServiceOps<T : Any>(
    ...,
    private val svcCtxProvider: (OperationContext) -> SvcCtx,  // service 提供路由逻辑
) {
    fun findById(opCtx: OperationContext, id: UUID, loader: (SvcCtx, UUID, UUID) -> T?): T? {
        val svcCtx = svcCtxProvider(opCtx)
        val appId = opCtx.mustGetAppId()
        if (!opCtx.readCache) return loader(svcCtx, appId, id)
        return cache.getOrLoadNullable(...) { loader(svcCtx, appId, id) }
    }
}
```

## SvcCtx 构建示例

```kotlin
// Service internal 类中
private fun svcCtx(opCtx: OperationContext) = SvcCtx(
    op = opCtx,
    dsl = SvcCtx.DEFAULT.dsl,  // 当前单集群; 将来: clusterRouter.forApp(opCtx.appId)
)
```

## Repo 方法签名

```kotlin
// 从接收 RepoContext 改为接收 SvcCtx
fun findById(ctx: SvcCtx, appId: UUID, id: UUID): Todo? =
    ctx.dsl.selectFrom(table)...

fun insert(ctx: SvcCtx, todo: Todo) =
    ctx.dsl.newRecord(table, todo).let { ctx.dsl.executeInsert(it) }
```

## 文件改动清单

| 文件 | 改动 |
|------|------|
| `infra/http/RequestContext.kt` | 拆分：RequestContext 只保留 HTTP 字段；OperationContext 包含 `req: RequestContext` + per-op 字段 |
| `infra/db/RepoContext.kt` | 重命名为 `SvcCtx.kt`，加 `op: OperationContext` 字段 |
| `infra/jooq/TxRunner.kt` | `withTx` 参数改为 `SvcCtx` |
| `infra/jooq/JooqConfig.kt` | `RepoContext.DEFAULT` → `SvcCtx.DEFAULT`（临时兼容） |
| `infra/service/CrudServiceOps.kt` | loader 签名从 `(RepoContext, UUID, UUID)` → `(SvcCtx, UUID, UUID)`；factory 加 `svcCtxProvider` |
| `infra/graphql/OperationContextProvider.kt` | 构建 RequestContext + OperationContext（不再构建 repoCtx/SvcCtx） |
| 所有 `modules/*/repo/*.kt` | 参数 `RepoContext` → `SvcCtx` |
| 所有 `modules/*/service/*.kt` | `ctx.repoCtx` → `svcCtx(ctx)`；withTx 参数改 |
| `bff/graphql/customer/storage/StorageFetcher.kt` | 调 repo 时传 `SvcCtx.DEFAULT` 或改走 service |
| DataLoader | `RepoContext.DEFAULT` → `SvcCtx.DEFAULT` |
| `infra/http/RequestContextExt.kt` | `mustGetAppId` 等移到 OperationContext 上 |

## 执行顺序

```
1. 创建新的 RequestContext + OperationContext + SvcCtx 定义
2. 改 TxRunner + CrudServiceOps + CrudRepoOps
3. 改 OperationContextProvider
4. 批量改 repo（参数名 RepoContext → SvcCtx）
5. 重构 service 为 FacadeService + internal 实现类
6. 改 Fetcher + DataLoader（注入 FacadeService）
7. 删除旧 RepoContext.kt
8. 编译通过
```

## Service 层重构: FacadeService + Internal 实现

### 设计

每个模块对外暴露一个 `XxxFacadeService`，DataFetcher 只注入 Facade。实际逻辑拆到 internal 实现类（按 query/command 分）。

```
modules/todo/
├── repo/
│   ├── TodoRepository.kt
│   └── TodoItemRepository.kt
├── service/
│   ├── TodoFacadeService.kt       # 对外唯一入口
│   ├── internal/
│   │   ├── TodoQueries.kt         # 查询实现
│   │   ├── TodoCommands.kt        # Todo 写操作实现
│   │   └── TodoItemCommands.kt    # TodoItem 写操作实现
```

### FacadeService 示例

```kotlin
@Service
class TodoFacadeService(
    private val queries: TodoQueries,
    private val commands: TodoCommands,
    private val itemCommands: TodoItemCommands,
) {
    // ===== Query =====
    fun findById(opCtx: OperationContext, id: UUID) = queries.findById(opCtx, id)
    fun findByCursor(opCtx: OperationContext, input: TodoQueryInput) = queries.findByCursor(opCtx, input)
    fun findByIds(opCtx: OperationContext, ids: List<UUID>) = queries.findByIds(opCtx, ids)

    // ===== Todo Mutation =====
    fun createTodo(opCtx: OperationContext, input: CreateTodoInput) = commands.create(opCtx, input)
    fun updateTodo(opCtx: OperationContext, input: UpdateTodoInput) = commands.update(opCtx, input)
    fun deleteTodo(opCtx: OperationContext, id: UUID) = commands.delete(opCtx, id)
    fun batchDeleteTodos(opCtx: OperationContext, ids: List<UUID>) = commands.batchDelete(opCtx, ids)

    // ===== TodoItem Mutation =====
    fun updateItems(opCtx: OperationContext, input: UpdateTodoItemsMutationInput) = itemCommands.update(opCtx, input)
    fun findItemsByTodoIds(opCtx: OperationContext, todoIds: Collection<UUID>) = queries.findItemsByTodoIds(opCtx, todoIds)
}
```

### Internal 实现类

```kotlin
@Component
internal class TodoQueries(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
    factory: CrudServiceOpsFactory,
) {
    private val ops = factory.create(Todo::class.java, "todo", { it.id }) { svcCtx(it) }

    private fun svcCtx(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = ...)

    fun findById(opCtx: OperationContext, id: UUID): Todo? = ops.findById(opCtx, id, repo::findById)
    fun findByCursor(opCtx: OperationContext, input: TodoQueryInput): Page<Todo> = ops.findByCursor(...)
    fun findByIds(opCtx: OperationContext, ids: List<UUID>): List<Todo> = ops.findByIds(...)
    fun findItemsByTodoIds(opCtx: OperationContext, todoIds: Collection<UUID>) = itemRepo.findByTodoIds(svcCtx(opCtx), todoIds)
}

@Component
internal class TodoCommands(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
    private val tx: TxRunner,
) {
    private fun svcCtx(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = ...)

    fun create(opCtx: OperationContext, input: CreateTodoInput): UUID {
        val sc = svcCtx(opCtx)
        val appId = opCtx.mustGetAppId()
        return tx.withTx(sc) { txCtx ->
            repo.insert(txCtx, Todo(...))
            // ...
            id
        }
    }

    fun update(opCtx: OperationContext, input: UpdateTodoInput): Boolean { ... }
    fun delete(opCtx: OperationContext, id: UUID): Boolean { ... }
    fun batchDelete(opCtx: OperationContext, ids: List<UUID>): Int { ... }
}

@Component
internal class TodoItemCommands(
    private val repo: TodoItemRepository,
    private val tx: TxRunner,
) {
    fun update(opCtx: OperationContext, input: UpdateTodoItemsMutationInput) { ... }
}
```

### DataFetcher 注入

```kotlin
@DgsComponent
class TodoFetcher(
    private val todoService: TodoFacadeService,  // 只注入 Facade
    private val ctxProvider: OperationContextProvider,
) {
    @DgsQuery(field = "query_todo_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val opCtx = ctxProvider.fromDfe(dfe)
        return todoService.findById(opCtx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }
}
```

### 各模块 FacadeService

| 模块 | FacadeService | Internal 实现类 |
|------|--------------|----------------|
| todo | `TodoFacadeService` | `TodoQueries`, `TodoCommands`, `TodoItemCommands` |
| scan | `ScanFacadeService` | `ScanQueries`, `ScanCommands` |
| collection | `CollectionFacadeService` | `CollectionQueries`, `CollectionCommands` |
| auth | `AuthFacadeService` | `AuthQueries`(me), `AuthCommands`(login/logout/refresh/exchange/delete) |
| iap | `IapFacadeService` | `IapCommands`(verify), `IapWebhookHandler`(notification) |
| feedback | `FeedbackFacadeService` | `FeedbackCommands`(submit) |
| storage | `StorageFacadeService` | `StorageCommands`(presign) |
| app | `AppConfigFacadeService` | `AppConfigQueries`, `AppConfigCommands` |

## 命名规范

| 变量名 | 类型 | 谁构建 |
|--------|------|--------|
| `reqCtx` | `RequestContext` | OperationContextProvider (from HTTP headers) |
| `opCtx` 或 `ctx` | `OperationContext` | OperationContextProvider (from DFE) |
| `svcCtx` 或 `sc` | `SvcCtx` | Service (from cluster routing) |
| `txCtx` | `SvcCtx` | TxRunner body 参数（事务内） |
