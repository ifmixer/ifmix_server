# 计划: Demo 模块替换为 MyBatis Dynamic SQL

## 背景

- 动机: 团队对 MyBatis 更熟悉
- 范围: 仅 demo 模块（Todo / TodoItem），不需要兼容 jOOQ 基础设施
- 后续: 如果 MyBatis 好用，未来可以把 jOOQ 删掉

## 版本确认（已验证 ✅）

| 组件 | 版本 | 兼容性 |
|------|------|--------|
| mybatis-spring-boot-starter | **4.1.0** | ✅ Spring Boot 4.x / Spring Framework 7 |
| mybatis-spring | **4.1.0** | ✅ Spring Framework 7 |
| mybatis-dynamic-sql | **2.0.0** | ✅ Java 17+ |

## 当前 Demo 模块盘点

| 层 | 文件 | 状态 |
|---|---|---|
| Entity | `entity/todo/Todo.kt`, `entity/todo/TodoItem.kt` | ✅ 保留（data class 与 ORM 无关） |
| GraphQL Schema | `schema/customer/demo.graphqls` | ✅ 保留不动 |
| DataFetcher | **不存在** | 需新建 |
| Service | **不存在** | 需新建 |
| Repository | **不存在** | 需用 MyBatis 新建 |
| jOOQ codegen | `src/generated/jooq/.../CoreTodo.kt`, `CoreTodoItem.kt` | 保留（其他模块在用） |
| E2E 测试 | `TodoMetaJsonbE2eTest.kt`（Jimmer 遗留） | 删除或重写 |

---

## 架构设计

### 调用链路

```
DataFetcher
  → opCtx = OperationContextProvider.fromDfe(dfe)     // isMutation=true/false
  → svcCtxFactory.forApp(opCtx).use { ctx ->
        demoService.xxx(ctx, ...)
    }

ModuleService
  → repo.findById(ctx, ...)

Repository
  → ctx.mapper<TodoMapper>().selectOne(...)
```

### 读写分离机制

**在 `SvcCtxFactory.forApp(opCtx)` 时决定主/从**，不依赖 `@Transactional(readOnly=true)`：

```
SvcCtxFactory.forApp(opCtx)
  ├── clusterId = router.resolveCluster(appId)
  ├── opCtx.isMutation?
  │     true  → mybatis.writer.openSession(autoCommit=true)
  │     false → mybatis.reader.openSession(autoCommit=true)
  └── SvcCtx(op, dsl, session, clusterId, inTransaction=false)
```

- Query（`isMutation=false`）→ reader SqlSession → 从库
- Mutation（`isMutation=true`）→ writer SqlSession → 主库
- 事务场景：`MyBatisTxRunner.withTx()` 新开 writer session（autoCommit=false），commit/rollback 手动控制

### SvcCtx 扩展

```kotlin
data class SvcCtx(
    val op: OperationContext,
    val dsl: DSLContext,                    // jOOQ 模块用（保持现有）
    val session: SqlSession? = null,        // MyBatis 模块用
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) : AutoCloseable {

    /** MyBatis mapper 便捷获取 */
    inline fun <reified M> mapper(): M =
        session?.getMapper(M::class.java)
            ?: error("SqlSession not available in this SvcCtx")

    override fun close() {
        session?.close()
    }
}
```

### MyBatis 双 SqlSessionFactory

```kotlin
@Component
class MyBatisSessionFactories(private val registry: DataSourceRegistry) {

    val writer: SqlSessionFactory by lazy { build(registry.writerDataSource) }
    val reader: SqlSessionFactory by lazy { build(registry.readerDataSource) }

    private fun build(ds: DataSource): SqlSessionFactory {
        val txFactory = JdbcTransactionFactory()
        val env = Environment("mybatis", txFactory, ds)
        val config = Configuration(env).apply {
            isMapUnderscoreToCamelCase = true
            typeHandlerRegistry.register(UuidTypeHandler::class.java)
            typeHandlerRegistry.register(InstantTypeHandler::class.java)
            // 注册 Mapper
            addMapper(TodoMapper::class.java)
            addMapper(TodoItemMapper::class.java)
        }
        return SqlSessionFactoryBuilder().build(config)
    }
}
```

### SvcCtxFactory 改造

```kotlin
@Component
class SvcCtxFactory(
    private val router: ClusterRouter,
    private val mybatis: MyBatisSessionFactories?,  // 可选注入，未引入 MyBatis 时为 null
) {

    fun forApp(opCtx: OperationContext): SvcCtx {
        val dsl = opCtx.globalTxDsl ?: router.forApp(opCtx.mustGetAppId())
        val session = mybatis?.let { sf ->
            val factory = if (opCtx.isMutation) sf.writer else sf.reader
            factory.openSession(true)  // autoCommit=true；事务由 TxRunner 管
        }
        return SvcCtx(
            op = opCtx,
            dsl = dsl,
            session = session,
            inTransaction = opCtx.inGlobalTx,
        )
    }
}
```

### MyBatis TxRunner

```kotlin
@Component
class MyBatisTxRunner(private val mybatis: MyBatisSessionFactories) {

    fun <R> withTx(svcCtx: SvcCtx, body: (SvcCtx) -> R): R {
        if (svcCtx.inTransaction) return body(svcCtx)

        // 新开 writer session，autoCommit=false
        val txSession = mybatis.writer.openSession(false)
        val txCtx = svcCtx.copy(session = txSession, inTransaction = true)
        return try {
            val result = body(txCtx)
            txSession.commit()
            result
        } catch (e: Exception) {
            txSession.rollback()
            throw e
        } finally {
            txSession.close()
        }
    }
}
```

### 通用 MybatisCrudOps

```kotlin
/**
 * MyBatis Dynamic SQL 通用 CRUD 操作。
 * 类似 jOOQ 的 CrudRepoOps，每个 Repository 绑定一个实例。
 */
class MybatisCrudOps<T : Any>(
    private val table: SqlTable,
    private val idColumn: SqlColumn<UUID>,
    private val appIdColumn: SqlColumn<UUID>?,
    private val deletedAtColumn: SqlColumn<Instant>?,
    private val allColumns: List<BasicColumn>,
    private val mapperFn: (SvcCtx) -> CrudMapper<T>,  // ctx → typed mapper
) {
    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): T? { ... }
    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<T> { ... }
    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<T> { ... }
    fun insert(ctx: SvcCtx, record: T) { ... }
    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean { /* 软删除 */ ... }
    fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean { ... }
}
```

### Repository 示例

```kotlin
@Repository
class TodoRepository {

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): Todo? =
        ctx.mapper<TodoMapper>().selectOne(
            select(allColumns)
                .from(coreTodo)
                .where(CoreTodoDSS.appId, isEqualTo(appId))
                .and(CoreTodoDSS.id, isEqualTo(id))
                .and(CoreTodoDSS.deletedAt, isNull())
                .build().render(RenderingStrategies.MYBATIS3)
        )

    fun insert(ctx: SvcCtx, entity: Todo) =
        ctx.mapper<TodoMapper>().insert(
            insertInto(coreTodo)
                .map(CoreTodoDSS.id).toValue(entity.id)
                .map(CoreTodoDSS.appId).toValue(entity.appId)
                .map(CoreTodoDSS.title).toValue(entity.title)
                .map(CoreTodoDSS.done).toValue(entity.done)
                .map(CoreTodoDSS.createdAt).toValue(entity.createdAt)
                .map(CoreTodoDSS.updatedAt).toValue(entity.updatedAt)
                .build().render(RenderingStrategies.MYBATIS3)
        )
}
```

### DataFetcher 使用

```kotlin
@DgsComponent
class DemoFetcher(
    private val provider: OperationContextProvider,
    private val svcCtxFactory: SvcCtxFactory,
    private val demoService: DemoModuleService,
) {
    @DgsQuery
    fun query_demo_findTodoById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo =
        svcCtxFactory.forApp(provider.fromDfe(dfe)).use { ctx ->
            demoService.findById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)
        }

    @DgsMutation
    fun mutation_demo_createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput) =
        svcCtxFactory.forApp(provider.fromDfe(dfe)).use { ctx ->
            CreateTodoPayload(todo = demoService.createTodo(ctx, input))
        }
}
```

---

## 实施步骤

### Phase 1: 依赖 + 基础设施

1. `build.gradle.kts` 添加依赖:
   ```kotlin
   implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.1.0")
   implementation("org.mybatis.dynamic-sql:mybatis-dynamic-sql:2.0.0")
   ```
2. `infra/mybatis/MyBatisSessionFactories.kt` — 双 SqlSessionFactory（writer/reader）
3. `infra/mybatis/UuidTypeHandler.kt` — UUID ↔ PG uuid
4. `infra/mybatis/InstantTypeHandler.kt` — Instant ↔ PG timestamptz
5. `infra/mybatis/JsonbTypeHandler.kt` — String ↔ PG jsonb（PGobject）
6. `infra/mybatis/MyBatisTxRunner.kt` — 事务管理
7. `infra/mybatis/MybatisCrudOps.kt` — 通用 CRUD 操作
8. 改造 `SvcCtx` — 加 `session: SqlSession?` + `AutoCloseable`
9. 改造 `SvcCtxFactory` — 注入 `MyBatisSessionFactories?`，forApp 时 openSession

### Phase 2: 表定义 + Mapper

10. `modules/demo/mybatis/CoreTodoDynamicSqlSupport.kt` — 表+列定义
11. `modules/demo/mybatis/CoreTodoItemDynamicSqlSupport.kt`
12. `modules/demo/repo/TodoMapper.kt` — MyBatis Mapper 接口
13. `modules/demo/repo/TodoItemMapper.kt`

### Phase 3: Repository + Service

14. `modules/demo/repo/TodoRepository.kt`
15. `modules/demo/repo/TodoItemRepository.kt`
16. `modules/demo/service/DemoModuleService.kt`
17. `modules/demo/service/internal/TodoEntityService.kt`

### Phase 4: DataFetcher

18. `bff/graphql/customer/demo/DemoFetcher.kt`

### Phase 5: 测试

19. 删除 `TodoMetaJsonbE2eTest.kt`
20. 新建 `DemoTodoE2eTest.kt`（Testcontainers PG）
21. `./gradlew :core-api:compileKotlin`
22. `./gradlew :core-api:test`

### Phase 6: 文档

23. 更新 `AGENTS.md` — 记录 demo 模块用 MyBatis
24. 更新 `ARCHITECTURE.md` — 记录 MyBatis 共存方案

---

## 文件清单

| 操作 | 文件 |
|------|------|
| 修改 | `core-api/build.gradle.kts` |
| 修改 | `infra/db/SvcCtx.kt` — 加 session + AutoCloseable |
| 修改 | `infra/db/SvcCtxFactory.kt` — 注入 MyBatisSessionFactories |
| 新增 | `infra/mybatis/MyBatisSessionFactories.kt` |
| 新增 | `infra/mybatis/MyBatisTxRunner.kt` |
| 新增 | `infra/mybatis/MybatisCrudOps.kt` |
| 新增 | `infra/mybatis/UuidTypeHandler.kt` |
| 新增 | `infra/mybatis/InstantTypeHandler.kt` |
| 新增 | `infra/mybatis/JsonbTypeHandler.kt` |
| 新增 | `modules/demo/mybatis/CoreTodoDynamicSqlSupport.kt` |
| 新增 | `modules/demo/mybatis/CoreTodoItemDynamicSqlSupport.kt` |
| 新增 | `modules/demo/repo/TodoMapper.kt` |
| 新增 | `modules/demo/repo/TodoItemMapper.kt` |
| 新增 | `modules/demo/repo/TodoRepository.kt` |
| 新增 | `modules/demo/repo/TodoItemRepository.kt` |
| 新增 | `modules/demo/service/DemoModuleService.kt` |
| 新增 | `modules/demo/service/internal/TodoEntityService.kt` |
| 新增 | `bff/graphql/customer/demo/DemoFetcher.kt` |
| 新增 | `test/.../e2e/DemoTodoE2eTest.kt` |
| 删除 | `test/.../e2e/TodoMetaJsonbE2eTest.kt` |
| 修改 | `docs/ARCHITECTURE.md` |
| 修改 | `AGENTS.md` |

---

## 关键设计决策

| # | 决策 | 原因 |
|---|------|------|
| 1 | SvcCtx 持有 `SqlSession`（不是 SqlSessionFactory） | 一个 ctx = 一个连接，生命周期清晰 |
| 2 | `SvcCtxFactory.forApp()` 根据 `isMutation` 选主/从 | Service 层面决定读写路由，不依赖 Spring TX |
| 3 | MyBatisTxRunner 独立于 jOOQ TxRunner | 两套不需要共享事务 |
| 4 | DataFetcher 用 `svcCtx.use { }` 管理 session 生命周期 | 显式，不用 ThreadLocal 魔法 |
| 5 | 不用 mybatis-spring 的 auto-configuration | 手动构建 SqlSessionFactory，完全控制 DataSource 绑定 |
| 6 | 通用操作封装到 MybatisCrudOps | 避免 copy 代码 |

## 风险点

1. ~~Spring Boot 4 兼容性~~ — **已验证，无风险**
2. **JSONB 字段** — 需自定义 TypeHandler 把 `PGobject` 转 String，已规划
3. **mybatis-spring-boot-starter 的 auto-config 可能冲突** — 如果它试图自动创建 SqlSessionFactory 绑定到 routing DataSource，需要排除。兜底：不用 starter，直接引 `mybatis` + `mybatis-spring` 手动配置
4. **SvcCtx 忘记 close** — DataFetcher 层必须用 `.use { }`。可加 lint 规则或单测检查

## 验收标准

- [ ] `./gradlew :core-api:compileKotlin` 通过
- [ ] `./gradlew :core-api:test` 通过（含新 E2E 测试）
- [ ] GraphQL Playground 可执行 demo 模块的所有 query/mutation
- [ ] Query 走 reader DataSource，Mutation 走 writer DataSource（日志验证）
- [ ] 其他模块（auth, ai, payment 等）不受影响
