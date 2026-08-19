# 架构重组计划：分层对齐 + 模块重命名

> 日期: 2026-08-19
> 参考: ifmix_server/docs/ARCHITECTURE.md

## 目标

将 ifmix-server-mongo 的代码组织对齐主项目的分层约定：

1. DataFetcher → ModuleFacade → EntityHandler → Repo 四层清晰分离
2. Context 分层：RequestContext → OperationContext → ModuleCtx → RepoCtx
3. 模块重命名对齐最终业务语义
4. GraphQL operation 命名对齐 `${query|mutation}_${module}_${action}` 规则

## 决策记录

| # | 决策 | 理由 |
|---|------|------|
| 1 | `TxRunner.withTx` 保留在 DataFetcher | 显式事务边界，ctx 注入 session/inTransaction |
| 2 | Context 四层分离 | 为未来微服务拆分预留，Repo 不混业务信息 |
| 3 | 全部 `@Service`/`@Component` | 删除 `*Config` 间接注册 |
| 4 | Service 不直接调 MongoTemplate | 必须走 Repo |
| 5 | CRUDOps 组合持有 | 不继承，Repo 内部 new |
| 6 | RepoCtx 含 session/inTransaction/readPreference | 事务内强制主库读，session 透传给 MongoTemplate |
| 7 | ModuleFacade 放模块顶层 | 对外唯一入口 |
| 8 | EntityHandler 放 `handler/` 目录 | 单 entity 业务实现 |
| 9 | DataFetcher 不做 cache | 缓存逻辑在 Handler 层，Facade 只编排 |
| 10 | Operation 命名带 entity | `findTodoById` 而非 `findById` |
| 11 | 不手写 DTO / Mapper | Entity 即 GraphQL output，ObjectId scalar 自动 coerce |
| 12 | storage 独立模块 | 预签名上传/下载是通用基础服务，不绑定 ai |
| 13 | Mutation 返回 XxxResult | `{success, entity}` 避免前端二次查询 |
| 14 | Mutation 回查 read from primary | 事务内 / 写后回查走主库 |
| 15 | 所有 Query 走 DataLoader | 批量加载，消除 N+1，不做单个查询 |
| 16 | Update Input: set/unset 分离 | set 传 null = 不更新；要清空用 unset 枚举 |

## Context 分层

```kotlin
// ===== per-request: HTTP 头解析，入口处立即转 ObjectId =====
data class RequestContext(
    val appId: ObjectId,
    val installId: ObjectId?,
    val userId: ObjectId?,
    val lang: String?,
    val currency: String?,
    val country: String?,
    val clientPlatform: ClientPlatform?,
    val operationId: String?,
    val bff: Bff,
    val permissions: Set<String>,
    val actorType: ActorType,
)

// ===== per-operation: DataFetcher 从 DFE 构建 =====
// DataFetcher 传给 Facade 的唯一参数
data class OperationContext(
    val req: RequestContext,
    val opName: String? = null,
    val isMutation: Boolean = false,
    val txSession: ClientSession? = null,     // 由 TxRunner 注入（跨模块事务）
    val inTransaction: Boolean = false,
) {
    val readCache get() = !isMutation
    val readPreference: ReadPreference get() =
        if (inTransaction) ReadPreference.primary() else
        if (isMutation) ReadPreference.primary() else ReadPreference.primaryPreferred()
    // 便捷委托
    val appId get() = req.appId
    val userId get() = req.userId
    val installId get() = req.installId
    val lang get() = req.lang
    val currency get() = req.currency
    val country get() = req.country
    val clientPlatform get() = req.clientPlatform
    fun mustGetUserId() = req.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
    fun mustGetInstallId() = req.installId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-install-id required")

    /** TxRunner 用：注入事务 session 返回新的 OperationContext */
    fun withTx(session: ClientSession) = copy(txSession = session, inTransaction = true)
}

// ===== module 内: Facade 构建，Handler 接收 =====
// Facade 知道自己的 clusterId，从 OperationContext 构建
data class ModuleCtx(
    val op: OperationContext,
    val cluster: Cluster = Cluster.DEFAULT,
    val readCache: Boolean = op.readCache,
    val txSession: ClientSession? = op.txSession,
    val inTransaction: Boolean = op.inTransaction,
) {
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
    val readPreference: ReadPreference get() =
        if (inTransaction) ReadPreference.primary() else op.readPreference
    fun mustGetUserId() = op.mustGetUserId()
    fun mustGetInstallId() = op.mustGetInstallId()

    companion object {
        /** Facade 用：从 OperationContext 构建，指定本模块的 cluster */
        fun from(opCtx: OperationContext, cluster: Cluster = Cluster.DEFAULT) = ModuleCtx(
            op = opCtx,
            cluster = cluster,
        )
    }
}

// ===== repo 层: 纯 DB 信息，Facade 构建传给 Handler/Repo =====
data class RepoCtx(
    val cluster: Cluster = Cluster.DEFAULT,
    val readPreference: ReadPreference = ReadPreference.primaryPreferred(),
    val txSession: ClientSession? = null,
    val inTransaction: Boolean = false,
) {
    companion object {
        /** Handler 用：从 ModuleCtx 构建 */
        fun from(mc: ModuleCtx) = RepoCtx(
            cluster = mc.cluster,
            readPreference = mc.readPreference,
            txSession = mc.txSession,
            inTransaction = mc.inTransaction,
        )
    }
}

// ===== 集群枚举 =====
enum class Cluster {
    DEFAULT,
    // 未来多集群: AUTH, ANALYTICS, ...
}
```

**数据流:**
```
DGS ContextBuilder → RequestContext
DataFetcher → OperationContext(req, isMutation, opName)
DataFetcher → txRunner.withTx(opCtx) { txOpCtx -> ... }  // 注入 txSession
Facade → 从 OperationContext 构建 ModuleCtx (知道自己的 clusterId)
Facade → 传 ModuleCtx 给 Handler
Handler → 接收 ModuleCtx，调 Repo 时 RepoCtx.from(mc)
Repo → 接收 RepoCtx + 业务参数 (CRUDOps 使用 txSession 执行操作)
```

## 模块重命名

| 旧名 | 新名 | 理由 |
|------|------|------|
| feedback | **cms** | 内容管理语义更广 |
| appconfig | **app** | 简洁 |
| iap | **payment** | 支付语义 |
| todo | **demo** | 示例模块 |
| antique + collection → 合并 | **ai** | AI 扫描 + 收藏统一 |
| storage | **storage** (独立) | 预签名上传/下载是通用基础服务 |

## 最终目录结构

```
core-api/src/main/kotlin/com/ifmix/api/core/
├── CoreApplication.kt
├── common/
│   ├── http/
│   │   ├── RequestContext.kt
│   │   ├── OperationContext.kt      ← 新增
│   │   ├── ModuleCtx.kt             ← 新增
│   │   ├── ApiError.kt
│   │   ├── ErrorCode.kt
│   │   ├── Envelope.kt
│   │   └── ...
│   ├── db/
│   │   ├── RepoCtx.kt               ← 新增 (从 RequestContext 脱离)
│   │   ├── CRUDOps.kt               ← 重命名自 CRUDRepository
│   │   ├── BaseEntity.kt
│   │   ├── Page.kt
│   │   ├── CursorQueryInput.kt
│   │   ├── Ownership.kt
│   │   └── MongoClusterResolver.kt
│   ├── auth/                         (JWT, AuthInterceptor, Hashing)
│   ├── redis/                        (CacheAside, RedisConfig)
│   ├── storage/                      (ObjectStorage, S3 — 基础设施实现)
│   ├── ratelimit/                    (RateLimiter, TierResolver)
│   ├── ai/                           (AgnesKeyStore, ScanRunner, ScanPrompt)
│   └── config/                       (WebConfig, JacksonConfig, MongoConfig)
├── bff/
│   ├── graphql/
│   │   ├── customer/                 (DataFetcher per module)
│   │   │   ├── DemoFetcher.kt
│   │   │   ├── AiFetcher.kt         (scan + collection)
│   │   │   ├── StorageFetcher.kt    (presign upload/download)
│   │   │   ├── CmsFetcher.kt
│   │   │   ├── PaymentFetcher.kt
│   │   │   └── AuthFetcher.kt       ← 新增 (从 bff/customer/ REST 迁入)
│   │   ├── admin/
│   │   │   ├── AdminAppFetcher.kt
│   │   │   └── AdminDemoFetcher.kt
│   │   └── common/                   (scalar, directive, context, dataloader, trusted)
│   ├── webhooks/                     (REST: Apple/Google IAP)
│   └── wellknown/                    (REST: JWKS)
└── modules/
    ├── demo/                         (原 todo)
    │   ├── DemoFacade.kt
    │   ├── handler/
    │   │   ├── TodoEntityHandler.kt
    │   │   └── TodoItemEntityHandler.kt
    │   ├── repo/
    │   │   ├── TodoRepository.kt
    │   │   └── TodoItemRepository.kt
    │   └── entity/
    │       ├── TodoEntity.kt
    │       └── TodoItemEntity.kt
    ├── ai/                           (原 antique + collection)
    │   ├── AiFacade.kt
    │   ├── handler/
    │   │   ├── ScanRecordEntityHandler.kt
    │   │   ├── CollectionEntityHandler.kt
    │   │   └── CollectionItemEntityHandler.kt
    │   ├── repo/
    │   │   ├── ScanRecordRepository.kt
    │   │   ├── CollectionRepository.kt
    │   │   └── CollectionItemRepository.kt
    │   └── entity/
    │       ├── ScanRecordEntity.kt
    │       ├── CollectionEntity.kt
    │       └── CollectionItemEntity.kt
    ├── storage/                      (独立模块)
    │   ├── StorageFacade.kt
    │   ├── handler/
    │   │   └── UploadRecordEntityHandler.kt
    │   ├── repo/
    │   │   └── UploadRecordRepository.kt
    │   └── entity/
    │       └── UploadRecordEntity.kt
    ├── auth/
    │   ├── AuthFacade.kt
    │   ├── handler/
    │   │   └── AuthEntityHandler.kt
    │   ├── repo/
    │   │   ├── AppUserRepo.kt
    │   │   ├── AuthProviderIdentityRepo.kt
    │   │   ├── AuthDeviceSecretRepo.kt
    │   │   ├── AppRefreshTokenRepo.kt
    │   │   ├── AuthTenantRepo.kt
    │   │   └── UserInstallBindingRepo.kt
    │   └── entity/
    │       ├── AppUserEntity.kt
    │       ├── AuthProviderIdentityEntity.kt
    │       ├── AuthDeviceSecretEntity.kt
    │       ├── AppRefreshTokenEntity.kt
    │       ├── AuthTenantEntity.kt
    │       └── UserInstallBindingEntity.kt
    ├── payment/                      (原 iap)
    │   ├── PaymentFacade.kt
    │   ├── handler/
    │   │   └── PaymentEntityHandler.kt
    │   ├── repo/
    │   │   └── SubscriptionRepo.kt
    │   └── entity/
    │       ├── SubscriptionEntity.kt
    │       └── StoreNotificationEntity.kt
    ├── cms/                          (原 feedback)
    │   ├── CmsFacade.kt
    │   ├── handler/
    │   │   └── FeedbackEntityHandler.kt
    │   ├── repo/
    │   │   └── FeedbackRepository.kt
    │   └── entity/
    │       └── FeedbackEntity.kt
    └── app/                          (原 appconfig)
        ├── AppFacade.kt
        ├── handler/
        │   └── AppConfigEntityHandler.kt
        ├── repo/
        │   └── AppConfigRepo.kt
        └── entity/
            ├── AppConfigEntity.kt
            └── AppInfoEntity.kt
```

## DTO 清理原则

**原则：不手写 DTO，不手写 Mapper。**

- DGS codegen 生成 **input** types（`CreateTodoInput` 等）
- Output 直接用 **Entity**（通过 `typeMapping` 映射，DGS 只序列化 schema 中声明的字段）
- `ObjectId` 字段通过自定义 scalar 自动 coerce 为 hex string
- Entity 的内部字段（`clientIp`, `deletedAt`, `appId` 等）schema 里不声明 = 不暴露
- 通用类型（`Page`, `OperationResult`）用共享 Kotlin class，不生成

**零转换层，零 Mapper 文件。**

### 删除清单

| 文件 | 理由 |
|------|------|
| `modules/antique/ScanDtos.kt` | 不手写 DTO |
| `modules/antique/ScanMapper.kt` | 不手写 Mapper |
| `modules/antique/ScanGraphQLMapper.kt` | Entity 直出，不需要 |
| `modules/appconfig/AppConfigView.kt` | 下游直接用 Entity |
| `modules/appconfig/AppConfigMapper.kt` | 不手写 Mapper |
| `modules/collection/CollectionDtos.kt` | 不手写 DTO，改用 DGS input |
| `modules/collection/CollectionGraphQLMapper.kt` | Entity 直出 |
| `modules/todo/mapper/TodoMapper.kt` | Entity 直出 |
| `modules/todo/mapper/` 目录 | 删除整个目录 |
| `modules/feedback/FeedbackDtos.kt` | 改用 DGS input |
| `modules/auth/AuthDtos.kt` | Auth 迁 GraphQL，改用 DGS codegen types |

### Auth 迁 GraphQL

Auth 模块从 REST 迁为 GraphQL mutation/query，`AuthDtos.kt` 删除，改用 DGS codegen 生成的 input/output types。
`CustomerAuthController.kt` 删除。

**仅保留 REST 的端点**（协议要求，无法迁 GraphQL）：
- `WebhookController.kt` — Apple/Google IAP 回调（外部推送，必须 REST POST）
- `JwksController.kt` — `GET /.well-known/jwks.json`（JWT 标准协议端点）

## GraphQL Operation 重命名

### 命名规则

```
${query|mutation}_${module}_${action含entity名}
```

action 需带 entity 名称，保证全局唯一可读。如 `findTodoById` 而非 `findById`。

### Customer Query

| 旧 | 新 |
|----|----|
| `todo_get(id)` | `query_demo_findTodoById(id)` |
| `todo_list(...)` | `query_demo_listTodos(...)` |
| `scan_get(id)` | `query_ai_findScanById(id)` |
| `scan_list(...)` | `query_ai_listScans(...)` |
| `collection_getDefault` | `query_ai_getDefaultCollection` |
| `collectionItem_list(...)` | `query_ai_listCollectionItems(...)` |
| `appConfig_getCurrent` | `query_app_getCurrentConfig` |

### Customer Mutation

| 旧 | 新 |
|----|----|
| `todo_create(input)` | `mutation_demo_createTodo(input)` |
| `todo_update(id, input)` | `mutation_demo_updateTodo(id, input)` |
| `todo_delete(id)` | `mutation_demo_deleteTodo(id)` |
| `todoItem_create(todoId, input)` | `mutation_demo_createTodoItem(todoId, input)` |
| `todoItem_update(id, input)` | `mutation_demo_updateTodoItem(id, input)` |
| `todoItem_delete(id)` | `mutation_demo_deleteTodoItem(id)` |
| `scan_create(input)` | `mutation_ai_createScan(input)` |
| `scan_update(id, collected)` | `mutation_ai_updateScan(id, collected)` |
| `scan_delete(id)` | `mutation_ai_deleteScan(id)` |
| `storage_presignUpload(input)` | `mutation_storage_presignUpload(input)` |
| `storage_presignDownload(input)` | `mutation_storage_presignDownload(input)` |
| `collectionItem_add(...)` | `mutation_ai_addCollectionItem(...)` |
| `collectionItem_remove(...)` | `mutation_ai_removeCollectionItems(...)` |
| `feedback_submit(input)` | `mutation_cms_submitFeedback(input)` |
| `iap_verifyPurchase(input)` | `mutation_payment_verifyPurchase(input)` |

### Admin Mutation

| 旧 | 新 |
|----|----|
| `todo_batchDelete(ids)` | `mutation_demo_batchDeleteTodos(ids)` |
| `todo_batchUpdate(patches)` | `mutation_demo_batchUpdateTodos(patches)` |
| `todoItem_batchDelete(ids)` | `mutation_demo_batchDeleteTodoItems(ids)` |
| `appConfig_createRevision(input)` | `mutation_app_createConfigRevision(input)` |
| `appConfig_toggleRevision(id, enabled)` | `mutation_app_toggleConfigRevision(id, enabled)` |

## CRUDOps 改造

```kotlin
/**
 * 通用 CRUD 操作集。纯工具类，非 Spring bean。
 * Repo 内部组合持有，按需委托。
 */
class CRUDOps<T : BaseEntity>(
    private val mongo: MongoTemplate,
    private val type: Class<T>,
) {
    // 探测能力
    private val appScoped = AppScoped::class.java.isAssignableFrom(type)
    private val softDeletable = SoftDeletable::class.java.isAssignableFrom(type)

    fun insertOne(ctx: RepoCtx, entity: T) { ... }
    fun findById(ctx: RepoCtx, appId: ObjectId, id: ObjectId): T? { ... }
    fun findByIds(ctx: RepoCtx, appId: ObjectId, ids: List<ObjectId>): List<T> { ... }
    fun findByCursor(ctx: RepoCtx, appId: ObjectId, input: CursorQueryInput): Page<T> { ... }
    fun updateById(ctx: RepoCtx, appId: ObjectId, id: ObjectId, set: Any?, unset: List<String>?): Boolean { ... }
    fun deleteById(ctx: RepoCtx, appId: ObjectId, id: ObjectId): Boolean { ... }
    fun insertMany(ctx: RepoCtx, entities: Collection<T>) { ... }
    // ...
}
```

**关键变化**：
- 参数从 `RequestContext` 改为 `RepoCtx` + 显式 `appId`
- appId 不再从 context 隐式取，而是由 Repo 显式传入

## Repo 模式

```kotlin
@Component
class TodoRepository(mongo: MongoTemplate) {
    private val ops = CRUDOps(mongo, TodoEntity::class.java)

    fun findById(ctx: RepoCtx, appId: ObjectId, id: ObjectId): TodoEntity? =
        ops.findById(ctx, appId, id)

    fun findByIds(ctx: RepoCtx, appId: ObjectId, ids: List<ObjectId>): List<TodoEntity> =
        ops.findByIds(ctx, appId, ids)

    fun insert(ctx: RepoCtx, entity: TodoEntity) =
        ops.insertOne(ctx, entity)

    fun findByCursor(ctx: RepoCtx, appId: ObjectId, input: CursorQueryInput): Page<TodoEntity> =
        ops.findByCursor(ctx, appId, input)

    /** 部分更新：接收 DGS 生成的 UpdateTodoInput，内部解析 set/unset */
    fun updateById(ctx: RepoCtx, appId: ObjectId, id: ObjectId, input: UpdateTodoInput): Boolean =
        ops.updateById(ctx, appId, id, input.set, input.unset?.map { it.name.lowercase() })

    fun deleteById(ctx: RepoCtx, appId: ObjectId, id: ObjectId): Boolean =
        ops.deleteById(ctx, appId, id)
}
```

Repo 也尽量用 input 对象作参数，避免过多散参。`updateById` 直接接收 DGS input，内部交给 CRUDOps 处理 set/unset 语义。

`CRUDOps` 内部使用 `RepoCtx.txSession`（如果非 null）执行操作，确保事务内操作绑定到同一个 session。

## DataLoader 约定

**所有 GraphQL query 都必须通过 DataLoader 批量加载，禁止在 resolver 中单个查询。**

```kotlin
// 注册 DataLoader
@DgsDataLoader(name = "todos")
class TodoDataLoader(private val demoFacade: DemoFacade) : MappedBatchLoader<ObjectId, TodoEntity> {
    override fun load(ids: Set<ObjectId>): CompletionStage<Map<ObjectId, TodoEntity>> {
        // 从 DGS context 取 OperationContext
        return CompletableFuture.supplyAsync {
            demoFacade.findTodosByIds(opCtx, ids.toList())
                .associateBy { it.id }
        }
    }
}

// Fetcher 中使用
@DgsQuery(field = "query_demo_findTodoById")
fun findTodoById(@InputArgument id: ObjectId, dfe: DgsDataFetchingEnvironment): CompletableFuture<TodoEntity?> {
    val dataLoader = dfe.getDataLoader<ObjectId, TodoEntity>("todos")
    return dataLoader.load(id)
}
```

### 设计要点

- **caching = false**（只 batching，不缓存）— 防 mutation 间脏读
- 所有关联字段（如 `Todo.items`、`CollectionItem.scanRecord`）走 DataLoader 批量
- 顶层 query（如 `findTodoById`）也走 DataLoader — 同一个 GraphQL 请求中多次查同 id 自动合并
- Facade 暴露 `findByIds(opCtx, ids)` 批量方法供 DataLoader 调用
- DataLoader per-request 生命周期（DGS 默认行为）

## Handler 模式

```kotlin
@Component
class TodoEntityHandler(
    private val repo: TodoRepository,
    private val cache: CacheAside,
) {
    private fun cacheKey(appId: ObjectId, id: ObjectId) = "todo:$appId:$id"

    fun findById(mc: ModuleCtx, id: ObjectId): TodoEntity? {
        if (!mc.readCache) return repo.findById(RepoCtx.from(mc), mc.appId, id)
        return cache.getOrLoadNullable(cacheKey(mc.appId, id), TodoEntity::class.java) {
            repo.findById(RepoCtx.from(mc), mc.appId, id)
        }
    }

    fun findByIds(mc: ModuleCtx, ids: List<ObjectId>): List<TodoEntity> =
        repo.findByIds(RepoCtx.from(mc), mc.appId, ids)

    fun create(mc: ModuleCtx, input: CreateTodoInput): ObjectId {
        val entity = TodoEntity().apply {
            appId = mc.appId
            title = input.title
            done = false
            meta = input.meta
            userId = mc.userId
            installId = mc.installId
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        repo.insert(RepoCtx.from(mc), entity)
        return entity.id
    }

    fun update(mc: ModuleCtx, id: ObjectId, input: UpdateTodoInput): Boolean {
        val result = repo.updateById(RepoCtx.from(mc), mc.appId, id, input)
        if (result) cache.evict(cacheKey(mc.appId, id))
        return result
    }

    fun delete(mc: ModuleCtx, id: ObjectId): Boolean {
        val result = repo.deleteById(RepoCtx.from(mc), mc.appId, id)
        if (result) cache.evict(cacheKey(mc.appId, id))
        return result
    }
}
```

Handler 职责：
- 接收 `ModuleCtx` + 业务参数（尽量用 input 对象）
- Cache 读写逻辑在此
- 调 Repo 时 `RepoCtx.from(mc)`

## Facade 模式

```kotlin
@Service
class DemoFacade(
    private val todoHandler: TodoEntityHandler,
    private val todoItemHandler: TodoItemEntityHandler,
) {
    // Facade: 薄转发 + 编排多个 Handler，不做 cache
    private fun mc(opCtx: OperationContext) = ModuleCtx.from(opCtx)

    // --- Query ---
    fun findTodoById(opCtx: OperationContext, id: ObjectId): TodoEntity? =
        todoHandler.findById(mc(opCtx), id)

    fun findTodosByIds(opCtx: OperationContext, ids: List<ObjectId>): List<TodoEntity> =
        todoHandler.findByIds(mc(opCtx), ids)

    // --- Mutation: 编排多 handler ---
    fun createTodo(opCtx: OperationContext, input: CreateTodoInput): ObjectId {
        val mc = mc(opCtx)
        val todoId = todoHandler.create(mc, input)
        input.items?.forEach { itemInput ->
            todoItemHandler.create(mc, todoId, itemInput)
        }
        return todoId
    }

    fun deleteTodo(opCtx: OperationContext, id: ObjectId): Boolean {
        val mc = mc(opCtx)
        todoItemHandler.deleteByTodoId(mc, id)
        return todoHandler.delete(mc, id)
    }
}
```

Facade 是薄转发层，只做：
- 构建 ModuleCtx
- 编排多个 Handler 的调用顺序
- **不做 cache**（cache 逻辑在 Handler 内部）

## DataFetcher 模式

```kotlin
@DgsComponent
class DemoFetcher(
    private val demoFacade: DemoFacade,
    private val txRunner: TxRunner,
) {
    @DgsQuery(field = "query_demo_findTodoById")
    fun findTodoById(@InputArgument id: ObjectId, dfe: DgsDataFetchingEnvironment): TodoEntity? {
        val opCtx = getOpCtx(dfe)
        return demoFacade.findTodoById(opCtx, id)
    }

    @DgsMutation(field = "mutation_demo_createTodo")
    fun createTodo(@InputArgument input: CreateTodoInput, dfe: DgsDataFetchingEnvironment): CreateTodoResult {
        val opCtx = getOpCtx(dfe)
        return txRunner.withTx(opCtx) { txOpCtx ->
            val id = demoFacade.createTodo(txOpCtx, input)
            // 只有客户端 select 了 todo 字段才回查
            val todo = if (dfe.selectionSet.contains("todo")) {
                demoFacade.findTodoById(txOpCtx, id)
            } else null
            CreateTodoResult(success = true, todo = todo)
        }
    }

    @DgsMutation(field = "mutation_demo_deleteTodo")
    fun deleteTodo(@InputArgument id: ObjectId, dfe: DgsDataFetchingEnvironment): OperationResult {
        val opCtx = getOpCtx(dfe)
        return txRunner.withTx(opCtx) { txOpCtx ->
            demoFacade.deleteTodo(txOpCtx, id)
            OperationResult(success = true)
        }
    }

    private fun getOpCtx(dfe: DgsDataFetchingEnvironment): OperationContext =
        DgsContext.getCustomContext(dfe)
}
```

### Mutation Result 约定

```graphql
type CreateTodoResult {
    success: Boolean!
    todo: Todo
}

# 只含 success 的用通用 OperationResult，不为每个 mutation 单独定义
type OperationResult {
    success: Boolean!
    modifiedCount: Int
}
```

- 需要回传 entity 的 mutation → 独立 `XxxResult` type（DGS codegen 生成）
- 只需 success / modifiedCount 的 mutation（delete, batch 等）→ 复用 `OperationResult`
- `OperationResult` typeMapping 到共享 Kotlin class，不生成

### Update Input — set/unset 防呆

```graphql
input UpdateTodoInput {
    id: ObjectId!
    set: UpdateTodoSetInput       # 有值 → SET field = value
    unset: [TodoUnsetField!]      # 列出 → SET field = NULL ($unset)
}

input UpdateTodoSetInput {
    title: String
    done: Boolean
    meta: JSON
}

enum TodoUnsetField {
    META
}
```

**语义：**
- `set` 中的字段：有值则 `$set`，`null` / 未传 = 不动
- `unset` 列出的枚举：执行 `$unset`（清空该字段）
- 两者都没出现 → 不动
- 冲突（同一字段同时出现在 set 和 unset）→ unset 优先

每个 Entity 的可 unset 字段用独立枚举声明，编译期类型安全。

### TxRunner 改造

`TxRunner` 启动 `ClientSession`，注入到 `OperationContext` 返回给 DataFetcher：

```kotlin
@Component
class TxRunner(private val mongoClient: MongoClient) {

    /** 跨模块事务：DataFetcher 层调用，txSession 注入到 OperationContext */
    fun <R> withTx(opCtx: OperationContext, body: (OperationContext) -> R): R {
        mongoClient.startSession().use { session ->
            return session.withTransaction {
                body(opCtx.withTx(session))
            }
        }
    }
}
```

DataFetcher 把 `txOpCtx`（含 txSession）传给 Facade，Facade 内部构建 `ModuleCtx` 时自动继承 txSession。

## 实施顺序

### Phase 1: 基础设施 (不改业务逻辑)

1. 新增 `OperationContext.kt`
2. 新增 `ModuleCtx.kt`（含 session/inTransaction）
3. 新增 `RepoCtx.kt`（含 session/inTransaction/readPreference）
4. `CRUDRepository` → `CRUDOps`（重命名 + 参数改 `RepoCtx` + 显式 `appId: ObjectId`）
5. 删除 `CRUDService`（透传层，逻辑上移）
6. 改造 `TxRunner`：启动 ClientSession + `opCtx.withTx(session)` 注入到 OperationContext
7. 新增 `ObjectIdScalar` + 注册
8. DGS ContextBuilder 改为构建 `OperationContext`（header 解析时立即转 ObjectId）
9. 更新 `typeMapping`：output types 映射到 Entity，通用类型映射到共享 class

### Phase 2: 模块重组 (逐个模块，每个独立可编译)

顺序：cms → app → storage → demo → payment → ai → auth

每个模块的步骤：
1. 创建新目录 + Facade + Handler
2. 迁移业务逻辑（删手写 DTO，用 DGS input + Entity 直出）
3. 更新 DataFetcher（注入 Facade + TxRunner，mutation 用 withTx）
4. 更新 GraphQL schema（operation 重命名 + set/unset input + Result types）
5. 更新 persisted queries JSON
6. 删除旧文件（Config, 旧 Service, DTO, Mapper）
7. 编译验证

### Phase 3: 收尾

1. 删除所有旧 `*Config.kt` 文件
2. 删除 `common/service/CRUDService.kt`
3. 删除 Konvert 依赖 + `META-INF/services/` TypeConverter 注册文件
4. 更新 `common/db/MongoClusterResolver` 使用方式（Repo 注入 MongoTemplate 由 Spring 自动解析）
5. 更新测试
6. 编写 ARCHITECTURE.md

## 删除清单

| 文件 | 理由 |
|------|------|
| `modules/todo/TodoConfig.kt` | 改 @Service |
| `modules/todo/TodoService.kt` | → DemoFacade + TodoEntityHandler |
| `modules/todo/service/TodoItemService.kt` | → TodoItemEntityHandler |
| `modules/todo/mapper/TodoMapper.kt` | Entity 直出，不需要 |
| `modules/antique/AntiqueConfig.kt` | 改 @Service |
| `modules/antique/AntiqueService.kt` | → AiFacade + ScanRecordEntityHandler |
| `modules/antique/ScanDtos.kt` | 删 DTO，用 DGS input + Entity |
| `modules/antique/ScanMapper.kt` | Entity 直出，不需要 |
| `modules/antique/ScanGraphQLMapper.kt` | Entity 直出，不需要 |
| `modules/collection/CollectionConfig.kt` | 改 @Service |
| `modules/collection/CollectionService.kt` | → AiFacade + CollectionEntityHandler |
| `modules/collection/CollectionDtos.kt` | 删 DTO |
| `modules/collection/CollectionGraphQLMapper.kt` | Entity 直出，不需要 |
| `modules/feedback/FeedbackConfig.kt` | 改 @Service |
| `modules/feedback/FeedbackService.kt` | → CmsFacade + FeedbackEntityHandler |
| `modules/feedback/FeedbackDtos.kt` | 删 DTO，用 DGS input |
| `modules/appconfig/` (整个目录) | 迁移到 `modules/app/` |
| `modules/appconfig/AppConfigView.kt` | 下游直接用 Entity |
| `modules/appconfig/AppConfigMapper.kt` | View 删了这也删 |
| `modules/iap/IapConfig.kt` | 改 @Service |
| `modules/iap/IapService.kt` | → PaymentFacade + PaymentEntityHandler |
| `modules/auth/AuthConfig.kt` | 改 @Service (JwtDecoder bean 移到 common/auth/) |
| `modules/auth/AuthService.kt` | → AuthFacade + AuthEntityHandler |
| `modules/auth/AuthDtos.kt` | Auth 迁 GraphQL，改用 DGS codegen types |
| `bff/customer/CustomerAuthController.kt` | Auth 迁 GraphQL，REST controller 删除 |
| `common/service/CRUDService.kt` | 层级删除，逻辑上移 |
| `graphql/customer/CustomerTodoFetcher.kt` | → bff/graphql/customer/DemoFetcher.kt |
| `graphql/customer/CustomerScanFetcher.kt` | → bff/graphql/customer/AiFetcher.kt + StorageFetcher.kt |
| `graphql/customer/CustomerCollectionFetcher.kt` | → bff/graphql/customer/AiFetcher.kt |
| `graphql/customer/CustomerFeedbackFetcher.kt` | → bff/graphql/customer/CmsFetcher.kt |
| `graphql/customer/CustomerIapFetcher.kt` | → bff/graphql/customer/PaymentFetcher.kt |
| `graphql/admin/AdminAppConfigFetcher.kt` | → bff/graphql/admin/AdminAppFetcher.kt |
| `graphql/admin/AdminTodoFetcher.kt` | → bff/graphql/admin/AdminDemoFetcher.kt |

## 注意事项

- MongoDB 事务需副本集（本地开发需 `rs.initiate()`）
- `TxRunner.withTx` 手动管理 `ClientSession`，不用 Spring `@Transactional`
- `CRUDOps` 不是 bean，在 Repo 构造函数中 `new` 出来
- `MongoClusterResolver` 暂保留，未来多集群时 Repo 可通过它获取不同 MongoTemplate
- auth 模块的 `ProviderVerifier` 等基础设施 bean 保留 `@Bean` 注册（需 `@Value` 注入），放 `common/auth/` 或单独一个 `AuthInfraConfig.kt`
- DataFetcher 中 query 走 DataLoader；mutation 回查 entity 仅在客户端 select 时触发

## ObjectId 统一 + GraphQL scalar

### 原则

所有 ID 类字段（主键、外键）统一使用 `org.bson.types.ObjectId` 类型。GraphQL schema 声明为自定义 scalar `ObjectId`，DGS 自动 coerce 为 24 位 hex string。

### GraphQL Schema

```graphql
scalar ObjectId

type Todo {
    id: ObjectId!
    title: String!
    done: Boolean!
    meta: JSON
    items: [TodoItem!]!
    userId: ObjectId
    installId: ObjectId
    createdAt: DateTime!
    updatedAt: DateTime!
}
```

### ObjectId Scalar 实现

```kotlin
class ObjectIdScalar : Coercing<ObjectId, String> {
    override fun serialize(dataFetcherResult: Any): String = when (dataFetcherResult) {
        is ObjectId -> dataFetcherResult.toHexString()
        is String -> dataFetcherResult
        else -> throw CoercingSerializeException("Expected ObjectId")
    }
    override fun parseValue(input: Any): ObjectId = when (input) {
        is String -> ObjectId(input)
        else -> throw CoercingParseValueException("Expected String")
    }
    override fun parseLiteral(input: Any): ObjectId = when (input) {
        is StringValue -> ObjectId(input.value)
        else -> throw CoercingParseLiteralException("Expected StringValue")
    }
}
```

### DGS typeMapping

```kotlin
typeMapping = mutableMapOf(
    "DateTime" to "java.time.Instant",
    "JSON" to "Map<String, Any?>",
    "Long" to "kotlin.Long",
    "ObjectId" to "org.bson.types.ObjectId",
    // output types → Entity class（不生成 DGS data class）
    "Todo" to "com.ifmix.api.core.modules.demo.entity.TodoEntity",
    "TodoItem" to "com.ifmix.api.core.modules.demo.entity.TodoItemEntity",
    "ScanRecord" to "com.ifmix.api.core.modules.ai.entity.ScanRecordEntity",
    "Collection" to "com.ifmix.api.core.modules.ai.entity.CollectionEntity",
    "CollectionItemType" to "com.ifmix.api.core.modules.ai.entity.CollectionItemEntity",
    "AppConfigRevision" to "com.ifmix.api.core.modules.app.entity.AppConfigEntity",
    "VerifyPurchaseResult" to "...",  // payment 需单独处理（非 entity 直出）
)
```

DGS codegen **只生成 input types**（`CreateTodoInput` 等），output types 全映射到 Entity。

### 通用类型统一映射

GraphQL 不支持泛型，schema 里每个模块有独立的 Connection type（`TodoConnection`, `ScanConnection` 等），但结构完全相同。通过 typeMapping 统一映射到同一个 Kotlin class，避免生成和转换：

```kotlin
typeMapping = mutableMapOf(
    // ...scalars & entities...

    // 所有 Connection type → 通用 Page
    "TodoConnection" to "com.ifmix.api.core.common.db.Page",
    "ScanConnection" to "com.ifmix.api.core.common.db.Page",
    "CollectionItemConnection" to "com.ifmix.api.core.common.db.Page",

    // 通用操作结果
    "OperationResult" to "com.ifmix.api.core.common.http.OperationResult",
)
```

对应的 Kotlin 类（已有 / 微调）：

```kotlin
// common/db/Page.kt — 字段名必须跟 schema 一致
data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

// common/http/OperationResult.kt
data class OperationResult(
    val success: Boolean,
    val modifiedCount: Int? = null,
)
```

DataFetcher 直接返回 `Page<TodoEntity>`，DGS 按 schema 字段名序列化，零转换。

### Entity 字段改 ObjectId

所有 ID 类 String 字段改为 `ObjectId?`：

| Entity | 改动字段 |
|--------|---------|
| TodoEntity | `userId: ObjectId?`, `installId: ObjectId?` |
| TodoItemEntity | `todoId: ObjectId` |
| ScanRecordEntity | `userId: ObjectId?`, `installId: ObjectId?` |
| CollectionEntity | `userId: ObjectId?`, `installId: ObjectId?` |
| CollectionItemEntity | `collectionId: ObjectId?` |
| FeedbackEntity | `userId: ObjectId?`, `installId: ObjectId?`, `scanRecordId: ObjectId?` |
| UploadRecordEntity | `userId: ObjectId?`, `installId: ObjectId?` |
| AppUserEntity | `authIdentityId: ObjectId?` |
| AuthIdentityEntity | `authTenantId: ObjectId?` |
| AuthProviderIdentityEntity | `authTenantId: ObjectId?`, `authIdentityId: ObjectId?`, `loginInstallId: ObjectId?` |
| AuthDeviceSecretEntity | `authTenantId: ObjectId?`, `authIdentityId: ObjectId?`, `loginInstallId: ObjectId?` |
| AppRefreshTokenEntity | `appUserId: ObjectId?`, `deviceSecretId: ObjectId?`, `loginInstallId: ObjectId?` |
| UserInstallBindingEntity | `userId: ObjectId?`, `installId: ObjectId?` |
| AppConfigEntity | `authTenantId: ObjectId?` |

### Context 层也用 ObjectId

`RequestContext`/`OperationContext`/`ModuleCtx` 中的 `appId`/`userId`/`installId` 全部用 `ObjectId` 类型。在 DGS ContextBuilder（入口）解析 HTTP header 时立即转换：

```kotlin
// DgsCustomContextBuilderImpl.build()
val appId = ObjectId(request.getHeader("x-app-id") ?: throw ...)
val installId = request.getHeader("x-install-id")?.let { ObjectId(it) }
val userId = request.getHeader("X-User-Id")?.let { ObjectId(it) }
```

下游全链路使用 `ObjectId`，不再有 String↔ObjectId 的来回转换。

### 删除 Konvert 依赖

Entity 即 GraphQL output，不需要任何 mapper/converter 框架。删除：
- `build.gradle.kts` 中 Konvert 相关依赖（`konvert-api`, `konvert`, `konvert-converter-api`, `konvert-converter`, `symbol-processing-api`, `kotlinpoet-jvm`）
- `META-INF/services/io.mcarle.konvert.converter.api.TypeConverter` 文件
- KSP 插件（如果仅 Konvert 使用）
