# Jimmer → MyBatis Dynamic SQL 迁移计划

> 创建时间: 2026-08-20
> 状态: 待执行
> 目标: 用 MyBatis Dynamic SQL 替换 Jimmer，手写全部代码，零代码生成器

---

## 设计决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | Entity 用 data class | 零魔法、可 copy、可调试 |
| 2 | 不用代码生成器 | 手写 DynamicSqlSupport + Mapper |
| 3 | 保留 CrudRepoTemplate | 减少样板，通用 CRUD 委托 |
| 4 | 保留 ModuleCtx 显式传递 | 不依赖 Spring ThreadLocal 事务传播 |
| 5 | ModuleCtx 携带 SqlSession | 显式路由读写分离 |
| 6 | Template 持有 mapper class + 函数引用 | 每次从 mc.session 动态获取 mapper 实例 |
| 7 | JSONB 用泛型 TypeHandler | 基类在 infra，每个领域模型一行子类 |
| 8 | insert 和 update 分离 | 不做隐式 upsert，UUIDv7 保证新 ID 不冲突 |
| 9 | 软删除由 Template 处理 | `softDeleteById` 设 deletedAt，查询自动加 `.isNull()` |
| 10 | DynamicSqlSupport / Mapper / TypeHandler 放各 module 的 repo/ 下 | 跟表强绑定，拆微服务整目录搬 |
| 11 | 通用基础设施放 infra/mybatis/ | JsonbTypeHandler 基类、JsonbUtil、CrudRepoTemplate |

---

## 目录结构

```
infra/mybatis/
├── JsonbTypeHandler.kt          # 泛型基类 abstract class
├── JsonbUtil.kt                 # Jackson 序列化/反序列化工具
├── CrudRepoTemplate.kt          # 通用 CRUD 模板
├── TableMeta.kt                 # 表元数据 data class
└── MybatisConfig.kt             # SqlSessionFactory 配置（writer/reader）

infra/db/
├── ModuleCtx.kt                 # data class（op + session + inTransaction）
├── ModuleCtxFactory.kt          # 从 ClusterRouter 选择 session
├── ClusterRouter.kt             # 接口不变
└── ClusterSessionPair.kt        # writer/reader SqlSessionFactory 对

infra/tx/
├── GlobalTxRunner.kt            # openSession(autoCommit=false) + commit/rollback
└── TxRunner.kt                  # 模块级预留

modules/demo/repo/
├── TodoTable.kt                 # object : SqlTable("core_todo")
├── TodoItemTable.kt             # object : SqlTable("core_demo_item")
├── TodoMapper.kt                # @Mapper interface
├── TodoItemMapper.kt            # @Mapper interface
├── TodoMetaTypeHandler.kt       # class : JsonbTypeHandler<TodoMeta>
└── TodoRepository.kt            # CrudRepoTemplate + 自定义查询
    TodoItemRepository.kt

modules/ai/repo/
├── ScanRecordTable.kt
├── ScanCollectionTable.kt
├── ScanCollectionItemTable.kt
├── AgnesKeyTable.kt
├── ScanRecordMapper.kt
├── ScanCollectionMapper.kt
├── ScanCollectionItemMapper.kt
├── AgnesKeyMapper.kt
├── JsonbMapTypeHandler.kt       # Map<String,Any?> 版
├── ImageRefListTypeHandler.kt   # List<ImageRef> 版
├── ScanRecordRepository.kt
├── ScanCollectionRepository.kt
├── ScanCollectionItemRepository.kt
└── AgnesKeyRepository.kt

modules/auth/repo/
├── AppUserTable.kt
├── AuthIdentityTable.kt
├── AuthProviderIdentityTable.kt
├── AuthDeviceSecretTable.kt
├── AppRefreshTokenTable.kt
├── UserInstallBindingTable.kt
├── AuthTenantTable.kt
├── *Mapper.kt                   # 每个表一个
└── *Repository.kt

modules/payment/repo/
├── SubscriptionTable.kt
├── StoreNotificationTable.kt
├── *Mapper.kt
└── *Repository.kt

modules/cms/repo/
├── FeedbackTable.kt
├── FeedbackMapper.kt
└── FeedbackRepository.kt

modules/storage/repo/
├── UploadRecordTable.kt
├── UploadRecordMapper.kt
└── UploadRecordRepository.kt

modules/app/repo/
├── AppConfigRevisionTable.kt
├── AppInfoTable.kt
├── *Mapper.kt
└── *Repository.kt

entity/
├── demo/Todo.kt, TodoItem.kt, TodoMeta.kt
├── ai/ScanRecord.kt, ScanCollection.kt, ScanCollectionItem.kt, AgnesKey.kt, ImageRef.kt
├── auth/AppUser.kt, AuthIdentity.kt, ...
├── payment/Subscription.kt, StoreNotification.kt
├── cms/Feedback.kt
├── storage/UploadRecord.kt
├── app/AppConfigRevision.kt, AppInfo.kt, ConfigTypes.kt
└── shared/Platforms.kt, Tiers.kt
```

---

## 核心代码设计

### ModuleCtx

```kotlin
data class ModuleCtx(
    val op: OperationContext,
    val session: SqlSession,
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) {
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
    val readCache get() = op.readCache

    fun <M : Any> mapper(type: Class<M>): M = session.getMapper(type)
    inline fun <reified M : Any> mapper(): M = session.getMapper(M::class.java)

    fun mustGetAppId() = op.mustGetAppId()
    fun mustGetUserId() = op.mustGetUserId()
}
```

### ClusterSessionPair

```kotlin
data class ClusterSessionPair(
    val writer: SqlSessionFactory,
    val reader: SqlSessionFactory,
)
```

### ModuleCtxFactory

```kotlin
@Component
class ModuleCtxFactory(private val router: ClusterRouter) {

    fun forApp(opCtx: OperationContext): ModuleCtx {
        // 全局事务内 → 复用已有 session
        if (opCtx.globalTxSession != null) {
            return ModuleCtx(op = opCtx, session = opCtx.globalTxSession, inTransaction = true)
        }
        val pair = router.forApp(opCtx.mustGetAppId())
        val factory = if (opCtx.preferReader) pair.reader else pair.writer
        val session = factory.openSession(true)  // autoCommit=true（非事务场景）
        return ModuleCtx(op = opCtx, session = session)
    }
}
```

### GlobalTxRunner

```kotlin
@Component
class GlobalTxRunner(private val router: ClusterRouter) {
    fun <R> withTx(opCtx: OperationContext, body: (OperationContext) -> R): R {
        val pair = router.forApp(opCtx.mustGetAppId())
        val session = pair.writer.openSession(false)  // autoCommit=false
        try {
            val txCtx = opCtx.copy(globalTxSession = session, inGlobalTx = true)
            val result = body(txCtx)
            session.commit()
            return result
        } catch (e: Exception) {
            session.rollback()
            throw e
        } finally {
            session.close()
        }
    }
}
```

### CrudRepoTemplate

```kotlin
class CrudRepoTemplate<E : Any, M : Any>(
    private val meta: TableMeta,
    private val mapperClass: Class<M>,
    private val selectMany: M.(SelectStatementProvider) -> List<E>,
    private val selectOne: M.(SelectStatementProvider) -> E?,
    private val doUpdate: M.(UpdateStatementProvider) -> Int,
    private val doDelete: M.(DeleteStatementProvider) -> Int,
) {
    private fun m(mc: ModuleCtx): M = mc.mapper(mapperClass)

    fun findById(mc: ModuleCtx, appId: UUID, id: UUID): E? { ... }
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): List<E> { ... }
    fun findByCursor(mc: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int, extra: ...): Page<E> { ... }
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean { ... }
    fun softDeleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean { ... }
    fun softDeleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int { ... }
    fun hardDeleteById(mc: ModuleCtx, appId: UUID, id: UUID): Boolean { ... }
    fun hardDeleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>): Int { ... }
}

data class TableMeta(
    val table: SqlTable,
    val id: SqlColumn<UUID>,
    val appId: SqlColumn<UUID>?,
    val deletedAt: SqlColumn<Instant>?,
    val allColumns: List<SqlColumn<*>>,
)
```

### JsonbTypeHandler 基类

```kotlin
abstract class JsonbTypeHandler<T>(private val type: Class<T>) : BaseTypeHandler<T>() {
    override fun setNonNullParameter(ps: PreparedStatement, i: Int, parameter: T, jdbcType: JdbcType?) {
        val pgObj = PGobject().apply { this.type = "jsonb"; value = JsonbUtil.serialize(parameter) }
        ps.setObject(i, pgObj)
    }
    override fun getNullableResult(rs: ResultSet, columnName: String): T? =
        JsonbUtil.deserialize(rs.getString(columnName), type)
    override fun getNullableResult(rs: ResultSet, columnIndex: Int): T? =
        JsonbUtil.deserialize(rs.getString(columnIndex), type)
    override fun getNullableResult(cs: CallableStatement, columnIndex: Int): T? =
        JsonbUtil.deserialize(cs.getString(columnIndex), type)
}
```

### 一行子类示例

```kotlin
// modules/demo/repo/TodoMetaTypeHandler.kt
class TodoMetaTypeHandler : JsonbTypeHandler<TodoMeta>(TodoMeta::class.java)

// modules/ai/repo/JsonbMapTypeHandler.kt
class JsonbMapTypeHandler : JsonbTypeHandler<Map<String, Any?>>(Map::class.java as Class<Map<String, Any?>>)

// modules/ai/repo/ImageRefListTypeHandler.kt
class ImageRefListTypeHandler : JsonbTypeHandler<List<ImageRef>>(List::class.java as Class<List<ImageRef>>)
```

---

## Demo 模块完整示例

### Entity

```kotlin
// entity/demo/Todo.kt
data class Todo(
    val id: UUID,
    val appId: UUID,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val title: String,
    val done: Boolean = false,
    val note: String? = null,
    val meta: TodoMeta? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)

// entity/demo/TodoMeta.kt
data class TodoMeta(
    val tags: List<String> = emptyList(),
    val color: String? = null,
    val priority: Int = 0,
)

// entity/demo/TodoItem.kt
data class TodoItem(
    val id: UUID,
    val appId: UUID,
    val todoId: UUID,
    val content: String,
    val done: Boolean = false,
    val note: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

### DynamicSqlSupport

```kotlin
// modules/demo/repo/TodoTable.kt
object TodoTable : SqlTable("core_todo") {
    val id = column<UUID>("id")
    val appId = column<UUID>("app_id")
    val installId = column<UUID>("install_id")
    val userId = column<UUID>("user_id")
    val title = column<String>("title")
    val done = column<Boolean>("done")
    val note = column<String>("note")
    val meta = column<String>("meta")
    val createdAt = column<Instant>("created_at")
    val updatedAt = column<Instant>("updated_at")
    val deletedAt = column<Instant>("deleted_at")

    val allColumns = listOf(id, appId, installId, userId, title, done, note, meta, createdAt, updatedAt, deletedAt)
    val meta_ = TableMeta(table = this, id = id, appId = appId, deletedAt = deletedAt, allColumns = allColumns)
}
```

### Mapper

```kotlin
// modules/demo/repo/TodoMapper.kt
@Mapper
interface TodoMapper {
    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @Results(id = "TodoResult", value = [
        Result(column = "id", property = "id"),
        Result(column = "app_id", property = "appId"),
        Result(column = "install_id", property = "installId"),
        Result(column = "user_id", property = "userId"),
        Result(column = "title", property = "title"),
        Result(column = "done", property = "done"),
        Result(column = "note", property = "note"),
        Result(column = "meta", property = "meta", typeHandler = TodoMetaTypeHandler::class),
        Result(column = "created_at", property = "createdAt"),
        Result(column = "updated_at", property = "updatedAt"),
        Result(column = "deleted_at", property = "deletedAt"),
    ])
    fun selectMany(statement: SelectStatementProvider): List<Todo>

    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @ResultMap("TodoResult")
    fun selectOne(statement: SelectStatementProvider): Todo?

    @InsertProvider(type = SqlProviderAdapter::class, method = "insert")
    fun insert(statement: InsertStatementProvider<Todo>): Int

    @InsertProvider(type = SqlProviderAdapter::class, method = "insertMultiple")
    fun insertMultiple(statement: MultiRowInsertStatementProvider<Todo>): Int

    @UpdateProvider(type = SqlProviderAdapter::class, method = "update")
    fun update(statement: UpdateStatementProvider): Int

    @DeleteProvider(type = SqlProviderAdapter::class, method = "delete")
    fun delete(statement: DeleteStatementProvider): Int
}
```

### Repository

```kotlin
// modules/demo/repo/TodoRepository.kt
@Repository
class TodoRepository {

    companion object {
        private val tpl = CrudRepoTemplate(
            meta = TodoTable.meta_,
            mapperClass = TodoMapper::class.java,
            selectMany = TodoMapper::selectMany,
            selectOne = TodoMapper::selectOne,
            doUpdate = TodoMapper::update,
            doDelete = TodoMapper::delete,
        )
    }

    // --- 通用 CRUD ---
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>) = tpl.findByIds(mc, appId, ids)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.softDeleteById(mc, appId, id)
    fun deleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>) = tpl.softDeleteByIds(mc, appId, ids)

    fun findByCursor(mc: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null): Page<Todo> =
        tpl.findByCursor(mc, appId, cursor, limit) {
            filter?.done?.let { and { TodoTable.done isEqualTo it } }
            filter?.userId?.let { and { TodoTable.userId isEqualTo it } }
        }

    // --- 自定义操作 ---

    fun insert(mc: ModuleCtx, entity: Todo): Int {
        val mapper = mc.mapper<TodoMapper>()
        return mapper.insert {
            insertInto(TodoTable)
            map(TodoTable.id) toValue entity.id
            map(TodoTable.appId) toValue entity.appId
            map(TodoTable.installId) toValueOrNull entity.installId
            map(TodoTable.userId) toValueOrNull entity.userId
            map(TodoTable.title) toValue entity.title
            map(TodoTable.done) toValue entity.done
            map(TodoTable.note) toValueOrNull entity.note
            map(TodoTable.meta) toValueOrNull entity.meta?.let { JsonbUtil.serialize(it) }
            map(TodoTable.createdAt) toValue entity.createdAt
            map(TodoTable.updatedAt) toValue entity.updatedAt
        }
    }

    fun partialUpdate(mc: ModuleCtx, appId: UUID, input: UpdateTodoInput) {
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()
        if (set == null && unset.isEmpty()) return

        val mapper = mc.mapper<TodoMapper>()
        mapper.update {
            update(TodoTable)
            // unset 优先
            if (TodoUnsetField.NOTE in unset) {
                set(TodoTable.note) equalToOrNull null as String?
            } else {
                set?.note?.let { set(TodoTable.note) equalTo it }
            }
            set?.title?.let { set(TodoTable.title) equalTo it }
            set?.done?.let { set(TodoTable.done) equalTo it }
            set(TodoTable.updatedAt) equalTo Instant.now()
            where { TodoTable.appId isEqualTo appId }
            and { TodoTable.id isEqualTo input.id }
        }
    }
}
```

---

## 上层不变

Facade、Handler、DataFetcher 签名保持不变：

```kotlin
// Handler 调用方式不变
fun findById(mc: ModuleCtx, appId: UUID, id: UUID): Todo? = todoRepo.findById(mc, appId, id)

// Facade 调用方式不变
fun findById(ctx: OperationContext, id: UUID): Todo? =
    handler.findById(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)
```

---

## 依赖变更

### 移除
- `org.babyfish.jimmer:jimmer-sql-kotlin`
- `org.babyfish.jimmer:jimmer-ksp`（KSP processor）
- KSP Gradle plugin 相关配置

### 新增
- `org.mybatis.spring.boot:mybatis-spring-boot-starter:3.x`
- `org.mybatis.dynamic-sql:mybatis-dynamic-sql:1.5.x`
- `org.postgresql:postgresql`（已有）

### build.gradle.kts
- 删除 KSP 配置块
- 删除 Jimmer 依赖
- 添加 MyBatis 依赖
- 确认 `@MapperScan` 或 MyBatis auto-config

---

## Session 生命周期管理

### 非事务（Query）
```
DataFetcher → ctxProvider.fromDfe(dfe) → OperationContext
  → Facade → mcFactory.forApp(ctx) → openSession(autoCommit=true) → ModuleCtx
    → Handler → Repo.findXxx(mc, ...) → mc.mapper<XxxMapper>().selectXxx(...)
  → session 何时 close?
```

**问题**: 非事务场景下 session 需要在请求结束时 close。

**方案**: `ModuleCtx` 实现 `AutoCloseable`，Facade 方法用 `mc.use { }` 或者在 OperationContextProvider 层面管理。

更简单的做法：**非事务场景也统一由 `GlobalTxRunner`-like 的 wrapper 管理**，或者 Facade 每次 `forApp` 后在 finally 中 close：

```kotlin
// Facade
fun findById(ctx: OperationContext, id: UUID): Todo? {
    val mc = mcFactory.forApp(ctx)
    try {
        return handler.findById(mc, ctx.mustGetAppId(), id)
    } finally {
        if (!mc.inTransaction) mc.session.close()
    }
}
```

或者更优雅——`ModuleCtxFactory` 返回的 mc 在全局事务内不 close（由 GlobalTxRunner close），非事务时每次操作后 close。可以封装：

```kotlin
// infra/db/ModuleCtxFactory.kt
inline fun <R> withCtx(opCtx: OperationContext, body: (ModuleCtx) -> R): R {
    if (opCtx.globalTxSession != null) {
        return body(ModuleCtx(op = opCtx, session = opCtx.globalTxSession, inTransaction = true))
    }
    val pair = router.forApp(opCtx.mustGetAppId())
    val factory = if (opCtx.preferReader) pair.reader else pair.writer
    val session = factory.openSession(true)
    try {
        return body(ModuleCtx(op = opCtx, session = session))
    } finally {
        session.close()
    }
}
```

Facade 用法：
```kotlin
fun findById(ctx: OperationContext, id: UUID): Todo? =
    mcFactory.withCtx(ctx) { mc -> handler.findById(mc, ctx.mustGetAppId(), id) }
```

---

## 迁移执行顺序

### Phase 1: 基础设施搭建

1. 添加 MyBatis 依赖，移除 Jimmer 依赖
2. 创建 `infra/mybatis/` — JsonbTypeHandler、JsonbUtil、CrudRepoTemplate、TableMeta
3. 改写 `infra/db/ModuleCtx.kt` — `KSqlClient` → `SqlSession`
4. 改写 `infra/db/ModuleCtxFactory.kt` — 提供 `withCtx` + `forApp`
5. 改写 `infra/tx/GlobalTxRunner.kt` — session 级事务
6. 配置 `MybatisConfig.kt` — 双 SqlSessionFactory（writer/reader）
7. 删除 `infra/jimmer/` 目录（ClusterRegistry 保留改名，其余删）
8. 编译验证（此时 modules 全红）

### Phase 2: Entity 改写

1. 删除 `entity/` 下所有 Jimmer interface + @MappedSuperclass
2. 重写为 data class（逐个 entity）
3. 编译验证（modules 仍红，entity 绿）

### Phase 3: 模块逐个迁移（每个模块独立可编译）

顺序：demo → cms → app → storage → payment → ai → auth

每个模块：
1. 创建 XxxTable.kt（DynamicSqlSupport）
2. 创建 XxxMapper.kt（@Mapper + @Results）
3. 创建必要的 TypeHandler 子类
4. 改写 XxxRepository.kt（CrudRepoTemplate + 自定义方法）
5. 编译验证

### Phase 4: Facade 适配

1. Facade 中 `mcFactory.forApp(ctx)` → `mcFactory.withCtx(ctx) { mc -> ... }`
2. 确认 session 生命周期正确
3. 全量编译

### Phase 5: 清理

1. 删除 build.gradle.kts 中 KSP 配置
2. 删除 `build/generated/ksp/` 输出
3. 删除旧的 Jimmer 相关 import
4. 全量测试

---

## FilterGroup 动态查询

### 设计

白名单只声明 KProperty 列表，类型和列映射全自动推导：

```kotlin
// modules/demo/repo/TodoRepository.kt
companion object {
    val FILTERABLE = listOf(Todo::title, Todo::done, Todo::userId, Todo::note, Todo::createdAt, Todo::updatedAt)
}
```

`FilterGroupResolver` 从 KProperty 自动获取：
- **字段名**: `prop.name` → `"title"`（前端传的 key）
- **类型**: `prop.returnType` → `String::class`（用于 value 转换）
- **SqlColumn**: 通过反射从 `TodoTable` 找同名属性 → `TodoTable.title`

### 约定

`SqlTable` object 的属性名必须与 entity data class 的属性名相同：

```kotlin
data class Todo(val title: String, val userId: UUID?, ...)

object TodoTable : SqlTable("core_todo") {
    val title = column<String>("title")      // 属性名 = Todo.title
    val userId = column<UUID>("user_id")     // 属性名 = Todo.userId
}
```

### FilterGroupResolver 实现

```kotlin
// infra/mybatis/FilterGroupResolver.kt
object FilterGroupResolver {

    /**
     * 预构建 field 元数据：KProperty → (name, type, SqlColumn)
     * 调用一次缓存，后续查找 O(1)。
     */
    fun <E : Any> buildMeta(
        entityClass: KClass<E>,
        tableObject: SqlTable,
        filterable: List<KProperty1<E, *>>,
    ): Map<String, FieldMeta> {
        return filterable.associate { prop ->
            val name = prop.name
            val type = prop.returnType.jvmErasure.java
            val column = tableObject::class.memberProperties
                .first { it.name == name }
                .getter.call(tableObject) as SqlColumn<*>
            name to FieldMeta(name, column, type)
        }
    }

    data class FieldMeta(
        val name: String,
        val column: SqlColumn<*>,
        val type: Class<*>,
    )

    /**
     * 在 KotlinWhereBuilder 中追加 FilterGroup 条件。
     */
    fun apply(builder: KotlinWhereBuilder, filter: FilterGroup?, meta: Map<String, FieldMeta>) {
        if (filter == null) return
        applyGroup(builder, filter, meta)
    }

    private fun applyGroup(builder: KotlinWhereBuilder, group: FilterGroup, meta: Map<String, FieldMeta>) {
        group.and?.forEach { expr ->
            when {
                expr.field != null -> applyField(builder, expr.field, meta)
                expr.group != null -> applyGroup(builder, expr.group, meta)
            }
        }
        group.or?.forEach { expr ->
            builder.or {
                when {
                    expr.field != null -> applyField(this, expr.field, meta)
                    expr.group != null -> applyGroup(this, expr.group, meta)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyField(builder: KotlinWhereBuilder, field: FieldFilter, meta: Map<String, FieldMeta>) {
        val fm = meta[field.field]
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "Field not filterable: ${field.field}")

        when (field.op) {
            FilterOp.EQ -> builder.and { (fm.column as SqlColumn<Any>) isEqualTo convert(field.value, fm.type) }
            FilterOp.NE -> builder.and { (fm.column as SqlColumn<Any>) isNotEqualTo convert(field.value, fm.type) }
            FilterOp.GT -> builder.and { (fm.column as SqlColumn<Comparable<Any>>) isGreaterThan convert(field.value, fm.type) as Comparable<Any> }
            FilterOp.GTE -> builder.and { (fm.column as SqlColumn<Comparable<Any>>) isGreaterThanOrEqualTo convert(field.value, fm.type) as Comparable<Any> }
            FilterOp.LT -> builder.and { (fm.column as SqlColumn<Comparable<Any>>) isLessThan convert(field.value, fm.type) as Comparable<Any> }
            FilterOp.LTE -> builder.and { (fm.column as SqlColumn<Comparable<Any>>) isLessThanOrEqualTo convert(field.value, fm.type) as Comparable<Any> }
            FilterOp.IN -> builder.and { (fm.column as SqlColumn<Any>) isIn convertList(field.values, fm.type) }
            FilterOp.NIN -> builder.and { (fm.column as SqlColumn<Any>) isNotIn convertList(field.values, fm.type) }
            FilterOp.LIKE -> builder.and { (fm.column as SqlColumn<String>) isLike "%${field.value}%" }
            FilterOp.IS_NULL -> builder.and { fm.column.isNull() }
            FilterOp.IS_NOT_NULL -> builder.and { fm.column.isNotNull() }
        }
    }

    private fun convert(value: Any?, type: Class<*>): Any = when (type) {
        UUID::class.java -> UUID.fromString(value as String)
        Instant::class.java -> Instant.ofEpochMilli((value as Number).toLong())
        Boolean::class.java, java.lang.Boolean::class.java -> value as Boolean
        Int::class.java, java.lang.Integer::class.java -> (value as Number).toInt()
        Long::class.java, java.lang.Long::class.java -> (value as Number).toLong()
        String::class.java -> value as String
        else -> value!!
    }

    private fun convertList(values: List<Any?>?, type: Class<*>): List<Any> =
        values?.map { convert(it, type) } ?: emptyList()
}
```

### Repository 用法

```kotlin
@Repository
class TodoRepository {

    companion object {
        val FILTERABLE = listOf(Todo::title, Todo::done, Todo::userId, Todo::note, Todo::createdAt, Todo::updatedAt)

        // 启动时构建一次，缓存
        val filterMeta = FilterGroupResolver.buildMeta(Todo::class, TodoTable, FILTERABLE)

        private val tpl = CrudRepoTemplate(...)
    }

    fun findByFilter(mc: ModuleCtx, appId: UUID, filter: FilterGroup?, cursor: UUID?, limit: Int): Page<Todo> {
        val mapper = mc.mapper<TodoMapper>()
        val rows = mapper.selectMany {
            where { TodoTable.appId isEqualTo appId }
            and { TodoTable.deletedAt.isNull() }
            FilterGroupResolver.apply(this, filter, filterMeta)
            cursor?.let { and { TodoTable.id isLessThan it } }
            orderBy(TodoTable.id.descending())
            limit(limit + 1)
        }
        return Page.of(rows, limit) { it.id.toString() }
    }
}
```

---

## 验收标准

- [ ] `./gradlew :core-api:compileKotlin` 零错误
- [ ] 启动无报错
- [ ] 无 Jimmer 依赖残留（import / gradle）
- [ ] mutation 走 writer session / query 走 reader session
- [ ] GlobalTxRunner 内所有操作走同一 session
- [ ] JSONB 字段正确序列化/反序列化（Map 和领域模型）
- [ ] 游标分页正常工作
- [ ] 软删除正常工作
- [ ] E2E 测试通过

---

## 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| MyBatis @Results 手写映射容易遗漏字段 | 运行时 null | 每个 Mapper 写简单的集成测试 |
| SqlSession 未 close 导致连接泄漏 | 连接池耗尽 | withCtx 统一 try/finally |
| TypeHandler 注册遗漏 | JSONB 读为 null | 集成测试覆盖 JSONB 字段 |
| Jimmer 的对象图加载能力丢失 | N+1 | 已有 DataLoader 机制兜底 |
