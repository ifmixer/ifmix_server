# Jimmer → jOOQ 迁移 + GraphQL 架构完善

> 日期: 2026-08-18
> 状态: 草案

## 目标

1. 将 ORM 从 Jimmer 迁移到 jOOQ（类型安全 SQL DSL + 自定义 Domain Model）
2. 扩展现有 `OperationContext`，加入 per-operation 字段（opName, isMutation, readCache）
3. Mutation 时 `readCache=false` + `readFromReplica=false`，防止脏读
4. DataLoader 关闭 identity cache（`caching=false`），只保留 batching

---

## 核心设计决策

### D1: 扩展 OperationContext（不新建类）

在现有 `OperationContext` 上增加字段，由 `OperationContextProvider.fromDfe()` 在 DataFetcher 层自动填充：

```kotlin
data class OperationContext(
    // --- per-request 字段（从 HTTP header 解析）---
    val appId: UUID? = null,
    val installId: UUID? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: UUID? = null,
    val clientIp: String? = null,
    // --- per-operation 字段（从 GraphQL execution 解析）---
    /** true = 允许读从库。mutation 时为 false。 */
    val readFromReplica: Boolean = false,
    /** GraphQL operation field name */
    val opName: String? = null,
    /** true = mutation */
    val isMutation: Boolean = false,
    /** 是否允许读缓存。mutation 时为 false，避免脏读。 */
    val readCache: Boolean = !isMutation,
)
```

**OperationContextProvider** 自动判断 mutation/query：

```kotlin
fun fromDfe(dfe: DgsDataFetchingEnvironment): OperationContext {
    val request = extractHttpRequest(dfe)
    val isMutation = dfe.executionStepInfo.parent?.type?.let {
        (it as? GraphQLObjectType)?.name == "Mutation"
    } ?: false
    val opName = dfe.field?.name

    return OperationContext(
        appId = ...,
        // ... 其他 header 字段 ...
        readFromReplica = !isMutation,
        opName = opName,
        isMutation = isMutation,
        // readCache 自动由 !isMutation 决定
    )
}
```

### D2: DataLoader — caching=false，只保留 batching

**问题**：同一 mutation document 中多个 mutation field 按顺序执行。如果 field 1 通过 DataLoader 查了数据并缓存，field 2 修改了该数据，field 3 回查时 DataLoader 返回旧缓存。

**解法**：DataLoader 关闭 identity cache，只保留 batching（攒一批再查，但不缓存结果）：

```kotlin
@DgsDataLoader(name = TodoItemsDataLoader.NAME, caching = false)
class TodoItemsDataLoader(private val repo: TodoRepository) : MappedBatchLoader<UUID, List<TodoItem>> {
    override fun load(todoIds: Set<UUID>): CompletionStage<Map<UUID, List<TodoItem>>> {
        val items = repo.findItemsByTodoIds(todoIds)
        val grouped = items.groupBy { it.todoId }
        return CompletableFuture.completedFuture(
            todoIds.associateWith { grouped[it] ?: emptyList() }
        )
    }
    companion object { const val NAME = "todoItems" }
}
```

**所有 DataLoader 统一 `caching = false`。** 安全 > 性能。同一 request 内重复 load 同一 key 的场景极少，而脏读的 debug 成本极高。

### D3: Domain Model — 自定义 data class

```kotlin
// model/Todo.kt
data class Todo(
    val id: UUID,
    val appId: UUID,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val title: String,
    val done: Boolean,
    val note: String? = null,
    val meta: Map<String, Any?>? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
) {
    fun isOwned(userId: UUID?, installId: UUID?): Boolean =
        this.userId == userId || this.installId == installId
}

data class TodoItem(
    val id: UUID,
    val appId: UUID,
    val todoId: UUID,
    val content: String,
    val done: Boolean,
    val note: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
```

**优势**：
- 可加业务方法
- Jackson 直接序列化（无 unloaded 炸弹）
- 可作为缓存值直接存 Redis
- DGS 按属性名匹配 GraphQL field

### D4: jOOQ codegen — 手动执行，生成文件提交

```bash
# 手动触发，连接本地 DB 生成 Table/Record 类
./gradlew :core-api:generateJooq
# 生成到 src/main/jooq/，提交到 git
```

不在 `compileKotlin` 中自动依赖。CI 跑 Flyway + codegen 验证一致性。

### D5: BaseCrudRepo 抽象

软删除可选（通过构造参数 `deletedAtField`）：

```kotlin
abstract class BaseCrudRepo<T : Any>(
    protected val dsl: DSLContext,
    protected val table: Table<*>,
    protected val idField: TableField<*, UUID>,
    protected val appIdField: TableField<*, UUID>,
    private val modelClass: Class<T>,
    /** null = 无软删除 */
    protected val deletedAtField: TableField<*, Instant?>? = null,
) {
    protected fun baseCondition(appId: UUID): Condition {
        var cond = appIdField.eq(appId)
        if (deletedAtField != null) cond = cond.and(deletedAtField.isNull)
        return cond
    }

    open fun findById(appId: UUID, id: UUID): T? =
        dsl.selectFrom(table)
            .where(baseCondition(appId).and(idField.eq(id)))
            .fetchOneInto(modelClass)

    open fun findByIds(appId: UUID, ids: Collection<UUID>): List<T> {
        if (ids.isEmpty()) return emptyList()
        return dsl.selectFrom(table)
            .where(baseCondition(appId).and(idField.`in`(ids)))
            .fetchInto(modelClass)
    }

    open fun findByCursor(appId: UUID, cursor: UUID?, limit: Int): List<T> =
        dsl.selectFrom(table)
            .where(baseCondition(appId))
            .apply { if (cursor != null) and(idField.lt(cursor)) }
            .orderBy(idField.desc())
            .limit(limit)
            .fetchInto(modelClass)

    open fun exists(appId: UUID, id: UUID): Boolean =
        dsl.fetchExists(table, baseCondition(appId).and(idField.eq(id)))

    open fun deleteById(appId: UUID, id: UUID): Boolean =
        if (deletedAtField != null) {
            dsl.update(table)
                .set(deletedAtField as TableField<*, Any?>, Instant.now() as Any?)
                .where(appIdField.eq(appId).and(idField.eq(id)))
                .execute() > 0
        } else {
            dsl.deleteFrom(table)
                .where(appIdField.eq(appId).and(idField.eq(id)))
                .execute() > 0
        }

    open fun deleteByIds(appId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return if (deletedAtField != null) {
            dsl.update(table)
                .set(deletedAtField as TableField<*, Any?>, Instant.now() as Any?)
                .where(appIdField.eq(appId).and(idField.`in`(ids)))
                .execute()
        } else {
            dsl.deleteFrom(table)
                .where(appIdField.eq(appId).and(idField.`in`(ids)))
                .execute()
        }
    }
}
```

### D6: 审计字段自动填充

jOOQ `RecordListener` 全局拦截 insert/update，自动填充 `created_at`/`updated_at`：

```kotlin
class AuditRecordListener : RecordListener {
    override fun insertStart(ctx: RecordContext) {
        val record = ctx.record()
        val now = Instant.now()
        record.fieldIndex("created_at")?.let { record.set(it, now) }
        record.fieldIndex("updated_at")?.let { record.set(it, now) }
    }
    override fun updateStart(ctx: RecordContext) {
        val record = ctx.record()
        record.fieldIndex("updated_at")?.let { record.set(it, Instant.now()) }
    }
}
```

### D7: UUIDv7 主键 — 调用方可传，不传则自动生成

```kotlin
fun insert(appId: UUID, id: UUID = UuidV7.generate(), block: CoreTodoRecord.() -> Unit)
```

### D8: 多集群路由（保持现有方案）

jOOQ `DSLContext` 绑 `AbstractRoutingDataSource`，现有 `ReadWriteRoutingDataSource` + `ClusterRegistry` 不变：

```kotlin
@Bean
fun dslContext(routingDataSource: DataSource): DSLContext =
    DSL.using(routingDataSource, SQLDialect.POSTGRES).apply {
        configuration().set(AuditRecordListener())
    }
```

---

## 缓存分层

```
┌──────────────────────────────────────────────────────────┐
│ DataLoader (per-request, batching only, caching=false)   │
│   → 关联字段 N+1 去重                                     │
├──────────────────────────────────────────────────────────┤
│ Redis CacheAside (跨 request, TTL 分钟级)                 │
│   → 热点数据缓存                                          │
│   → query 时 readCache=true → 走 cache                   │
│   → mutation 时 readCache=false → 跳过 cache              │
│   → 写后 evict                                           │
├──────────────────────────────────────────────────────────┤
│ DB (via jOOQ)                                            │
│   → query 时 readFromReplica=true → 读从库               │
│   → mutation 时 readFromReplica=false → 读主库            │
└──────────────────────────────────────────────────────────┘
```

---

## TodoRepository (jOOQ 版)

```kotlin
@Repository
class TodoRepository(dsl: DSLContext) : BaseCrudRepo<Todo>(
    dsl, CORE_TODO, CORE_TODO.ID, CORE_TODO.APP_ID, Todo::class.java,
    deletedAtField = CORE_TODO.DELETED_AT,
) {

    /** 插入 Todo。id 可由调用方指定，不传则自动生成。 */
    fun insert(appId: UUID, id: UUID = UuidV7.generate(), block: CoreTodoRecord.() -> Unit) {
        val record = dsl.newRecord(CORE_TODO).apply {
            this.id = id
            this.appId = appId
            block()
        }
        dsl.executeInsert(record)
    }

    /** partial update — 只 SET 有值的字段 */
    fun partialUpdate(appId: UUID, input: UpdateTodoInput) {
        dsl.update(CORE_TODO)
            .apply {
                input.set?.title?.let { set(CORE_TODO.TITLE, it) }
                input.set?.done?.let { set(CORE_TODO.DONE, it) }
                input.set?.note?.let { set(CORE_TODO.NOTE, it) }
                if (input.unset?.contains(TodoUnsetField.NOTE) == true) {
                    setNull(CORE_TODO.NOTE)
                }
            }
            .where(CORE_TODO.ID.eq(input.id).and(CORE_TODO.APP_ID.eq(appId)))
            .execute()
    }

    /** 批量 save items (insert + update 混合，用 ON CONFLICT) */
    fun saveItems(appId: UUID, items: List<TodoItem>) {
        if (items.isEmpty()) return
        val insert = dsl.insertInto(
            CORE_TODO_ITEM,
            CORE_TODO_ITEM.ID, CORE_TODO_ITEM.APP_ID, CORE_TODO_ITEM.TODO_ID,
            CORE_TODO_ITEM.CONTENT, CORE_TODO_ITEM.DONE, CORE_TODO_ITEM.NOTE,
        )
        items.forEach { item ->
            insert.values(
                item.id ?: UuidV7.generate(),
                appId, item.todoId, item.content, item.done, item.note,
            )
        }
        insert.execute()
    }

    /** 批量 partial update items */
    fun updateItems(appId: UUID, updates: List<UpdateTodoItemInput>) {
        if (updates.isEmpty()) return
        val batch = updates.map { u ->
            dsl.update(CORE_TODO_ITEM)
                .apply {
                    u.set?.content?.let { set(CORE_TODO_ITEM.CONTENT, it) }
                    u.set?.done?.let { set(CORE_TODO_ITEM.DONE, it) }
                    u.set?.note?.let { set(CORE_TODO_ITEM.NOTE, it) }
                    if (u.unset?.contains(TodoItemUnsetField.NOTE) == true) {
                        setNull(CORE_TODO_ITEM.NOTE)
                    }
                }
                .where(CORE_TODO_ITEM.ID.eq(u.id).and(CORE_TODO_ITEM.APP_ID.eq(appId)))
        }
        dsl.batch(batch).execute()
    }

    /** DataLoader 用 — 批量查 items by todoIds */
    fun findItemsByTodoIds(todoIds: Collection<UUID>): List<TodoItem> {
        if (todoIds.isEmpty()) return emptyList()
        return dsl.selectFrom(CORE_TODO_ITEM)
            .where(CORE_TODO_ITEM.TODO_ID.`in`(todoIds))
            .and(CORE_TODO_ITEM.DELETED_AT.isNull)
            .fetchInto(TodoItem::class.java)
    }

    /** 软删除 items */
    fun deleteItemsByIds(appId: UUID, ids: List<UUID>): Int {
        if (ids.isEmpty()) return 0
        return dsl.update(CORE_TODO_ITEM)
            .set(CORE_TODO_ITEM.DELETED_AT, Instant.now())
            .where(CORE_TODO_ITEM.ID.`in`(ids).and(CORE_TODO_ITEM.APP_ID.eq(appId)))
            .execute()
    }
}
```

---

## TodoService (jOOQ 版)

```kotlin
@Service
class TodoService(
    private val repo: TodoRepository,
    private val cache: CacheAside,
) {

    fun findById(ctx: OperationContext, id: UUID): Todo? {
        val appId = ctx.mustGetAppId()
        return if (ctx.readCache) {
            cache.getOrLoadNullable(cacheKey(appId, id), Todo::class.java) {
                repo.findById(appId, id)
            }
        } else {
            repo.findById(appId, id)
        }
    }

    fun findByCursor(ctx: OperationContext, input: TodoQueryInput): Page<Todo> {
        val appId = ctx.mustGetAppId()
        val limit = (input.limit ?: 20).coerceIn(1, 100)
        val cursorUuid = input.cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = repo.findByCursor(appId, cursorUuid, limit + 1)
        val hasMore = items.size > limit
        val resultItems = items.take(limit)
        return Page(resultItems, resultItems.lastOrNull()?.id?.toString(), hasMore)
    }

    fun findByIds(ctx: OperationContext, ids: List<UUID>): List<Todo> {
        if (ids.isEmpty()) return emptyList()
        val appId = ctx.mustGetAppId()
        if (!ctx.readCache) return repo.findByIds(appId, ids)
        return cache.loadMany(
            ids = ids.map { it.toString() },
            keyOf = { cacheKey(appId, UUID.fromString(it)) },
            type = Todo::class.java,
            idOf = { it.id.toString() },
        ) { missIds -> repo.findByIds(appId, missIds.map { UUID.fromString(it) }) }
    }

    @Transactional
    fun createTodo(ctx: OperationContext, input: CreateTodoInput): UUID {
        val appId = ctx.mustGetAppId()
        val id = UuidV7.generate()
        repo.insert(appId, id) {
            this.installId = ctx.installId
            this.userId = ctx.userId
            this.title = input.title
            this.done = input.done ?: false
            this.note = input.note
        }
        input.items?.takeIf { it.isNotEmpty() }?.let { items ->
            repo.saveItems(appId, items.map { i ->
                TodoItem(
                    id = UuidV7.generate(), appId = appId, todoId = id,
                    content = i.content, done = i.done ?: false, note = i.note,
                    createdAt = Instant.now(),
                )
            })
        }
        return id
    }

    @Transactional
    fun updateTodo(ctx: OperationContext, input: UpdateTodoInput): Boolean {
        val appId = ctx.mustGetAppId()
        if (!repo.exists(appId, input.id)) throw ApiError(ErrorCode.NOT_FOUND)
        repo.partialUpdate(appId, input)
        cache.evict(cacheKey(appId, input.id))
        return true
    }

    @Transactional
    fun updateTodoItems(ctx: OperationContext, input: UpdateTodoItemsMutationInput) {
        val appId = ctx.mustGetAppId()
        // Create
        input.create?.takeIf { it.isNotEmpty() }?.let { creates ->
            repo.saveItems(appId, creates.map { c ->
                TodoItem(
                    id = UuidV7.generate(), appId = appId, todoId = c.todoId,
                    content = c.content, done = c.done ?: false, note = c.note,
                    createdAt = Instant.now(),
                )
            })
        }
        // Update (partial, with set/unset)
        input.update?.takeIf { it.isNotEmpty() }?.let { updates ->
            repo.updateItems(appId, updates)
        }
        // Delete (soft)
        input.delete?.takeIf { it.isNotEmpty() }?.let { ids ->
            repo.deleteItemsByIds(appId, ids)
        }
    }

    @Transactional
    fun deleteTodo(ctx: OperationContext, id: UUID): Boolean {
        val appId = ctx.mustGetAppId()
        val ok = repo.deleteById(appId, id)
        if (ok) cache.evict(cacheKey(appId, id))
        return ok
    }

    @Transactional
    fun deleteTodosByIds(ctx: OperationContext, ids: List<UUID>): Int {
        val appId = ctx.mustGetAppId()
        if (ids.isEmpty()) return 0
        val count = repo.deleteByIds(appId, ids)
        cache.evictAll(ids.map { cacheKey(appId, it) })
        return count
    }

    private fun cacheKey(appId: UUID, id: UUID) = "todo:$appId:$id"
}
```

---

## TodoFetcher + DataLoader

```kotlin
@DgsComponent
class TodoFetcher(
    private val todoService: TodoService,
    private val ctxProvider: OperationContextProvider,
) {
    @DgsQuery(field = "query_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val ctx = ctxProvider.fromDfe(dfe)  // readCache=true, readFromReplica=true
        return todoService.findById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @DgsQuery(field = "query_findTodosByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: TodoQueryInput?): TodoPage {
        val ctx = ctxProvider.fromDfe(dfe)
        val page = todoService.findByCursor(ctx, input ?: TodoQueryInput())
        return TodoPage(items = page.items, nextCursor = page.nextCursor, hasMore = page.hasMore)
    }

    @DgsMutation(field = "mutation_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)  // readCache=false, readFromReplica=false
        val success = todoService.updateTodo(ctx, input)
        val todo = if (success && dfe.selectionSet.fields.any { it.name == "todo" }) {
            todoService.findById(ctx, input.id)  // readCache=false → 直读主库
        } else null
        return UpdateTodoPayload(success = success, todo = todo)
    }

    // ... 其他 mutation 类似 ...

    /** Todo.items 子字段解析 — 通过 DataLoader 批量加载 */
    @DgsData(parentType = "Todo", field = "items")
    fun todoItems(dfe: DgsDataFetchingEnvironment): CompletableFuture<List<TodoItem>> {
        val todo: Todo = dfe.getSource()!!
        val loader = dfe.getDataLoader<UUID, List<TodoItem>>(TodoItemsDataLoader.NAME)!!
        return loader.load(todo.id)
    }
}

/** DataLoader: batching only, no identity cache (防止 mutation 间脏读) */
@DgsDataLoader(name = TodoItemsDataLoader.NAME, caching = false)
class TodoItemsDataLoader(private val repo: TodoRepository) : MappedBatchLoader<UUID, List<TodoItem>> {
    override fun load(todoIds: Set<UUID>): CompletionStage<Map<UUID, List<TodoItem>>> {
        val items = repo.findItemsByTodoIds(todoIds)
        val grouped = items.groupBy { it.todoId }
        return CompletableFuture.completedFuture(
            todoIds.associateWith { grouped[it] ?: emptyList() }
        )
    }
    companion object { const val NAME = "todoItems" }
}
```

---

## Gradle 配置

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.netflix.dgs.codegen")      // DGS GraphQL codegen
    id("nu.studer.jooq")               // jOOQ codegen
}

dependencies {
    // 移除 Jimmer
    // implementation("org.babyfish.jimmer:jimmer-spring-boot-starter:...")
    // implementation("org.babyfish.jimmer:jimmer-sql-kotlin:...")
    // ksp("org.babyfish.jimmer:jimmer-ksp:...")

    // jOOQ
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    jooqGenerator("org.postgresql:postgresql")

    // DGS
    implementation(platform("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:12.0.1"))
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter")
    testImplementation("com.netflix.graphql.dgs:graphql-dgs-client")
}

// jOOQ codegen — 手动: ./gradlew :core-api:generateJooq
jooq {
    version.set("3.20.4")
    configurations {
        create("main") {
            jooqConfiguration.apply {
                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    url = "jdbc:postgresql://localhost:5432/ifmix_core_local"
                    user = "postgres"
                    password = "postgres"
                }
                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"
                    database.apply {
                        inputSchema = "public"
                        includes = "core_.*"
                        excludes = "flyway_.*"
                    }
                    target.apply {
                        packageName = "com.ifmix.api.core.jooq"
                        directory = "src/main/jooq"
                    }
                    generate.apply {
                        isKotlinNotNullPojoAttributes = true
                        isKotlinNotNullRecordAttributes = true
                    }
                }
            }
        }
    }
}

// 不让 compileKotlin 依赖 jOOQ codegen
tasks.named("compileKotlin") {
    dependsOn.remove(tasks.named("generateJooq"))
}

// DGS codegen
tasks.withType<com.netflix.graphql.dgs.codegen.gradle.GenerateJavaTask> {
    packageName = "com.ifmix.api.core.generated"
    language = "kotlin"
    generateClient = true
    generateDataTypes = true
    schemaPaths = mutableListOf(
        "${projectDir}/src/main/resources/schema/common",
        "${projectDir}/src/main/resources/schema/customer",
    )
    typeMapping = mutableMapOf(
        "UUID" to "java.util.UUID",
        "DateTime" to "java.time.Instant",
        "Long" to "kotlin.Long",
        "JSON" to "kotlin.Any",
        "Todo" to "com.ifmix.api.core.model.Todo",
        "TodoItem" to "com.ifmix.api.core.model.TodoItem",
        "ScanRecord" to "com.ifmix.api.core.model.ScanRecord",
        "ScanCollection" to "com.ifmix.api.core.model.ScanCollection",
        "ScanCollectionItem" to "com.ifmix.api.core.model.ScanCollectionItem",
    )
}
```

---

## 目录结构（最终态）

```
core-api/src/main/
├── kotlin/com/ifmix/api/core/
│   ├── CoreApplication.kt
│   ├── model/                              # Domain Model (data classes)
│   │   ├── Todo.kt
│   │   ├── TodoItem.kt
│   │   ├── ScanRecord.kt
│   │   ├── ScanCollection.kt
│   │   ├── ScanCollectionItem.kt
│   │   ├── Feedback.kt
│   │   ├── Subscription.kt
│   │   └── ...
│   ├── bff/
│   │   ├── graphql/
│   │   │   └── customer/
│   │   │       ├── TodoFetcher.kt
│   │   │       ├── TodoItemsDataLoader.kt
│   │   │       ├── ScanFetcher.kt
│   │   │       ├── ScanItemsDataLoader.kt
│   │   │       ├── AuthFetcher.kt
│   │   │       └── ...
│   │   ├── webhooks/WebhookController.kt
│   │   └── wellknown/JwksController.kt
│   ├── modules/
│   │   ├── todo/
│   │   │   ├── repo/TodoRepository.kt
│   │   │   └── service/TodoService.kt
│   │   ├── scan/
│   │   ├── auth/
│   │   └── ...
│   └── infra/
│       ├── graphql/
│       │   ├── OperationContextProvider.kt
│       │   ├── GraphQLExceptionHandler.kt
│       │   └── scalars/
│       ├── http/
│       │   ├── OperationContext.kt
│       │   ├── ApiError.kt
│       │   ├── ErrorCode.kt
│       │   └── ...
│       ├── jooq/
│       │   ├── JooqConfig.kt
│       │   ├── AuditRecordListener.kt
│       │   └── BaseCrudRepo.kt
│       ├── redis/
│       │   ├── CacheAside.kt
│       │   └── RedisCacheConfig.kt
│       └── ...
├── jooq/                                    # jOOQ codegen 输出（提交到 git）
│   └── com/ifmix/api/core/jooq/tables/...
├── resources/
│   ├── schema/
│   │   ├── common/common.graphqls
│   │   └── customer/*.graphqls
│   └── application.yml
└── (不再有 entity/ 和 dto/ 目录)
```

---

## 删除清单

| 删除 | 替换 |
|------|------|
| `entity/` 整个目录（Jimmer interfaces） | `model/` (data classes) |
| `src/main/dto/` 整个目录（Jimmer DTO files） | DGS codegen + Domain Model |
| `infra/jimmer/` 整个目录 | `infra/jooq/` |
| `infra/graphql/FetcherBuilder.kt` | DataLoader + 固定形状查询 |
| Jimmer 三个依赖 + KSP plugin | `spring-boot-starter-jooq` + jOOQ codegen |

**保留**:
- `infra/redis/` — CacheAside
- `infra/graphql/` — OperationContextProvider, ExceptionHandler, Scalars
- `infra/http/` — OperationContext, ApiError, ErrorCode
- `bff/graphql/` — Fetchers + DataLoaders
- `bff/webhooks/`, `bff/wellknown/`
- Flyway migrations（jOOQ codegen 读同一个 schema）
- DGS Framework + codegen

---

## 执行步骤

| # | 内容 | 风险 |
|---|------|------|
| 1 | 扩展 `OperationContext` 加 `opName`/`isMutation`/`readCache` | 低 |
| 2 | 添加 jOOQ 依赖 + codegen plugin，手动生成 Table/Record | 低 |
| 3 | 创建 `infra/jooq/` (JooqConfig, AuditRecordListener, BaseCrudRepo) | 低 |
| 4 | 创建 `model/Todo.kt`, `model/TodoItem.kt` | 低 |
| 5 | 重写 `TodoRepository` 用 jOOQ | 中 |
| 6 | 重写 `TodoService` (readCache 逻辑 + jOOQ repo) | 中 |
| 7 | 重写 `TodoFetcher` + `TodoItemsDataLoader` (caching=false) | 低 |
| 8 | 更新 DGS `typeMapping` → Domain Model | 低 |
| 9 | 验证 Todo 模块编译 + E2E | 中 |
| 10 | 迁移 Scan 模块 | 中 |
| 11 | 迁移 Auth 模块 | 中 |
| 12 | 迁移 Collection / Storage / Feedback / IAP | 中 |
| 13 | 删除 Jimmer 全部代码 + 依赖 + KSP | 低 |
| 14 | 重写 E2E 测试 | 中 |
| 15 | 更新 ARCHITECTURE.md + AGENTS.md | 低 |

---

## 迁移风险与回退

- **回退方案**: 独立 git branch，Jimmer 代码保留到全模块迁移完才删
- **并行验证**: jOOQ repo + Jimmer repo 可并存，service 层切换注入
- **DB schema 不变**: Flyway migrations 不改
- **GraphQL schema 不变**: `.graphqls` 不改，DGS codegen 不变
- **客户端无感**: API 契约不变

---

## 已确定的设计决策汇总

| # | 决策 | 理由 |
|---|------|------|
| 1 | jOOQ codegen 手动执行 | 不依赖运行中的 DB 来编译 |
| 2 | 软删除在 BaseCrudRepo 可选 | 有的实体需要有的不需要 |
| 3 | 审计字段 RecordListener 自动填充 | 不用每个 repo 手写 |
| 4 | id 由调用方传，不传则 UuidV7 兜底 | 测试/幂等场景方便指定 id |
| 5 | DataLoader `caching=false` | 防止 mutation document 内脏读 |
| 6 | OperationContext 扩展字段（不新建类） | 减少改动，保持单一入参 |
| 7 | mutation 时 readCache=false + readFromReplica=false | 写后读一致性 |
| 8 | Service 不接收 Fetcher 参数 | 形状固定才能缓存 |
| 9 | 关联字段走 DataLoader | Service 只管标量，关联按需加载 |
| 10 | DGS codegen 生成 input/payload/enum | Schema 单一源头，不手写 |
| 11 | Domain Model = 普通 data class | 可加方法，可序列化，无魔法 |
