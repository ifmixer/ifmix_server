# 设计文档：ifmix_server 迁移到 Jimmer + PostgreSQL

## 概述

将 ifmix_server 的 ORM 层从 Spring Data MongoDB (MongoTemplate + Konvert) 迁移到 Jimmer + PostgreSQL，支持读写分离和多集群路由。

### 动机

1. 动态查询自动生成，无需每个场景手写 DTO（类似 drizzle RQB）
2. Jimmer DTO 语言生成任意前端需要的形状，替代 Konvert mapper
3. 统一实体图，避免 Java/Kotlin 项目中字段漂移和重复 copy
4. 保存任意形状（save 命令），多关联关系的 CRUD API 轻松生成
5. 显式 save，无 dirty checking 误写风险，性能优于 Hibernate/MikroORM
6. 对 Spring + Kotlin 支持良好

### 迁移策略

分阶段迁移：
1. 第一阶段：搭建 Jimmer 基础设施 + 迁移 todo、feedback 模块验证
2. 第二阶段：迁移 collection + antique（验证多表关联）
3. 第三阶段：迁移 auth、iap、appconfig 等复杂模块
4. 最终：移除 MongoDB 依赖

MongoDB 和 Jimmer 在过渡期共存，未迁移模块继续用 MongoTemplate。

---

## 目录结构

```
core-api/src/main/kotlin/com/ifmix/api/core/
├── common/
│   ├── jimmer/                          # 统一 DAO 层
│   │   ├── entity/                      # @Entity interface（按领域分包）
│   │   │   ├── auth/                    # AuthIdentity, AppUser, AuthProviderIdentity, ...
│   │   │   ├── antique/                 # ScanRecord
│   │   │   ├── collection/              # Collection, CollectionItem
│   │   │   ├── todo/                    # Todo, TodoItem
│   │   │   ├── feedback/                # Feedback
│   │   │   ├── iap/                     # Subscription, StoreNotification
│   │   │   ├── appconfig/               # AppConfig, AppInfo
│   │   │   └── ai/                      # AgnesApiKey
│   │   ├── repository/                  # 各领域 Repository
│   │   │   ├── auth/
│   │   │   ├── todo/
│   │   │   └── ...
│   │   ├── dto/                         # Jimmer .dto 文件
│   │   │   ├── todo/
│   │   │   └── ...
│   │   ├── filter/                      # 全局过滤器（AppScopedFilter）
│   │   ├── base/                        # BaseCrudRepository, BaseAppCrudRepository,
│   │   │                                # BaseCrudService, BaseAppCrudService, 游标分页
│   │   └── cluster/                     # ClusterRegistry, ClusterConfig, ClusterInitializer
│   ├── db/                              # 保留：MongoDB（未迁移模块）
│   └── ...
├── modules/                             # Service + 领域逻辑
│   ├── todo/
│   │   ├── TodoService.kt
│   │   └── TodoConfig.kt
│   └── ...
└── bff/                                 # Controller 层不变
```

**原则：**
- `common/jimmer/entity/` 存放完整实体图，KSP 一次性处理所有关联
- `common/jimmer/dto/` 的 `.dto` 文件由 Jimmer KSP 自动生成 DTO 类（替代 Konvert）
- Entity + Repository 集中在 common/jimmer（DAO 层），模块只有 Service + Controller

---

## 多集群路由与读写分离

### 架构

每个集群（由 appId 路由）持有一对 DataSource（writer + reader），通过 `AbstractRoutingDataSource` 做读写分离。上层 `ClusterRegistry` 按 appId 返回对应集群的 `JSqlClient`。

```
Request → RequestContext(appId)
        → ClusterRegistry.forAppId(appId) → JSqlClient(writer/reader)
        → BaseCrudRepository → Jimmer 查询
```

### 配置格式

```yaml
app:
  clusters:
    default:
      writer:
        jdbc-url: jdbc:postgresql://localhost:5432/ifmix_core_local
        username: postgres
        password: postgres
      reader:
        jdbc-url: jdbc:postgresql://localhost:5432/ifmix_core_local
        username: postgres
        password: postgres

  cluster-routing:
    mappings:
      # appId → cluster name；未列出的走 default
      # "some-app-id": "cluster-us"
```

### 核心组件

| 组件 | 职责 |
|---|---|
| `ClusterConfig` | `@ConfigurationProperties`，解析 yml 为集群连接配置 + 路由映射表 |
| `ClusterRegistry` | 启动时为每个集群创建 writerDs + readerDs + ReadWriteRoutingDataSource + JSqlClient |
| `ReadWriteRoutingDataSource` | 继承 `AbstractRoutingDataSource`，`readOnly=true` 路由到 reader，否则 writer |
| `ClusterInitializer` | `ApplicationRunner`，启动时对每个集群的 writer DataSource 执行 Flyway migrate |

### 读写分离策略

- **默认（B 模式）：** Service 方法标注 `@Transactional(readOnly = true)` 自动走 reader
- **显式控制（A 模式）：** `BaseCrudRepository` 支持 `forceWriter` 参数，写后立即读等场景可强制走 writer
- 事务内强制走 writer

---

## Jimmer Entity 定义

### 命名规范

- 表名：单数 + snake_case（如 `todo`, `todo_item`, `scan_record`）
- Entity 接口：PascalCase（如 `Todo`, `TodoItem`, `ScanRecord`）

### Entity 定义风格

使用 Jimmer 推荐的 interface 风格 + `@Entity` 注解，KSP 生成不可变实现类、Draft、Fetcher。

### 超类型接口

```kotlin
// 带 appId 的多租户实体
@MappedSuperclass
interface AppScopedProps {
    val appId: UUID
}
```

所有带 `appId` 的实体实现 `AppScopedProps`，全局 `AppScopedFilter` 自动注入 `WHERE app_id = ?`。

### 软删

使用 Jimmer 内建的 `@LogicalDeleted("now")` 注解：
- 查询自动加 `WHERE deleted_at IS NULL`
- 删除变为 `UPDATE SET deleted_at = now()`
- 不需要自定义过滤器

### Entity 示例

```kotlin
@Entity
@Table(name = "todo")
interface Todo : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID

    override val appId: UUID

    val title: String

    val done: Boolean

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant
    val updatedAt: Instant

    @OneToMany(mappedBy = "todo")
    val items: List<TodoItem>
}

@Entity
@Table(name = "todo_item")
interface TodoItem : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID

    override val appId: UUID

    @ManyToOne
    @JoinColumn(name = "todo_id")
    val todo: Todo

    val content: String

    val done: Boolean

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant
    val updatedAt: Instant
}
```

### 全局过滤器

```kotlin
@Component
class AppScopedFilter(
    private val requestContextHolder: RequestContextHolder
) : KFilter<AppScopedProps> {

    override fun filter(args: KFilterArgs<AppScopedProps>) {
        val ctx = requestContextHolder.current()
        args.apply {
            where(table.appId eq UUID.fromString(ctx.appId))
        }
    }
}
```

### DTO 文件示例

```
// common/jimmer/dto/todo/Todo.dto
TodoView {
    id
    title
    done
    createdAt
    items {
        id
        content
        done
    }
}

TodoCreateInput {
    title
    done
}

TodoUpdateInput {
    title
    done
}
```

---

## BaseCrudRepository 与 BaseCrudService

### Repository 层

两层抽象：

**BaseCrudRepository<E>** — 纯 CRUD + 游标分页 + 集群路由，不假设 appId：

```kotlin
abstract class BaseCrudRepository<E : Any>(
    private val clusterRegistry: ClusterRegistry,
    private val entityType: KClass<E>,
) {
    protected fun sql(ctx: RequestContext): JSqlClient =
        clusterRegistry.forAppId(ctx.appId)

    fun findById(ctx: RequestContext, id: UUID): E? =
        sql(ctx).findById(entityType, id)

    fun <V : View<E>> findById(ctx: RequestContext, id: UUID, viewType: KClass<V>): V? =
        sql(ctx).findById(viewType, id)

    fun save(ctx: RequestContext, entity: E): E =
        sql(ctx).save(entity).modifiedEntity

    fun save(ctx: RequestContext, input: Input<E>): E =
        sql(ctx).save(input).modifiedEntity

    fun deleteById(ctx: RequestContext, id: UUID) {
        sql(ctx).deleteById(entityType, id)
    }

    fun findByCursor(
        ctx: RequestContext,
        input: CursorQueryInput = CursorQueryInput(),
        block: (MutableRootQuery<E>.() -> Unit)? = null,
    ): Page<E> { /* keyset 游标分页实现 */ }

    fun <V : View<E>> findByCursor(
        ctx: RequestContext,
        input: CursorQueryInput = CursorQueryInput(),
        viewType: KClass<V>,
        block: (MutableRootQuery<E>.() -> Unit)? = null,
    ): Page<V> { /* 带 View 投影的游标分页 */ }
}
```

**BaseAppCrudRepository<E : AppScopedProps>** — 面向多租户实体：

```kotlin
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    clusterRegistry: ClusterRegistry,
    entityType: KClass<E>,
) : BaseCrudRepository<E>(clusterRegistry, entityType)
```

AppScopedFilter 全局自动注入，Repository 本身不需要额外逻辑，拆出来是为了类型约束和未来扩展。

### Service 层

同样两层：

**BaseCrudService<E, ID>** — 委托 Repository，标注事务：

```kotlin
open class BaseCrudService<E : Any>(
    protected val repo: BaseCrudRepository<E>,
) {
    @Transactional(readOnly = true)
    open fun findById(ctx: RequestContext, id: UUID): E? =
        repo.findById(ctx, id)

    @Transactional(readOnly = true)
    open fun <V : View<E>> findById(ctx: RequestContext, id: UUID, viewType: KClass<V>): V? =
        repo.findById(ctx, id, viewType)

    @Transactional(readOnly = true)
    open fun findByCursor(ctx: RequestContext, input: CursorQueryInput = CursorQueryInput()): Page<E> =
        repo.findByCursor(ctx, input)

    @Transactional
    open fun create(ctx: RequestContext, input: Input<E>): E =
        repo.save(ctx, input)

    @Transactional
    open fun update(ctx: RequestContext, input: Input<E>): E =
        repo.save(ctx, input)

    @Transactional
    open fun deleteById(ctx: RequestContext, id: UUID) =
        repo.deleteById(ctx, id)
}
```

**BaseAppCrudService<E : AppScopedProps>** — 面向多租户：

```kotlin
open class BaseAppCrudService<E : AppScopedProps>(
    repo: BaseAppCrudRepository<E>,
) : BaseCrudService<E>(repo)
```

各模块 Service 继承后只需添加领域特有方法，标准 CRUD 零代码。

---

## Flyway 数据库迁移

### 目录

```
core-api/src/main/resources/
└── db/
    └── migration/
        ├── V1__baseline.sql     # 从 drizzle 项目导出的全量 DDL（17 张表）
        └── V2__xxx.sql          # 后续增量变更
```

### 执行策略

- 启动时 `ClusterInitializer` 对每个集群的 writer DataSource 执行 Flyway migrate
- 所有集群共享同一套 migration 文件（schema 一致）
- `baselineOnMigrate = true`：已有数据库跳过基线，新库从头执行
- 表名规范：单数 + snake_case

### 基线处理

- `V1__baseline.sql`：将现有 drizzle 的 17 张表 DDL（含索引、约束）导出
- 已有 `ifmix_core` 库：用 Flyway baseline 标记 V1 已执行
- 新建 `ifmix_core_local`：从 V1 正常建表

---

## Service 层与 BFF 层变化

### Service 层

```kotlin
@Service
class TodoService(
    todoRepo: TodoRepository,
) : BaseAppCrudService<Todo>(todoRepo) {

    // 标准 CRUD 由 BaseAppCrudService 提供，这里只写领域特有方法

    @Transactional(readOnly = true)
    fun searchByTitle(ctx: RequestContext, keyword: String, input: CursorQueryInput): Page<TodoView> =
        (repo as TodoRepository).searchByTitle(ctx, keyword, input)
}
```

### BFF Controller 层

基本无变化，入参类型从手写 DTO 变为 Jimmer 生成的 Input/View 类型。

### RequestContext 调整

- 移除 `readPreference: ReadPreference` 字段（MongoDB 特有）
- 保留：appId, installId, userId, lang, currency, country, clientPlatform

---

## 依赖变更

### 新增

- `org.babyfish.jimmer:jimmer-spring-boot-starter` — Jimmer 核心
- `org.babyfish.jimmer:jimmer-sql-kotlin` — Kotlin DSL
- KSP processor: `org.babyfish.jimmer:jimmer-ksp`
- `org.postgresql:postgresql` — PG JDBC 驱动
- `com.zaxxer:HikariCP` — 连接池（Spring Boot 默认自带）
- `org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-postgresql`
- `org.testcontainers:postgresql` — 测试容器（替代 testcontainers:mongodb）

### 最终移除（全量迁移完成后）

- `spring-boot-starter-data-mongodb`
- `io.mcarle:konvert-api` + `io.mcarle:konvert`（KSP）
- `org.testcontainers:mongodb`

### 过渡期保留

- `spring-boot-starter-data-mongodb`（未迁移模块继续使用）
- `io.mcarle:konvert`（未迁移模块的 DTO 映射）

---

## 第一阶段实施范围

1. **基础设施搭建：**
   - Gradle 引入 Jimmer + PG + Flyway 依赖
   - 实现 ClusterConfig, ClusterRegistry, ReadWriteRoutingDataSource, ClusterInitializer
   - 实现 BaseCrudRepository, BaseAppCrudRepository, BaseCrudService, BaseAppCrudService
   - 实现 AppScopedFilter
   - 创建 V1__baseline.sql
   - 配置 application.yml / application-local.yml 的集群连接

2. **模块迁移（todo + feedback）：**
   - 定义 Entity：Todo, TodoItem, Feedback
   - 编写 .dto 文件
   - 实现 TodoRepository, FeedbackRepository
   - 重写 TodoService, FeedbackService 继承 BaseAppCrudService
   - Controller 层适配新 DTO 类型
   - 编写集成测试（Testcontainers + PostgreSQL）

3. **验证：**
   - 读写分离路由正确性
   - AppScopedFilter 租户隔离
   - 软删行为
   - 游标分页
   - MongoDB 模块不受影响
