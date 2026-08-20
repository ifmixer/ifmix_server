# Jimmer → MyBatis Dynamic SQL 迁移计划

> 创建时间: 2026-08-20
> 状态: 待执行
> 目标: 用 MyBatis Dynamic SQL 替换 Jimmer，codegen 生成 Table/Mapper，通用 CRUD 通过 CrudRepoTemplate 复用

---

## 设计决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | Entity 用 data class | 零魔法、可 copy、可调试、nullable 字段无 UnloadedException |
| 2 | **codegen 生成 Table + Mapper** | 减少手写样板，字段变更时重新生成即可 |
| 3 | codegen 可随时"毕业" | 把文件从 generated 移到 src，从配置中排除该表，之后手动维护 |
| 4 | 保留 CrudRepoTemplate | 通用 CRUD 委托，减少每个 Repo 的样板代码 |
| 5 | 保留 ModuleCtx 显式传递 | 不依赖 Spring ThreadLocal 事务传播 |
| 6 | ModuleCtx 携带 SqlSession | 显式路由读写分离 |
| 7 | Template 持有 mapper class + 函数引用 | 每次从 mc.session 动态获取 mapper 实例 |
| 8 | JSONB 用泛型 TypeHandler | 基类在 infra，每个领域模型一行子类 |
| 9 | insert 和 update 分离 | 不做隐式 upsert，UUIDv7 保证新 ID 不冲突 |
| 10 | 软删除由 Template 处理 | `softDeleteById` 设 deletedAt，查询自动加 `.isNull()` |
| 11 | TypeHandler 放各 module 的 repo/ 下 | 跟表强绑定 |
| 12 | 通用基础设施放 infra/mybatis/ | JsonbTypeHandler 基类、JsonbUtil、CrudRepoTemplate、MybatisConfig |
| 13 | FilterGroup 白名单用 KProperty | 自动推导字段名、类型、SqlColumn 映射 |

---

## Codegen 策略

### 工具

[MyBatis Generator](https://mybatis.org/generator/) + Kotlin Target + Dynamic SQL Runtime

### 生成物

| 生成物 | 输出位置 | 内容 |
|--------|----------|------|
| `XxxDynamicSqlSupport.kt` | `build/generated/mybatis/` | SqlTable object + SqlColumn 定义 |
| `XxxMapper.kt` | `build/generated/mybatis/` | @Mapper interface（selectMany/selectOne/insert/update/delete） |
| `XxxRecord.kt` | `build/generated/mybatis/` | data class（表字段 1:1 映射） |

### "毕业"流程

```
1. 某个表需要定制（改 TypeHandler、加自定义方法、用领域模型替代 Record）
2. 把该表的文件从 build/generated/ 复制到 src/main/kotlin/modules/xxx/repo/
3. 从 generatorConfig 中排除该表
4. 以后手动维护该文件
```

### Entity vs Record

- codegen 生成的 `XxxRecord.kt` 是纯 DB 映射 data class
- 如果想用领域模型（如 `TodoMeta` 替代 `String`），毕业后把 Record 改为领域 Entity
- 或者保留 Record 作为 DB 层，在 Handler 中转为领域模型（当前不做，KISS）

---

## 目录结构

```
build/generated/mybatis/                    # codegen 输出（不提交 git）
├── com/ifmix/api/core/generated/mybatis/
│   ├── demo/
│   │   ├── TodoDynamicSqlSupport.kt       # SqlTable + SqlColumn
│   │   ├── TodoMapper.kt                  # @Mapper interface
│   │   ├── TodoRecord.kt                  # data class
│   │   ├── TodoItemDynamicSqlSupport.kt
│   │   ├── TodoItemMapper.kt
│   │   └── TodoItemRecord.kt
│   ├── ai/
│   │   ├── ScanRecordDynamicSqlSupport.kt
│   │   ├── ScanRecordMapper.kt
│   │   └── ScanRecordRecord.kt
│   │   └── ...
│   ├── auth/...
│   ├── payment/...
│   ├── cms/...
│   ├── storage/...
│   └── app/...

src/main/kotlin/.../
├── entity/                                 # 领域模型（可选，毕业后用）
│   ├── demo/Todo.kt, TodoItem.kt, TodoMeta.kt
│   ├── ai/ScanRecord.kt, ImageRef.kt, ...
│   └── ...
├── infra/mybatis/
│   ├── JsonbTypeHandler.kt                 # abstract class
│   ├── JsonbUtil.kt                        # 序列化工具
│   ├── CrudRepoTemplate.kt                # 通用 CRUD 模板
│   ├── TableMeta.kt                        # 表元数据
│   ├── MybatisConfig.kt                    # 双 SqlSessionFactory（writer/reader）
│   └── FilterGroupResolver.kt             # FilterGroup → Dynamic SQL where
├── infra/db/
│   ├── ModuleCtx.kt                        # op + SqlSession + inTransaction
│   ├── ModuleCtxFactory.kt                 # chooseSqlSession 逻辑
│   ├── ClusterRouter.kt                    # 接口不变
│   └── ClusterSessionPair.kt              # writer/reader SqlSessionFactory 对
├── infra/tx/
│   ├── GlobalTxRunner.kt                   # session 级事务
│   └── TxRunner.kt                         # 模块级预留
├── modules/demo/repo/
│   ├── TodoMetaTypeHandler.kt              # 一行子类（领域模型 JSONB）
│   └── TodoRepository.kt                  # CrudRepoTemplate + 自定义查询
├── modules/ai/repo/
│   ├── JsonbMapTypeHandler.kt
│   ├── ImageRefListTypeHandler.kt
│   └── ScanRecordRepository.kt
└── ...
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

    /** 事务内 → 复用 session；事务外 → 按 preferReader 路由 */
    inline fun <R> withCtx(opCtx: OperationContext, body: (ModuleCtx) -> R): R {
        if (opCtx.globalTxSession != null) {
            return body(ModuleCtx(op = opCtx, session = opCtx.globalTxSession, inTransaction = true))
        }
        val pair = router.forApp(opCtx.mustGetAppId())
        val factory = if (opCtx.preferReader) pair.reader else pair.writer
        val session = factory.openSession(true)  // autoCommit=true
        try {
            return body(ModuleCtx(op = opCtx, session = session))
        } finally {
            session.close()
        }
    }

    fun forApp(opCtx: OperationContext): ModuleCtx {
        if (opCtx.globalTxSession != null) {
            return ModuleCtx(op = opCtx, session = opCtx.globalTxSession, inTransaction = true)
        }
        val pair = router.forApp(opCtx.mustGetAppId())
        val factory = if (opCtx.preferReader) pair.reader else pair.writer
        return ModuleCtx(op = opCtx, session = factory.openSession(true))
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
}

data class TableMeta(
    val table: SqlTable,
    val id: SqlColumn<UUID>,
    val appId: SqlColumn<UUID>?,
    val deletedAt: SqlColumn<Instant>?,
    val allColumns: List<SqlColumn<*>>,
)
```

### JsonbTypeHandler

```kotlin
abstract class JsonbTypeHandler<T>(private val type: Class<T>) : BaseTypeHandler<T>() {
    override fun setNonNullParameter(ps: PreparedStatement, i: Int, parameter: T, jdbcType: JdbcType?) {
        val pgObj = PGobject().apply { this.type = "jsonb"; value = JsonbUtil.serialize(parameter) }
        ps.setObject(i, pgObj)
    }
    override fun getNullableResult(rs: ResultSet, columnName: String): T? =
        JsonbUtil.deserialize(rs.getString(columnName), type)
    // ... 其他 override
}

// 一行子类
class TodoMetaTypeHandler : JsonbTypeHandler<TodoMeta>(TodoMeta::class.java)
class JsonbMapTypeHandler : JsonbTypeHandler<Map<String, Any?>>(Map::class.java as Class<Map<String, Any?>>)
```

### FilterGroup — 白名单用 KProperty 自动推导

```kotlin
// Repository 白名单声明
companion object {
    val FILTERABLE = listOf(Todo::title, Todo::done, Todo::userId, Todo::createdAt, Todo::updatedAt)
    // FilterGroupResolver 自动从 KProperty 推导：
    //   prop.name → 字段名（前端传的 key）
    //   prop.returnType → 类型（用于 value 转换）
    //   TodoDynamicSqlSupport 中同名属性 → SqlColumn
}
```

---

## Demo 模块示例（codegen 后的使用方式）

### codegen 生成（build/generated/mybatis/demo/）

```kotlin
// TodoDynamicSqlSupport.kt（自动生成）
object TodoDynamicSqlSupport {
    val todo = Todo()
    class Todo : SqlTable("core_todo") {
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
    }
}

// TodoRecord.kt（自动生成）
data class TodoRecord(
    val id: UUID,
    val appId: UUID,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val title: String,
    val done: Boolean = false,
    val note: String? = null,
    val meta: String? = null,       // JSONB 存为 String（或通过 TypeHandler 转领域模型）
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)

// TodoMapper.kt（自动生成）
@Mapper
interface TodoMapper {
    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @Results(...)
    fun selectMany(statement: SelectStatementProvider): List<TodoRecord>

    @SelectProvider(type = SqlProviderAdapter::class, method = "select")
    @ResultMap("TodoRecordResult")
    fun selectOne(statement: SelectStatementProvider): TodoRecord?

    @InsertProvider(type = SqlProviderAdapter::class, method = "insert")
    fun insert(statement: InsertStatementProvider<TodoRecord>): Int

    @UpdateProvider(type = SqlProviderAdapter::class, method = "update")
    fun update(statement: UpdateStatementProvider): Int

    @DeleteProvider(type = SqlProviderAdapter::class, method = "delete")
    fun delete(statement: DeleteStatementProvider): Int
}
```

### 手写 Repository（src/main/kotlin/.../modules/demo/repo/）

```kotlin
@Repository
class TodoRepository {
    companion object {
        private val t = TodoDynamicSqlSupport.todo
        private val tpl = CrudRepoTemplate(
            meta = TableMeta(t, t.id, t.appId, t.deletedAt, t.allColumns()),
            mapperClass = TodoMapper::class.java,
            selectMany = TodoMapper::selectMany,
            selectOne = TodoMapper::selectOne,
            doUpdate = TodoMapper::update,
            doDelete = TodoMapper::delete,
        )
        val FILTERABLE = listOf(Todo::title, Todo::done, Todo::userId, Todo::createdAt, Todo::updatedAt)
    }

    // 通用 CRUD
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>) = tpl.findByIds(mc, appId, ids)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.softDeleteById(mc, appId, id)
    fun deleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>) = tpl.softDeleteByIds(mc, appId, ids)

    fun findByCursor(mc: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter? = null) =
        tpl.findByCursor(mc, appId, cursor, limit) {
            filter?.done?.let { and { t.done isEqualTo it } }
            filter?.userId?.let { and { t.userId isEqualTo it } }
        }

    // 自定义
    fun insert(mc: ModuleCtx, entity: TodoRecord): Int {
        val mapper = mc.mapper<TodoMapper>()
        return mapper.insert {
            insertInto(t)
            map(t.id) toValue entity.id
            map(t.appId) toValue entity.appId
            map(t.installId) toValueOrNull entity.installId
            map(t.userId) toValueOrNull entity.userId
            map(t.title) toValue entity.title
            map(t.done) toValue entity.done
            map(t.note) toValueOrNull entity.note
            map(t.meta) toValueOrNull entity.meta
            map(t.createdAt) toValue entity.createdAt
            map(t.updatedAt) toValue entity.updatedAt
        }
    }

    fun partialUpdate(mc: ModuleCtx, appId: UUID, input: UpdateTodoInput) {
        val mapper = mc.mapper<TodoMapper>()
        val set = input.set
        val unset = input.unset?.toSet() ?: emptySet()
        if (set == null && unset.isEmpty()) return
        mapper.update {
            update(t)
            if (TodoUnsetField.NOTE in unset) set(t.note) equalToOrNull null as String?
            else set?.note?.let { set(t.note) equalTo it }
            set?.title?.let { set(t.title) equalTo it }
            set?.done?.let { set(t.done) equalTo it }
            set(t.updatedAt) equalTo Instant.now()
            where { t.appId isEqualTo appId }
            and { t.id isEqualTo input.id }
        }
    }
}
```

---

## 依赖变更

### 移除
- `org.babyfish.jimmer:jimmer-sql-kotlin`
- `org.babyfish.jimmer:jimmer-ksp`
- KSP Gradle plugin (`com.google.devtools.ksp`)
- Jimmer 相关配置

### 新增
- `org.mybatis.spring.boot:mybatis-spring-boot-starter:3.x`
- `org.mybatis.dynamic-sql:mybatis-dynamic-sql:1.5.x`
- `org.mybatis.generator:mybatis-generator-core:1.4.x`（codegen，compileOnly 或 buildscript）
- `org.postgresql:postgresql`（已有）

---

## Session 生命周期

| 场景 | 谁 open | 谁 close | autoCommit |
|------|---------|----------|------------|
| Query（无事务） | ModuleCtxFactory.withCtx | withCtx finally | true |
| Mutation（全局事务） | GlobalTxRunner.withTx | withTx finally | false |
| DataLoader（事务内） | 复用 globalTxSession | GlobalTxRunner close | — |

**规则**：Facade 使用 `mcFactory.withCtx(ctx) { mc -> ... }` 确保 session 在 finally 中 close。

---

## 迁移执行顺序

### Phase 0: 准备（先做 todo 模块验证）

1. 添加 MyBatis + Dynamic SQL 依赖
2. 配置 MyBatis Generator（generatorConfig.xml），只对 `core_todo` + `core_demo_item` 生成
3. 运行 codegen，生成 TodoDynamicSqlSupport / TodoMapper / TodoRecord
4. 创建 `infra/mybatis/` — JsonbTypeHandler、JsonbUtil、CrudRepoTemplate、TableMeta、MybatisConfig
5. 改写 `infra/db/ModuleCtx.kt` — `KSqlClient` → `SqlSession`
6. 改写 `infra/db/ModuleCtxFactory.kt` — withCtx + forApp
7. 改写 `infra/tx/GlobalTxRunner.kt` — session 级事务
8. 改写 `modules/demo/repo/TodoRepository.kt` — 使用 codegen 生成的 Mapper + CrudRepoTemplate
9. 改写 `modules/demo/handler/TodoAggHandler.kt` — 构造 TodoRecord 而非 Jimmer Draft
10. **编译验证 + 启动 + 测试 todo CRUD**

### Phase 1: 全量 codegen

1. generatorConfig 加入所有表（19 个）
2. 运行 codegen
3. 编写各模块的 TypeHandler 子类
4. 编译验证

### Phase 2: 模块逐个迁移（每个模块独立可编译）

顺序：demo(已完成) → cms → app → storage → payment → ai → auth

每个模块：
1. 改写 Repository — 使用 codegen Mapper + CrudRepoTemplate
2. 改写 Handler — 构造 Record 而非 Jimmer Draft
3. 确认 Facade 签名不变（对 DataFetcher 透明）
4. 编译验证

### Phase 3: GraphQL 直出适配

1. 确认 codegen 的 Record data class 能被 DGS PropertyDataFetcher 直出
2. 验证 typeMapping 指向 Record 类
3. 验证 JSONB 字段（TypeHandler 注册 + GraphQL JSON scalar）

### Phase 4: 清理

1. 删除 Jimmer 依赖 + KSP 配置
2. 删除 `infra/jimmer/` 目录
3. 删除 `entity/` 下 Jimmer interface（被 codegen Record 替代）
4. 确认 `.gitignore` 包含 `build/generated/mybatis/`
5. 全量测试

---

## codegen 配置参考

```xml
<!-- generatorConfig.xml -->
<generatorConfiguration>
  <context id="PostgreSQL" targetRuntime="MyBatis3DynamicSql">
    <plugin type="org.mybatis.generator.plugins.KotlinDataClassPlugin" />

    <jdbcConnection driverClass="org.postgresql.Driver"
                    connectionURL="jdbc:postgresql://localhost:5432/ifmix_core_local"
                    userId="postgres" password="postgres" />

    <javaModelGenerator targetPackage="com.ifmix.api.core.generated.mybatis.demo"
                        targetProject="build/generated/mybatis" />

    <javaClientGenerator targetPackage="com.ifmix.api.core.generated.mybatis.demo"
                         targetProject="build/generated/mybatis" />

    <!-- Phase 0: 只生成 todo -->
    <table tableName="core_todo" domainObjectName="Todo" />
    <table tableName="core_demo_item" domainObjectName="TodoItem" />

    <!-- Phase 1: 全量（逐步取消注释）
    <table tableName="core_scan_record" domainObjectName="ScanRecord" />
    <table tableName="core_scan_collection" domainObjectName="ScanCollection" />
    ...
    -->
  </context>
</generatorConfiguration>
```

---

## 验收标准

- [ ] `./gradlew :core-api:compileKotlin` 零错误
- [ ] 启动无报错
- [ ] 无 Jimmer 依赖残留
- [ ] mutation 走 writer session / query 走 reader session
- [ ] GlobalTxRunner 内所有操作走同一 session
- [ ] JSONB 字段正确序列化/反序列化
- [ ] 游标分页正常工作
- [ ] 软删除正常工作
- [ ] FilterGroup 动态查询正常工作
- [ ] Todo 模块 E2E 测试通过
- [ ] codegen 重新生成后编译通过（验证不依赖手动修改 generated 文件）

---

## 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| codegen 生成的 Record 字段名与 entity 不一致 | 编译错误 | codegen 配置 columnRenamingRule |
| JSONB TypeHandler 注册遗漏 | 运行时 null | 集成测试覆盖 |
| Session 未 close 导致连接泄漏 | 连接池耗尽 | withCtx 统一 try/finally |
| Jimmer 对象图加载能力丢失 | N+1 | 已有 DataLoader 机制兜底 |
| MyBatis Generator 不支持 Kotlin data class 默认值 | nullable 字段无默认 | 生成后手动加或用 KotlinDataClassPlugin |
