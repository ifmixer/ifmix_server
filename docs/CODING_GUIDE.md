# 编码指南

## Context 三层模型

```
RequestContext      HTTP 请求级    构造于: AuthInterceptor / Header 解析
    ↓
OperationContext    Operation 级   构造于: DataFetcher (ctxProvider.fromDfe)
    ↓
ModuleCtx           模块调用级     构造于: Facade (ModuleCtxFactory.forApp)
```

### RequestContext

```kotlin
data class RequestContext(
    val appId: UUID?, val installId: UUID?, val userId: UUID?,
    val lang: String?, val currency: String?, val country: String?,
    val clientPlatform: ClientPlatform?, val clientIp: String?,
)
```

### OperationContext

```kotlin
data class OperationContext(
    val req: RequestContext,
    val opName: String? = null,
    val isMutation: Boolean = false,
    val preferReader: Boolean = !isMutation,
    val globalTxSql: KSqlClient? = null,
    val inGlobalTx: Boolean = false,
)
```

### ModuleCtx

```kotlin
data class ModuleCtx(
    val op: OperationContext,
    val sql: KSqlClient,
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) {
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
}
```

参数名缩写：`mc`（ModuleCtx）

### ModuleCtxFactory

```kotlin
@Component
class ModuleCtxFactory(private val router: ClusterRouter) {
    fun forApp(opCtx: OperationContext): ModuleCtx { ... }

    private fun chooseSql(opCtx, pair): KSqlClient = when {
        opCtx.globalTxSql != null -> opCtx.globalTxSql  // 全局事务内，复用
        opCtx.preferReader -> pair.reader
        else -> pair.writer
    }
}
```

## 事务管理

### GlobalTxRunner（DataFetcher 层）

```kotlin
@DgsMutation(field = "m_demo_createTodo")
fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoResult {
    val ctx = ctxProvider.fromDfe(dfe)
    val todo = globalTx.withTx(ctx) { txCtx -> demoService.create(txCtx, input) }
    return CreateTodoResult(todo = todo)
}
```

**机制：** `withTx` 设置 `opCtx.globalTxSql = writer`，后续 `ModuleCtxFactory.chooseSql` 复用事务连接。

### TxRunner（模块级，预留）

支持传播行为：REQUIRED / REQUIRES_NEW / SUPPORTS / NOT_SUPPORTED

### 反模式

```kotlin
// ❌ 外部 IO 在事务内
globalTx.withTx(ctx) {
    val result = externalApi.call()
    repo.save(mc, entity)
}

// ✅ 先做 IO，再开事务
val result = externalApi.call()
globalTx.withTx(ctx) { txCtx -> facade.save(txCtx, entity) }
```

## 分层编码示例

### DataFetcher

```kotlin
@DgsComponent
class DemoFetcher(
    private val demoService: DemoFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: OperationContextProvider,
) {
    @DgsQuery(field = "q_demo_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val ctx = ctxProvider.fromDfe(dfe)
        return demoService.findById(ctx, id) ?: throw IllegalArgumentException("not found")
    }

    @DgsMutation(field = "m_demo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = globalTx.withTx(ctx) { txCtx -> demoService.create(txCtx, input) }
        return CreateTodoResult(todo = todo)
    }
}
```

### Facade

```kotlin
@Service
class DemoFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: TodoAggHandler,
) {
    fun findById(ctx: OperationContext, id: UUID): Todo? =
        handler.findById(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)

    fun create(ctx: OperationContext, input: CreateTodoInput): Todo =
        handler.create(mcFactory.forApp(ctx), input)
}
```

### Handler

```kotlin
@Component
class TodoAggHandler(
    private val todoRepo: TodoRepository,
) {
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID): Todo? =
        todoRepo.findById(mc, appId, id)

    fun create(mc: ModuleCtx, input: CreateTodoInput): Todo {
        val todo = Todo { ... }
        todoRepo.save(mc, todo)
        return todo
    }
}
```

### Repository

```kotlin
@Repository
class TodoRepository {
    companion object {
        private val tpl = AppCrudRepoTemplate(Todo::class)
        val FILTERABLE = listOf(TodoProps.TITLE, TodoProps.DONE, TodoProps.USER_ID)
        val SORTABLE = setOf("id", "createdAt", "updatedAt")
    }

    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>) = tpl.findByIds(mc, appId, ids)
    fun save(mc: ModuleCtx, entity: Todo) = tpl.save(mc, entity)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.deleteById(mc, appId, id)

    fun findByOptions(mc: ModuleCtx, appId: UUID, options: CommonFindOptions?) =
        tpl.findByOptions(mc, appId, options, FILTERABLE, SORTABLE)
}
```

### DataLoader（关联字段）

```kotlin
@DgsDataLoader(name = "todoItems", caching = false)
class TodoItemsDataLoader(
    private val demoFacade: DemoFacade,  // 通过 Facade，不直接注入 repo
) : MappedBatchLoader<UUID, List<TodoItem>> {
    override fun load(ids: Set<UUID>): CompletionStage<Map<UUID, List<TodoItem>>> {
        val opCtx = OperationContextHolder.current()
        val items = demoFacade.findItemsByTodoIds(opCtx, ids)
        return CompletableFuture.completedFuture(items.groupBy { it.todoId })
    }
}
```

## Entity 设计

### 基类

| 基类 | 字段 | 用于 |
|------|------|------|
| `BaseAppEntity` | id + appId + createdAt + updatedAt | 大部分 app 级实体 |
| `BaseEntity` | id + createdAt + updatedAt | 全局实体 |
| `UUIDProps` | @Id val id: UUID | 只需 ID 的组合 |

### JSONB 值对象

```kotlin
// entity/demo/TodoRecommend.kt
data class TodoRecommend(
    val sectionId: UUID,
    val sectionName: String,
    val viewCount: Int? = null,
    val recItems: List<RecItem>? = null,
) {
    data class RecItem(val recId: UUID, val title: String?, val priority: Int, ...)
}

// toDomain() 放同文件
fun TodoRecommendInput.toDomain() = TodoRecommend(...)
```

### CrudRepoTemplate API

| 模板 | 适用 | 签名特征 |
|------|------|----------|
| `CrudRepoTemplate<E>` | 全局实体 | `findById(ctx, id)` |
| `AppCrudRepoTemplate<E>` | App 级实体 | `findById(ctx, appId, id)` |

两者提供：
- Read: `findById` / `findByIds` / `exists` / `existsByIds` / `findByCursor` / `findByOptions`
- Write: `save`(→Boolean) / `batchSave`(→Int)
- Delete: `deleteById`(→Boolean) / `deleteByIds`(→Int)

`findByOptions` 支持 CommonFindOptions（filter + cursor + sortBy + sortDirection + limit）+ filterable/sortable 白名单 + 自定义 where lambda。

## 多集群路由

```kotlin
interface ClusterRouter {
    fun forApp(appId: UUID): ClusterSqlPair
}
```

Query → reader, Mutation → writer（通过 GlobalTxRunner）。
