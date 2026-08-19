# ifmix_server 架构文档

> 最后更新: 2026-08-18
> 状态: Jimmer → jOOQ 迁移进行中（两者共存），GraphQL DGS 已就位

## 项目概述

面向移动端（iOS/Android）的后端 API 服务：古物扫描 + AI 图像识别 + 收藏管理 + 社交登录 + IAP。

## 技术栈

| 层级 | 选型 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.10 |
| 运行时 | JDK 25 (Virtual Threads) | — |
| 框架 | Spring Boot | 4.1.0 |
| API | GraphQL (Netflix DGS) | 12.0.1 |
| SQL (新) | jOOQ | 3.21.5 |
| SQL (demo) | MyBatis Dynamic SQL | 3.5.16 / 2.0.0 |
| ORM (旧，迁移中) | Jimmer | 0.11.5 |
| 数据库 | PostgreSQL (读写分离) | — |
| 缓存 | Redis + CacheAside | — |
| 对象存储 | S3 兼容 (AWS/R2/MinIO) | — |
| AI | Spring AI 2.0 (OpenAI-compatible) | — |
| 认证 | EdDSA(Ed25519) JWT + OAuth2 | — |
| 构建 | Gradle 9.6.1 + KSP | — |
| 序列化 | Jackson 3 (tools.jackson) | — |
| GraphQL codegen | DGS codegen 8.6.0 | schema → input/payload/enum |
| jOOQ codegen | nu.studer.jooq 9.0 | 手动触发 |

## 当前模块结构

```
ifmix-server/
├── build.gradle.kts          # 版本集中管理
├── settings.gradle.kts       # include("core-api")
└── core-api/                 # 唯一的 Spring Boot Application
```


## 分层架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  BFF — GraphQL (DGS DataFetcher)                                     │
│  /customer/graphql   (DGS 12.x)                                      │
│  /webhooks/iap/*     (REST, Apple/Google 回调)                        │
│  /.well-known/jwks   (REST)                                          │
├─────────────────────────────────────────────────────────────────────┤
│  Service Layer (modules/*/service/)                                   │
│  业务编排 · TxRunner 事务 · CrudServiceOps 缓存决策                    │
├─────────────────────────────────────────────────────────────────────┤
│  Repository Layer (modules/*/repo/)                                   │
│  纯数据访问 · 注入 CrudOps (jOOQ) · 接收 RepoContext                  │
├─────────────────────────────────────────────────────────────────────┤
│  Model (entity/)                                                      │
│  Domain data class · 可加业务方法 · Jackson 直接序列化                  │
├─────────────────────────────────────────────────────────────────────┤
│  Infra (infra/)                                                      │
│  jOOQ/CacheAside/TxRunner/GraphQL scalars/Auth/RateLimit/Storage     │
├─────────────────────────────────────────────────────────────────────┤
│  Data: PostgreSQL (Writer + Reader) | Redis | S3                     │
│  Flyway V1-V24 | UUIDv7 时间有序 ID                                  │
└─────────────────────────────────────────────────────────────────────┘
```

## 目录结构

```
core-api/src/main/kotlin/com/ifmix/api/core/
├── CoreApplication.kt
├── bff/
│   ├── graphql/customer/       # DGS DataFetcher (Todo/Scan/Collection/Feedback)
│   ├── webhooks/               # Apple/Google IAP 回调 (REST)
│   └── wellknown/              # JWKS (REST)
├── entity/                      # Domain data class (jOOQ 时代)
│   ├── Todo, TodoItem, ScanRecord, ScanCollection, ...
│   ├── AppUser, AuthIdentity, AuthDeviceSecret, ...
│   └── Subscription, StoreNotification, Feedback, ...
├── modules/                    # 业务模块 (每个含 repo/ + service/)
│   ├── auth/                   # 认证 + 社交登录
│   ├── scan/                   # 古物扫描 + AI 识别
│   ├── todo/                   # Todo 清单
│   ├── iap/                    # 内购 + 订阅
│   ├── feedback/               # 反馈
│   ├── ai/                     # Agnes AI Key 管理 + ScanRunner
│   ├── storage/                # 对象存储
│   └── app/                    # AppConfig / AppInfo
├── entity/                     # Jimmer 实体 (迁移中，逐步删除)
├── infra/
│   ├── jooq/                   # CrudOps, TxRunner, AuditRecordListener, JooqConfig, InstantConverter
│   ├── mybatis/                # MyBatisSessionFactories, TypeHandlers, MyBatisTxRunner, SvcCtx extension
│   ├── jimmer/                 # ClusterRegistry, ReadWriteRouting (迁移完后删除)
│   ├── graphql/                # OperationContextProvider, scalars, ExceptionHandler, EndpointConfig
│   ├── repo/                   # BaseCrudRepository, BaseAppCrudRepository (jOOQ 基类)
│   ├── service/                # CrudServiceOps (通用 service 操作)
│   ├── http/                   # Envelope, ApiError, ErrorCode, Interceptors, RequestContext
│   ├── auth/                   # JWT 签发/验签, AuthInterceptor, Hashing
│   ├── redis/                  # CacheAside, RedisConfig
│   ├── ratelimit/              # RateLimiter (Redis 日固定窗口)
│   ├── storage/                # ObjectStorage + S3 实现
│   ├── db/                     # UuidV7, RepoContext, Ownership
│   ├── dto/                    # CursorQueryInput, Page, CommonDto
│   └── config/                 # WebConfig, JacksonConfig, TransactionConfig
└── src/generated/jooq/          # jOOQ codegen 生成代码 (提交 git，不与手写混)
    └── com/ifmix/api/core/jooq/

resources/
├── schema/common/              # GraphQL 公共 scalars
├── schema/customer/            # GraphQL Customer schema (todo/scan/collection/auth/feedback/iap/storage)
├── db/migration/               # Flyway V1-V24
├── prompts/                    # AI scan prompts
└── application.yml
```

## GraphQL 设计

### Endpoint & Schema

- **Customer**: `POST /customer/graphql` — schema 从 `schema/common/` + `schema/customer/` 合并
- **Admin**: `POST /admin/graphql` — 未来实现

### Operation 命名

```
${query|mutation}_${module}_${action}
```
示例: `query_todo_findById`, `mutation_scan_create`, `mutation_auth_loginGoogle`

### DGS Codegen

- 从 `.graphqls` 生成 Kotlin input/payload/enum types
- output types 通过 `typeMapping` 映射到 `entity/` 下的 data class（不生成）
- 生成代码包: `com.ifmix.api.core.generated`

### Update Input — set/unset 防呆

```graphql
input UpdateXxxInput {
    id: UUID!
    set: UpdateXxxSetInput    # 有值 → SET col = value
    unset: [XxxUnsetField!]   # 列出 → SET col = NULL
}
# 都没出现 → 不动 | 冲突 → unset 优先
```

### Mutation Payload

```graphql
type XxxPayload {
    success: Boolean!
    xxx: Xxx          # 客户端 select 了才回查
}
```

### DataLoader

- `caching = false`（只 batching，防 mutation 间脏读）
- 关联字段（如 Todo.items）走 DataLoader 批量加载

## 数据访问层 (jOOQ)

### 组合优于继承

- **CrudOps** (`infra/jooq/`): 无状态全局 bean，通用 CRUD 操作
  - `findById`, `findByIds`, `findByCursor`, `exists`
  - `insert` (newRecord 自动映射), `batchInsert`
  - `partialUpdate` (lambda 构建 SET)
  - `deleteById`, `deleteByIds` (软删除可选)
- **CrudServiceOps** (`infra/service/`): Service 级操作，工厂创建
  - 封装 readCache 判断 + cache evict
  - `factory.create(Type::class.java, "prefix") { it.id }`
- **BaseCrudRepository / BaseAppCrudRepository** (`infra/repo/`): Repo 基类，委托 CrudOps
- **每个 Repo 一张表**，注入 CrudOps

### 事务管理 — TxRunner

```kotlin
fun createXxx(ctx, input) = tx.withTx(ctx) { txCtx ->
    repo.insert(txCtx.repoCtx, ...)
    id
}
```
- 不用 `@Transactional`（DSLContext 动态路由，Spring 注解绑固定 DataSource）
- 传播行为: REQUIRED / REQUIRES_NEW / SUPPORTS / NOT_SUPPORTED
- 事务边界在 Service 层

### MyBatis 共存方案（demo 模块专用）

- MyBatis **手动构建** `SqlSessionFactory`（writer + reader），不走 Spring auto-config
- `SvcCtx` 扩展持有 `SqlSession?`，通过 `ctx.mapper<TodoMapper>()` 获取 Mapper
- `MyBatisTxRunner` 提供事务边界（对标 jOOQ 的 `TxRunner`）
- 适用场景：demo 模块（core_todo / core_todo_item），其他模块仍使用 jOOQ

### RepoContext

```kotlin
data class RepoContext(
    val dsl: DSLContext,
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
)
```

### OperationContext

```kotlin
data class OperationContext(
    // per-request (HTTP header): appId, installId, userId, lang, currency, country, clientPlatform, clientIp
    // per-operation (GraphQL):
    val opName: String?,
    val isMutation: Boolean,
    val readFromReplica: Boolean = !isMutation,   // mutation → 主库
    val readCache: Boolean = !isMutation,          // mutation → 跳过缓存
    val repoCtx: RepoContext,
)
```

### jOOQ Codegen

```bash
./gradlew :core-api:generateJooq   # 手动触发，连本地 DB
```
- 生成到 `src/generated/jooq/`（不与手写源码混），提交 git
- `generateSchemaSourceOnCompilation = false`
- forcedType: TIMESTAMP → Instant (InstantConverter), SMALLINT → Int (SmallintToIntConverter)
- 生成 POJO 作参考，不直接当 model

### 审计字段

`AuditRecordListener` 全局拦截 insert/update，自动填充 `created_at`/`updated_at`

### Domain Entity

- 普通 Kotlin `data class`，放 `entity/`
- 字段名与 DB column camelCase 对齐
- 时间统一 `Instant`
- 可加业务方法，Jackson 直接序列化

## 缓存分层

```
DataLoader (per-request, batching only, caching=false)
  → 关联字段 N+1 批量加载
Redis CacheAside (跨 request, TTL 分钟级)
  → query: readCache=true → 走 cache
  → mutation: readCache=false → 跳过
  → 写后: evict
DB (via jOOQ)
  → query: readFromReplica=true → 从库
  → mutation: readFromReplica=false → 主库
```

## 模块职责

| 模块 | 功能 |
|------|------|
| auth | 社交登录(Google/Apple/WeChat)、设备密钥、Refresh Token 轮转、Access Token(EdDSA)、多租户 |
| scan | 古物扫描创建(限流+预签名上传)、AI 识别(Spring AI 多模态)、Key 轮换+模型 fallback |
| todo | Todo 清单 CRUD、嵌入 items、游标分页 |
| iap | Apple/Google 购买验证、订阅管理、Webhook(JWS 验签)、Tier 映射 |
| feedback | 用户反馈 |
| ai | Agnes AI Key 管理、ScanRunner |
| storage | 预签名上传/下载 |
| app | AppConfig、AppInfo 管理 |

## 关键设计决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | GraphQL (DGS) 替代 REST | 移动端按需取字段、DataLoader 解决 N+1 |
| 2 | jOOQ 替代 Jimmer | 类型安全 SQL + 显式控制 + 无 unloaded 问题 |
| 3 | TxRunner 替代 @Transactional | 多集群动态路由、显式控制 |
| 4 | CrudOps 组合注入 | 灵活可测、不强制继承 |
| 5 | DataLoader caching=false | 防 mutation 间脏读 |
| 6 | CacheAside 显式调用 | 不用 @Cacheable 魔法 |
| 7 | Domain Entity = data class | 可加方法、Jackson 直接序列化、无框架依赖 |
| 8 | jOOQ codegen 手动执行 | 不依赖 DB 来编译 |
| 9 | set/unset Update 语义 | 防 null vs undefined 歧义 |
| 10 | AuthInterceptor 非阻塞 | 支持匿名+认证混合 |
| 11 | presignUpload 不要求登录 | 已确定 |
| 12 | 限流超限不删 Redis key | 自然 TTL 过期 |
| 13 | Operation 命名: ${q\|m}_${module}_${action} | 清晰 + Federation 友好 |
| 14 | Instant 统一时间类型 | 语义精确 |

## API 约定

- **GraphQL endpoint**: `/customer/graphql` (需 `x-app-id` header)
- **Webhook (REST)**: `POST /webhooks/iap/*` (JWS 验签)
- **JWKS (REST)**: `GET /.well-known/jwks`
- **所有响应包装**: `Envelope<T>` (`{code, msg, data}`) — REST 端点用

## 数据库约定

- **表名前缀**: `core_` (如 `core_todo`, `core_app_user`)
- **主键**: UUIDv7 (时间有序，支持游标分页)
- **游标分页**: `WHERE id < cursor ORDER BY id DESC LIMIT n+1`
- **读写分离**: ReadWriteRoutingDataSource + ClusterRegistry
- **软删除**: `deleted_at` 列 (CrudOps 可选)
- **Flyway**: V1-V24, 不可回退

### UUID 表示

- **PG/jOOQ**: 原生 UUID (16 bytes)
- **API/Redis/前端**: 22 位 Base58 (Bitcoin 字母表, URL-safe)
- **Jackson**: 全局模块自动转换 (`JacksonConfig.uuidBase58Module`)
- **工具**: `infra/codec/Base58.kt` — `uuid.toBase58()` / `str.toUuidFromBase58()`

### 枚举

**全链路 Int 透传 + 内部常量辅助。**

- **GraphQL**: input/output 全部 `Int`，schema 注释写含义
- **PG**: SMALLINT（jOOQ forcedType 自动转 Int）
- **Kotlin Model**: `val status: Int`
- **Kotlin 内部辅助**: 常量放 model class 的嵌套 object（如 `ScanRecord.Status.COMPLETED`）
- **跨模块共享**: 放 `entity/shared/`

**编码规则（新增）：** 0 保留不用，从 10 开始步长 10

**已有编码保持不变：**

| 枚举 | 值 | 编码 |
|------|-----|------|
| ScanStatus | PENDING/PROCESSING/COMPLETED/FAILED | 100/110/200/300 |
| Tier | FREE/PRO/ENTERPRISE | 100/200/300 |
| FeedbackCategory | LIKED/.../FEATURE_REQUEST/MORE_RECOMMENDATIONS | 100/200~220/300/400/410 |
| Platform | APPLE/GOOGLE | 100/200 |

## 存储上传

- objectKey 格式: `app_{appId}/i_{installId}/...` 或 `app_{appId}/u_{userId}/...`
- 强制格式校验，禁止路径遍历 (`..`)
- `presignDownload` 暂不做权限验证

## AI 扫描

- **模型 fallback**: 主模型 → fallback 列表
- **Key 重试**: 每个模型遍历所有可用 key（内层循环）
- **预扣配额**: 请求前扣减，失败归还

## 迁移状态 (2026-08-18)

## 待办

- **RepoContext 从 OperationContext 剥离**: Service 自己决定集群路由 — 见 `plans/2026-08-18-remaining-cleanup.md`
- **Admin GraphQL**: `/admin/graphql` endpoint
- **Federation 预留**: 命名已兼容

## 环境变量

| 变量 | 用途 | 默认值 |
|------|------|--------|
| `PG_WRITER_URL` | PostgreSQL 主库 | `jdbc:postgresql://localhost:5432/ifmix_core_local` |
| `PG_READER_URL` | PostgreSQL 从库 | 同主库 |
| `PG_USERNAME` / `PG_PASSWORD` | 数据库凭证 | `postgres` |
| `REDIS_URL` | Redis | `redis://localhost:6379` |
| `STORAGE_TYPE` | 存储类型 | `none` (启用: `s3`) |
| `SPRING_AI_OPENAI_API_KEY` | AI API Key | placeholder |
| `SPRING_AI_OPENAI_BASE_URL` | AI 端点 | OpenAI |
| `AUTH_ISSUER` | JWT issuer | `ifmix` |
| `PORT` | 服务端口 | `3001` |

## 构建与测试

```bash
./gradlew :core-api:compileKotlin          # 编译
./gradlew :core-api:test                   # 全部测试
./gradlew :core-api:test --tests "*.e2e.*" # E2E
./gradlew :core-api:generateJooq           # jOOQ codegen (需本地 PG)
./gradlew :core-api:bootRun                # 运行 (需 PG + Redis)
```

- **测试框架**: JUnit 5 + Mockito + assertk
- **集成测试**: Testcontainers (PostgreSQL + Redis)
- **E2E**: WebTestClient + Testcontainers


---

## Service 分层约定（ModuleService + Internal Service）

### 规则

每个模块的 service 层分为两层：

| 层 | 文件 | 职责 | 注入 |
|---|---|---|---|
| **ModuleService** | `XxxModuleService.kt` | opCtx→svcCtx 转换、开事务、委托 internal | TxRunner + Internal Services + CrudServiceOps(简单查询) |
| **Internal Service** | `XxxEntityService.kt` | 纯业务实现，接收 SvcCtx | Repo |

**约束：**
- ModuleService 是模块对外唯一入口，DataFetcher 只注入 ModuleService
- Internal Service **不注入 TxRunner**，**不构建 SvcCtx**，只接收 SvcCtx 参数
- 所有 mutation 必须在 ModuleService 通过 `tx.withTx(svc(opCtx)) { sc -> ... }` 包裹
- 简单的单行 repo 查询（如 `findById`）可保留在 ModuleService 直接调 repo/ops
- 有逻辑的操作必须委托给 Internal Service
- Internal Service 按 domain entity 拆文件（纯粹控制文件大小，不是设计分层）
- 全局事务由 DataFetcher 层的 `GlobalTxRunner` 开启，ModuleService 通过 `opCtx.globalTxDsl` 检测并复用

### 目录结构

```
modules/todo/
├── repo/
│   ├── TodoRepository.kt
│   └── TodoItemRepository.kt
└── service/
    ├── TodoModuleService.kt           # 对外入口：事务 + 委托
    ├── TodoEntityService.kt         # Todo 表的实现
    └── TodoItemEntityService.kt     # TodoItem 表的实现（文件不大可合并）
```

简单模块（如 feedback）：
```
modules/feedback/
├── repo/FeedbackRepository.kt
└── service/
    ├── FeedbackModuleService.kt
    └── FeedbackEntityService.kt
```

### 完整 Demo — Todo 模块

```kotlin
// ======================== ModuleService ========================

@Service
class TodoModuleService(
    private val todoInternal: TodoEntityService,
    private val todoItemInternal: TodoItemEntityService,
    private val repo: TodoRepository,
    private val tx: TxRunner,
    factory: CrudServiceOpsFactory,
) {
    private val ops = factory.create(Todo::class.java, "todo") { it.id }

    private fun svc(opCtx: OperationContext) = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl,
    )

    // --- 简单查询：保留在 facade ---
    fun findById(opCtx: OperationContext, id: UUID): Todo? =
        ops.findById(svc(opCtx), id, repo::findById)

    fun findByIds(opCtx: OperationContext, ids: List<UUID>): List<Todo> =
        ops.findByIds(svc(opCtx), ids, repo::findByIds)

    fun findByCursor(opCtx: OperationContext, input: TodoQueryInput): Page<Todo> =
        ops.findByCursor(svc(opCtx), input.cursor, input.limit, repo::findByCursor)

    // --- Mutation：开事务 + 走 internal ---
    fun createTodo(opCtx: OperationContext, input: CreateTodoInput): UUID =
        tx.withTx(svc(opCtx)) { sc -> todoInternal.create(sc, input) }

    fun updateTodo(opCtx: OperationContext, input: UpdateTodoInput): Boolean =
        tx.withTx(svc(opCtx)) { sc -> todoInternal.update(sc, input) }

    fun deleteTodo(opCtx: OperationContext, id: UUID): Boolean =
        tx.withTx(svc(opCtx)) { sc -> todoInternal.delete(sc, id) }

    fun updateItems(opCtx: OperationContext, input: UpdateTodoItemsMutationInput) =
        tx.withTx(svc(opCtx)) { sc -> todoItemInternal.update(sc, input) }

    // --- DataLoader 用 ---
    fun findItemsByTodoIds(opCtx: OperationContext, todoIds: Collection<UUID>): List<TodoItem> =
        todoItemInternal.findByTodoIds(svc(opCtx), todoIds)
}

// ======================== Internal: TodoEntityService ========================

@Component
class TodoEntityService(
    private val repo: TodoRepository,
    private val itemRepo: TodoItemRepository,
    // 不注入 TxRunner
) {
    fun create(sc: SvcCtx, input: CreateTodoInput): UUID {
        val appId = sc.mustGetAppId()
        val now = Instant.now()
        val id = UuidV7.generate()
        repo.insert(sc, Todo(
            id = id, appId = appId,
            installId = sc.installId, userId = sc.userId,
            title = input.title, done = input.done ?: false,
            createdAt = now,
        ))
        input.items?.takeIf { it.isNotEmpty() }?.let { items ->
            itemRepo.batchInsert(sc, items.map { i ->
                TodoItem(
                    id = UuidV7.generate(), appId = appId, todoId = id,
                    content = i.content, done = i.done ?: false, createdAt = now,
                )
            })
        }
        return id
    }

    fun update(sc: SvcCtx, input: UpdateTodoInput): Boolean {
        val appId = sc.mustGetAppId()
        if (!repo.exists(sc, appId, input.id)) throw ApiError(ErrorCode.NOT_FOUND)
        repo.partialUpdate(sc, appId, input)
        return true
    }

    fun delete(sc: SvcCtx, id: UUID): Boolean {
        val appId = sc.mustGetAppId()
        return repo.deleteById(sc, appId, id)
    }
}

// ======================== Internal: TodoItemEntityService ========================

@Component
class TodoItemEntityService(
    private val repo: TodoItemRepository,
    // 不注入 TxRunner
) {
    fun findByTodoIds(sc: SvcCtx, todoIds: Collection<UUID>): List<TodoItem> =
        repo.findByTodoIds(sc, todoIds)

    fun update(sc: SvcCtx, input: UpdateTodoItemsMutationInput) {
        val appId = sc.mustGetAppId()
        val now = Instant.now()
        input.create?.takeIf { it.isNotEmpty() }?.let { creates ->
            repo.batchInsert(sc, creates.map { c ->
                TodoItem(
                    id = UuidV7.generate(), appId = appId, todoId = c.todoId,
                    content = c.content, done = c.done ?: false, createdAt = now,
                )
            })
        }
        input.update?.takeIf { it.isNotEmpty() }?.let { updates ->
            repo.batchUpdate(sc, appId, updates)
        }
        input.delete?.takeIf { it.isNotEmpty() }?.let { ids ->
            repo.deleteByIds(sc, appId, ids)
        }
    }
}
```

### DataFetcher（调用方）

```kotlin
@DgsComponent
class TodoFetcher(
    private val todoService: TodoModuleService,  // 只注入 Facade
    private val ctxProvider: OperationContextProvider,
) {
    @DgsQuery(field = "query_todo_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val opCtx = ctxProvider.fromDfe(dfe)
        return todoService.findById(opCtx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @DgsMutation(field = "mutation_todo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val opCtx = ctxProvider.fromDfe(dfe)
        val id = todoService.createTodo(opCtx, input)
        val todo = todoService.findById(opCtx, id)!!
        return CreateTodoPayload(todo = todo)
    }
}
```

### 跨模块事务（DataFetcher 层）

```kotlin
@DgsMutation(field = "mutation_xxx_complexOperation")
fun complexOp(dfe: DgsDataFetchingEnvironment, ...): ... {
    val opCtx = ctxProvider.fromDfe(dfe)
    return globalTx.withTx(opCtx) { txOpCtx ->
        // 各 ModuleService 检测 txOpCtx.globalTxDsl != null → 复用事务
        val id = todoService.createTodo(txOpCtx, ...)
        scanService.bindToTodo(txOpCtx, id)
        ...
    }
}
```
