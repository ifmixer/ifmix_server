# 编码指南

## Context 三层模型

```
RequestContext      HTTP 请求级    构造于: AuthInterceptor / Header 解析
    ↓
OperationContext    Operation 级   构造于: DataFetcher (ctxProvider.fromDfe)
    ↓
ModuleCtx           模块调用级     构造于: Facade (ModuleCtxFactory.forProject)
```

### RequestContext

从 HTTP 请求头解析的纯请求信息。

```kotlin
data class RequestContext(
    val projectId: UUID?,
    val customerId: UUID?,   // 主体归一：actorType==customer 时 = actorId；null = 匿名/未认证
    val actorType: Int?,     // 10=customer / 20=manager
    val anonymous: Boolean,  // token ano claim
    val locale: String?, val currency: String?, val country: String?,
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
    val projectId get() = op.projectId
    val customerId get() = op.customerId
    val actorType get() = op.actorType
    val anonymous get() = op.anonymous
}
```

参数名缩写：`mc`（ModuleCtx）

### ModuleCtxFactory

```kotlin
@Component
class ModuleCtxFactory(private val router: ClusterRouter) {
    fun forProject(opCtx: OperationContext): ModuleCtx { ... }

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
        handler.findById(mcFactory.forProject(ctx), ctx.mustGetProjectId(), id)

    fun create(ctx: OperationContext, input: CreateTodoInput): Todo =
        handler.create(mcFactory.forProject(ctx), input)
}
```

### Handler

```kotlin
@Component
class TodoAggHandler(
    private val todoRepo: TodoRepository,
) {
    fun findById(mc: ModuleCtx, projectId: UUID, id: UUID): Todo? =
        todoRepo.findById(mc, projectId, id)

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
        private val tpl = ProjectCrudRepoTemplate(Todo::class)
        val FILTERABLE = listOf(TodoProps.TITLE, TodoProps.DONE, TodoProps.CUSTOMER_ID)
        val SORTABLE = setOf("id", "createdAt", "updatedAt")
    }

    fun findById(mc: ModuleCtx, projectId: UUID, id: UUID) = tpl.findById(mc, projectId, id)
    fun findByIds(mc: ModuleCtx, projectId: UUID, ids: Collection<UUID>) = tpl.findByIds(mc, projectId, ids)
    fun save(mc: ModuleCtx, entity: Todo) = tpl.save(mc, entity)
    fun deleteById(mc: ModuleCtx, projectId: UUID, id: UUID) = tpl.deleteById(mc, projectId, id)

    fun findByOptions(mc: ModuleCtx, projectId: UUID, options: CommonFindOptions?) =
        tpl.findByOptions(mc, projectId, options, FILTERABLE, SORTABLE)
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
| `BaseProjectEntity` | id + projectId + createdAt + updatedAt | 大部分 project 级实体 |
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
| `ProjectCrudRepoTemplate<E>` | Project 级实体 | `findById(ctx, projectId, id)` |

两者提供：
- Read: `findById` / `findByIds` / `exists` / `existsByIds` / `findByCursor` / `findByOptions`
- Write: `save`(→Boolean) / `batchSave`(→Int)
- Delete: `deleteById`(→Boolean) / `deleteByIds`(→Int)

`findByOptions` 支持 CommonFindOptions（filter + cursor + sortBy + sortDirection + limit）+ filterable/sortable 白名单 + 自定义 where lambda。

## 多集群路由

```kotlin
interface ClusterRouter {
    fun forProject(projectId: UUID): ClusterSqlPair
}
```

Query → reader, Mutation → writer（通过 GlobalTxRunner）。

## 日志与异常

异常日志**集中在两个异常入口**记录，业务代码抛 `ApiError` 时无需各自打 log：

- GraphQL 路径：`GraphQLExceptionHandler`（DGS `DataFetcherExceptionResolver`）
- REST/Webhook 路径：`GlobalExceptionHandler`（`@RestControllerAdvice`）

分级规则（按 `ErrorCode.status`）：

| 情况 | 级别 | 说明 |
|------|------|------|
| 5xx（INTERNAL / AI_UNAVAILABLE） | `error`（带 stack） | 服务端故障 |
| RATE_LIMITED / QUOTA_EXCEEDED / AUTH_PROVIDER_FAILED | `warn` | 限流命中、第三方验证失败 |
| 其余 4xx（400/401/403/404/TOKEN_EXPIRED…） | `debug` | 正常客户端拒绝，避免刷 warn |
| 非 `ApiError` 未预期异常 | `error`（带 stack） | 视为程序 bug；GraphQL 侧记录后返回 null 走默认处理 |

其他约定：

- **静默降级点必须打 log**：吞掉外部故障 / 兜底返回 null 的分支（如 Redis `INCR` 返回 null 降级放行、webhook 载荷解析失败）记 `warn`，便于排查。
- **不要给正常路径打 log**：合法默认值（`?: false`）、用户输入校验失败（无效 UUID/日期/token）属正常流程，打 log 只是噪音。
- logger 声明：`private val log = LoggerFactory.getLogger(X::class.java)`；占位符 `log.warn("... {}", arg)`，只有 `error` 带异常对象打 stack。
