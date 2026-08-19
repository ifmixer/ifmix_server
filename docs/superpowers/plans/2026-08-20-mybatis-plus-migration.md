# 计划: Demo 模块使用 MyBatis Plus

## 背景

- 动机: 团队熟悉 MyBatis，MP 开箱即用 CRUD + 插件生态
- 范围: demo 模块（Todo / TodoItem），后续可替换 jOOQ
- 不使用代码生成，entity 手写 + 注解驱动
- 不使用 ThreadLocal / TenantLineInnerInterceptor，appId 显式传递

## 版本

```kotlin
implementation("com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17")
```

不需要额外引入 mybatis、mybatis-spring、mybatis-dynamic-sql — starter 全包。

---

## 架构设计

### 调用链路

```
DataFetcher
  → opCtx = OperationContextProvider.fromDfe(dfe)   // isMutation=true/false
  → svcCtxFactory.forApp(opCtx).use { ctx ->
        demoService.xxx(ctx, ...)
    }

Service
  → repo.findById(ctx, appId, id)

Repository
  → ops.findById(ctx, appId, id)           // 通用 CRUD 委托 ops
  → ctx.mapper<TodoMapper>().selectList(wrapper)  // 业务特有查询手写
```

### 读写分离

与 Dynamic SQL 版相同：
- `SvcCtxFactory.forApp(opCtx)` 根据 `isMutation` 选 writer/reader SqlSessionFactory → openSession
- `ctx.mapper<TodoMapper>()` 从当前 session 获取 mapper
- 不用 `@Transactional`，不用 ThreadLocal

### Entity（手写，注解驱动，就是领域模型）

```kotlin
@TableName("core_todo")
data class Todo(
    @TableId(type = IdType.ASSIGN_UUID)
    val id: UUID,
    val appId: UUID,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val title: String,
    val done: Boolean = false,
    val note: String? = null,
    @TableField(typeHandler = MetaTypeHandler::class)
    val meta: Meta? = null,
    @TableField(fill = FieldFill.INSERT)
    val createdAt: Instant,
    @TableField(fill = FieldFill.INSERT_UPDATE)
    val updatedAt: Instant,
    @TableLogic(value = "NULL", delval = "NOW()")
    val deletedAt: Instant? = null,
)
```

- `@TableLogic` — MP 自动在所有查询追加 `AND deleted_at IS NULL`，删除时自动 `SET deleted_at = NOW()`
- `@TableField(fill = ...)` — 配合 `MetaObjectHandler` 自动填充时间
- `@TableField(typeHandler = ...)` — JSONB ↔ Meta 对象
- **不需要代码生成，加字段就是 entity 加一行 val**

### Mapper（一行）

```kotlin
@Mapper
interface TodoMapper : BaseMapper<Todo>

@Mapper
interface TodoItemMapper : BaseMapper<TodoItem>
```

`BaseMapper<T>` 自带 20+ 方法：`selectById`, `selectBatchIds`, `selectList`, `selectCount`, `insert`, `updateById`, `update`, `deleteById`, `delete` 等。

### CrudOps（通用 CRUD + 显式 appId）

```kotlin
class MybatisCrudOps<T : Any>(
    private val entityClass: Class<T>,
    private val appIdProp: KProperty1<T, UUID?>,
    private val idProp: KProperty1<T, UUID>,
    private val mapperFn: (SvcCtx) -> BaseMapper<T>,
) {
    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): T?
    fun findByIds(ctx: SvcCtx, appIdVal: UUID, ids: Collection<UUID>): List<T>
    fun findByCursor(ctx: SvcCtx, appIdVal: UUID, cursor: UUID?, limit: Int): List<T>
    fun insert(ctx: SvcCtx, entity: T)
    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): Boolean
    fun exists(ctx: SvcCtx, appIdVal: UUID, idVal: UUID): Boolean
}
```

- 只需 3 个关键参数：entityClass + appIdProp + mapperFn
- 软删除由 `@TableLogic` 自动处理，ops 里不用管
- appId 条件通过 `KtQueryWrapper` 的 `eq(appIdProp, appIdVal)` 显式加

### Repository

```kotlin
@Repository
class TodoRepository {
    private val ops = MybatisCrudOps(
        entityClass = Todo::class.java,
        appIdProp = Todo::appId,
        idProp = Todo::id,
        mapperFn = { ctx -> ctx.mapper<TodoMapper>() },
    )

    // 通用 — 一行委托
    fun findById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.findById(ctx, appIdVal, idVal)
    fun findByIds(ctx: SvcCtx, appIdVal: UUID, ids: Collection<UUID>) = ops.findByIds(ctx, appIdVal, ids)
    fun findByCursor(ctx: SvcCtx, appIdVal: UUID, cursor: UUID?, limit: Int) = ops.findByCursor(ctx, appIdVal, cursor, limit)
    fun insert(ctx: SvcCtx, entity: Todo) = ops.insert(ctx, entity)
    fun deleteById(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.deleteById(ctx, appIdVal, idVal)
    fun exists(ctx: SvcCtx, appIdVal: UUID, idVal: UUID) = ops.exists(ctx, appIdVal, idVal)

    // 业务特有 — 手写 Wrapper
    fun update(ctx: SvcCtx, appIdVal: UUID, input: UpdateTodoInput) {
        val wrapper = KtUpdateWrapper(Todo::class.java)
            .eq(Todo::id, input.id)
            .eq(Todo::appId, appIdVal)
        input.set?.title?.let { wrapper.set(Todo::title, it) }
        input.set?.done?.let { wrapper.set(Todo::done, it) }
        input.set?.note?.let { wrapper.set(Todo::note, it) }
        if (input.unset?.contains(TodoUnsetField.NOTE) == true) wrapper.set(Todo::note, null)
        ctx.mapper<TodoMapper>().update(null, wrapper)
    }
}
```

### MyBatisSessionFactories（双 writer/reader）

```kotlin
@Component
class MyBatisSessionFactories(private val registry: DataSourceRegistry) {
    val writer by lazy { buildFactory(registry.writerDataSource) }
    val reader by lazy { buildFactory(registry.readerDataSource) }

    private fun buildFactory(ds: DataSource): SqlSessionFactory {
        val factory = MybatisSqlSessionFactoryBean()  // MP 增强版
        factory.setDataSource(ds)
        factory.setPlugins(arrayOf(mybatisPlusInterceptor()))
        factory.setGlobalConfig(globalConfig())
        factory.setConfiguration(MybatisConfiguration().apply {
            isMapUnderscoreToCamelCase = true
        })
        return factory.getObject()!!
    }
}
```

### MetaObjectHandler（自动填充 createdAt/updatedAt）

```kotlin
@Component
class TimeAutoFillHandler : MetaObjectHandler {
    override fun insertFill(metaObject: MetaObject) {
        val now = Instant.now()
        setFieldValByName("createdAt", now, metaObject)
        setFieldValByName("updatedAt", now, metaObject)
    }
    override fun updateFill(metaObject: MetaObject) {
        setFieldValByName("updatedAt", Instant.now(), metaObject)
    }
}
```

---

## 实施步骤

### Phase 1: 依赖 + 基础设施

1. `build.gradle.kts`: 替换 mybatis + dynamic-sql 为 `mybatis-plus-spring-boot4-starter:3.5.17`
2. 排除 MP auto-config: `@SpringBootApplication(exclude = [MybatisPlusAutoConfiguration::class])`
3. `infra/mybatis/MyBatisSessionFactories.kt`: 改用 `MybatisSqlSessionFactoryBean`
4. `infra/mybatis/MybatisCrudOps.kt`: 重写为 BaseMapper + KProperty 版本
5. `infra/mybatis/TimeAutoFillHandler.kt`: createdAt/updatedAt 自动填充
6. 保留: `MetaTypeHandler.kt`, `UuidTypeHandler.kt`, `InstantTypeHandler.kt`
7. `MyBatisTxRunner.kt`: 不变

### Phase 2: Entity

8. `entity/demo/Todo.kt`: 加 MP 注解（`@TableName`, `@TableId`, `@TableLogic`, `@TableField`）
9. `entity/demo/TodoItem.kt`: 同上
10. `entity/demo/Meta.kt`: 不变

### Phase 3: Mapper + Repository

11. `modules/demo/repo/TodoMapper.kt`: `interface TodoMapper : BaseMapper<Todo>`
12. `modules/demo/repo/TodoItemMapper.kt`: 同上
13. `modules/demo/repo/TodoRepository.kt`: ops + 业务特有方法
14. `modules/demo/repo/TodoItemRepository.kt`: 同上
15. `modules/demo/DemoMyBatisConfig.kt`: 注册 Mapper（或靠 `@MapperScan`）

### Phase 4: Service + DataFetcher

16. `modules/demo/service/DemoModuleService.kt`: 不变（调 repository）
17. `bff/graphql/customer/demo/DemoFetcher.kt`: 不变

### Phase 5: 清理

18. 删除 `src/generated/mybatis/` 整个目录
19. 删除 `mybatis-generator-config.xml`
20. 删除 `PgTypeResolver.kt`
21. 删除 `JsonbTypeHandler.kt`（通用 String jsonb 不需要了，只保留 MetaTypeHandler）
22. 从 `build.gradle.kts` 删除 `mybatisGenerator` configuration + `generateMybatis` task + `compileOnly(mybatis-generator-core)`
23. 删除 `src/generated/mybatis` 的 sourceSets 配置

### Phase 6: 验证

24. `./gradlew :core-api:compileKotlin`
25. `./gradlew :core-api:test`

---

## 文件清单（最终状态）

| 文件 | 说明 |
|------|------|
| `build.gradle.kts` | mybatis-plus-spring-boot4-starter:3.5.17 |
| `entity/demo/Todo.kt` | 手写 data class + MP 注解 |
| `entity/demo/TodoItem.kt` | 同上 |
| `entity/demo/Meta.kt` | 值对象 |
| `infra/mybatis/MyBatisSessionFactories.kt` | 双 MybatisSqlSessionFactoryBean |
| `infra/mybatis/MyBatisTxRunner.kt` | 事务管理（不变） |
| `infra/mybatis/MybatisCrudOps.kt` | 通用 CRUD（BaseMapper + KProperty） |
| `infra/mybatis/MetaTypeHandler.kt` | JSONB ↔ Meta |
| `infra/mybatis/UuidTypeHandler.kt` | UUID ↔ PG uuid |
| `infra/mybatis/InstantTypeHandler.kt` | Instant ↔ PG timestamptz |
| `infra/mybatis/TimeAutoFillHandler.kt` | 自动填充时间字段 |
| `modules/demo/DemoMyBatisConfig.kt` | 注册 Mapper |
| `modules/demo/repo/TodoMapper.kt` | 一行 |
| `modules/demo/repo/TodoItemMapper.kt` | 一行 |
| `modules/demo/repo/TodoRepository.kt` | ops + 业务查询 |
| `modules/demo/repo/TodoItemRepository.kt` | ops + 业务查询 |
| `modules/demo/service/DemoModuleService.kt` | 业务编排 |
| `bff/graphql/customer/demo/DemoFetcher.kt` | GraphQL 入口 |

**删除：**
- `src/generated/mybatis/` 整个目录
- `mybatis-generator-config.xml`
- `PgTypeResolver.kt`
- `JsonbTypeHandler.kt`
- `build.gradle.kts` 中 mybatisGenerator 相关配置

---

## 关键设计决策

| # | 决策 | 原因 |
|---|------|------|
| 1 | 不用 TenantLineInnerInterceptor | 不用 ThreadLocal，appId 显式传递 |
| 2 | 不用代码生成 | Entity 手写 + 注解即可，加字段改一行 |
| 3 | 保留 Repository 层 | 封装 ops + 业务特有查询（游标分页、partial update） |
| 4 | CrudOps 只需 entityClass + appIdProp + mapperFn | BaseMapper 统一接口，不需要传函数引用 |
| 5 | @TableLogic 自动软删除 | 不用手写 deletedAt.isNull() |
| 6 | MetaObjectHandler 自动填充时间 | 不用手写 set(updatedAt) |
| 7 | 排除 MP auto-config | 手动构建双 SqlSessionFactory 实现读写分离 |
| 8 | Entity 既是领域模型也是 ORM 映射 | demo 模块不需要分离，减少转换代码 |

## 验收标准

- [ ] `./gradlew :core-api:compileKotlin` 通过
- [ ] `./gradlew :core-api:test` 通过
- [ ] GraphQL 所有 demo query/mutation 可执行
- [ ] Query 走 reader，Mutation 走 writer（日志验证）
- [ ] 其他模块（jOOQ）不受影响
