# ifmix_server 架构文档

> 最后更新: 2026-08-20
> 状态: Jimmer 迁移完成，分层重构完成（ModuleCtx + AggHandler + GlobalTx）

## 项目概述

面向移动端（iOS/Android）的后端 API 服务：古物扫描 + AI 图像识别 + 收藏管理 + 社交登录 + IAP。

## 技术栈

| 层级 | 选型 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.10 |
| 运行时 | JDK 25 (Virtual Threads) | — |
| 框架 | Spring Boot | 4.1.0 |
| API | GraphQL (Netflix DGS) | 12.0.1 |
| ORM | Jimmer (KSP) | 0.11.5 |
| 数据库 | PostgreSQL (读写分离) | — |
| 缓存 | Redis + CacheAside | — |
| 对象存储 | S3 兼容 (AWS/R2/MinIO) | — |
| AI | Spring AI 2.0 (OpenAI-compatible) | — |
| 认证 | EdDSA(Ed25519) JWT + OAuth2 | — |
| 构建 | Gradle 9.6.1 + KSP | — |
| 序列化 | Jackson 3 (tools.jackson) | — |
| GraphQL codegen | DGS codegen 8.6.0 | schema → input/payload/enum |

## 分层架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  BFF — GraphQL (DGS DataFetcher) + REST                              │
│  POST /customer/graphql   (主 API)                                   │
│  POST /webhooks/iap/*     (Apple/Google 回调)                        │
│  GET  /.well-known/jwks                                              │
├─────────────────────────────────────────────────────────────────────┤
│  Facade Layer (modules/*/XxxFacade.kt)                               │
│  构造 ModuleCtx + 简单转发 · 不含事务逻辑                           │
├─────────────────────────────────────────────────────────────────────┤
│  Handler Layer (modules/*/handler/XxxAggHandler.kt)                  │
│  纯业务逻辑 · 接收 ModuleCtx · 不注入 TxRunner                      │
├─────────────────────────────────────────────────────────────────────┤
│  Repository Layer (modules/*/repo/)                                   │
│  纯数据访问 · CrudRepoTemplate 组合 · 接收 ModuleCtx                │
├─────────────────────────────────────────────────────────────────────┤
│  Model (entity/)                                                      │
│  Jimmer interface + @MappedSuperclass · KSP 生成扩展属性 · 直出 GQL  │
├─────────────────────────────────────────────────────────────────────┤
│  Infra (infra/)                                                      │
│  Jimmer/CacheAside/TxRunner/GlobalTxRunner/Auth/RateLimit/Storage    │
├─────────────────────────────────────────────────────────────────────┤
│  Data: PostgreSQL (Writer + Reader) | Redis | S3                     │
│  Flyway V1-V24 | UUIDv7 时间有序 ID                                  │
└─────────────────────────────────────────────────────────────────────┘
```

### 分层约束

```
DataFetcher  →  只注入 Facade + GlobalTxRunner + OperationContextProvider
Facade       →  只注入 AggHandler + ModuleCtxFactory
Handler      →  只注入 Repo + CacheAside + 同模块 infra service
Repo         →  持有 CrudRepoTemplate（companion object）
```

**禁止跨级：**
- DataFetcher 不能 import handler/repo 包
- Facade 不能 import repo 包
- Handler 不能 import facade 包
- 跨模块调用：Facade 可注入其他模块的 Facade

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
    val globalTxSql: KSqlClient? = null,   // GlobalTxRunner 设置
    val inGlobalTx: Boolean = false,
)
```

### ModuleCtx

```kotlin
data class ModuleCtx(
    val op: OperationContext,
    val sql: KSqlClient,           // 路由后的实例（writer/reader/globalTx）
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) {
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
    val readCache get() = op.readCache
}
```

参数名缩写：`mc`（ModuleCtx）

### ModuleCtxFactory

```kotlin
@Component
class ModuleCtxFactory(private val router: ClusterRouter) {
    fun forApp(opCtx: OperationContext): ModuleCtx { ... }
    fun forTenant(opCtx: OperationContext, tenantId: UUID): ModuleCtx { ... }

    private fun chooseSql(opCtx, pair): KSqlClient = when {
        opCtx.globalTxSql != null -> opCtx.globalTxSql  // 全局事务内，复用
        opCtx.preferReader -> pair.reader
        else -> pair.writer
    }
}
```

## 事务管理

### 两层事务

| 层 | Runner | 位置 | 职责 |
|---|---|---|---|
| **GlobalTx** | `GlobalTxRunner` | DataFetcher 层 | 整个 mutation field 一个事务 |
| **ModuleTx** | `TxRunner` | 预留 Facade 层 | 当前不用，将来拆分 module 时加 |

### GlobalTxRunner（当前主要使用）

```kotlin
// DataFetcher 层：mutation 包在全局事务内
@DgsMutation(field = "m_demo_createTodo")
fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
    val ctx = ctxProvider.fromDfe(dfe)
    val todo = globalTx.withTx(ctx) { txCtx ->
        demoService.create(txCtx, input.title, input.done, input.note, input.items)
    }
    return CreateTodoPayload(todo = todo)
}
```

**关键机制：** `GlobalTxRunner.withTx` 开启事务后设置 `opCtx.globalTxSql = pair.writer` 和 `inGlobalTx = true`。后续 `ModuleCtxFactory.chooseSql` 检测到 `globalTxSql != null` 时复用事务连接，不嵌套新事务。

### TxRunner（模块级，预留）

```kotlin
@Component
class TxRunner(private val txManager: PlatformTransactionManager) {
    fun <R> withTx(mc: ModuleCtx, propagation: TxPropagation = REQUIRED, body: (ModuleCtx) -> R): R
}
```

支持传播行为：REQUIRED / REQUIRES_NEW / SUPPORTS / NOT_SUPPORTED

### 反模式

```kotlin
// ❌ 外部 IO 在事务内
globalTx.withTx(ctx) {
    val result = externalApi.call()  // 网络 IO 占住事务连接
    repo.save(mc, entity)
}

// ✅ 先做 IO，再开事务
val result = externalApi.call()
globalTx.withTx(ctx) { txCtx -> facade.save(txCtx, entity) }
```

## 目录结构

```
core-api/src/main/kotlin/com/ifmix/api/core/
├── CoreApplication.kt
├── bff/
│   ├── graphql/customer/       # DGS DataFetcher
│   │   ├── ai/                 # AiFetcher + ScanRecordsDataLoader
│   │   ├── auth/               # AuthFetcher
│   │   ├── cms/                # CmsFetcher
│   │   ├── demo/               # DemoFetcher
│   │   ├── payment/            # PaymentFetcher
│   │   └── storage/            # StorageFetcher
│   ├── webhooks/               # WebhookController (Apple/Google IAP REST)
│   └── wellknown/              # JwksController
├── entity/                      # Jimmer interface entity (直出 GraphQL)
│   ├── ai/                     # ScanRecord, ScanCollection, ScanCollectionItem, AgnesKey, ImageRef
│   ├── auth/                   # AppUser, AuthIdentity, AuthProviderIdentity, AuthDeviceSecret, ...
│   ├── payment/                # Subscription, StoreNotification
│   ├── demo/                   # Todo, TodoItem
│   ├── app/                    # AppConfigRevision, AppInfo, ConfigTypes
│   ├── cms/                    # Feedback
│   ├── storage/                # UploadRecord
│   ├── shared/                 # Platforms, Tiers (跨模块枚举常量)
│   ├── AppScopedProps.kt       # @MappedSuperclass (appId)
│   ├── CreatedAtProps.kt       # @MappedSuperclass
│   ├── MutableProps.kt         # @MappedSuperclass (createdAt + updatedAt)
│   └── SoftDeletableProps.kt   # @MappedSuperclass (deletedAt)
├── modules/
│   ├── auth/
│   │   ├── AuthFacade.kt                  # @Service
│   │   ├── handler/AuthAggHandler.kt      # @Component
│   │   ├── repo/                          # 7 个 @Repository
│   │   ├── AuthConfig.kt, ProviderVerifier.kt, WechatVerifier.kt, ...
│   │   └── MergeOnLoginListener.kt        # @EventListener
│   ├── ai/
│   │   ├── AiFacade.kt                    # @Service
│   │   ├── ScanCollectionFacade.kt        # @Service
│   │   ├── handler/
│   │   │   ├── ScanAggHandler.kt          # @Component
│   │   │   └── ScanCollectionAggHandler.kt
│   │   ├── repo/                          # 4 个 @Repository
│   │   ├── service/                       # AI infra（非 facade/handler）
│   │   │   ├── SpringAiScanRunner.kt, AgnesKeyStore.kt, AgnesChatClientFactory.kt
│   │   │   ├── ScanPrompt.kt, AiConfig.kt
│   │   └── ScanRunner.kt                  # interface
│   ├── payment/
│   │   ├── PaymentFacade.kt
│   │   ├── handler/PaymentAggHandler.kt, PaymentWebhookHandler.kt
│   │   ├── repo/
│   │   ├── PurchaseVerifier.kt, NotificationDecoder.kt, Entitlement.kt, IapConfig.kt
│   ├── cms/
│   │   ├── CmsFacade.kt
│   │   ├── handler/FeedbackAggHandler.kt
│   │   └── repo/
│   ├── storage/
│   │   ├── StorageFacade.kt
│   │   ├── handler/StorageAggHandler.kt
│   │   └── repo/
│   ├── app/
│   │   ├── AppConfigFacade.kt
│   │   ├── handler/AppConfigAggHandler.kt
│   │   └── repo/
│   └── demo/
│       ├── DemoFacade.kt
│       ├── handler/TodoAggHandler.kt
│       └── repo/
├── dto/                         # 共享 DTO (Page, OperationResult, 模块间 req/resp)
├── infra/
│   ├── db/                     # ModuleCtx, ModuleCtxFactory, ClusterRouter, ClusterSqlPair, UuidV7, Ownership
│   ├── tx/                     # TxRunner, GlobalTxRunner, TxPropagation
│   ├── jimmer/                 # ClusterRegistry, ClusterProperties, ClusterInitializer, JimmerConfig
│   │                           # ReadWriteRoutingDataSource, AppScopedFilter, TimestampDraftInterceptor
│   │                           # OperationContextHolder
│   ├── repo/                   # CrudRepoTemplate, FilterGroupResolver
│   ├── service/                # CrudServiceOps (缓存层)
│   ├── graphql/                # OperationContextProvider, GraphQLExceptionHandler, EndpointConfig, scalars/
│   ├── http/                   # OperationContext, RequestContext, ApiError, ErrorCode, Envelope, Interceptors
│   ├── auth/                   # AuthInterceptor, AuthJwtService, AuthJwtKeys, Hashing, EmailNormalize
│   ├── redis/                  # CacheAside, RedisConfig
│   ├── ratelimit/              # RateLimiter, TierResolver, RateLimitConfig
│   ├── storage/                # ObjectStorage (interface), S3ObjectStorage, StorageConfig
│   └── config/                 # WebConfig, JacksonConfig, TransactionConfig
└── resources/
    ├── schema/common/          # GraphQL 公共 scalars
    ├── schema/customer/        # GraphQL Customer schema
    ├── db/migration/           # Flyway V1-V24
    ├── prompts/                # AI scan prompts
    └── application.yml + application-local.yml
```

## 模块分层约定（Facade + AggHandler）

### 规则

| 层 | 文件 | 注解 | 职责 |
|---|---|---|---|
| **Facade** | `XxxFacade.kt` (模块根) | `@Service` | 构造 ModuleCtx + 简单转发（不含 TxRunner/Cache） |
| **AggHandler** | `handler/XxxAggHandler.kt` | `@Component` | 纯业务逻辑，接收 ModuleCtx |

### 完整示例 — Demo 模块

```kotlin
// ======================== DemoFacade ========================
@Service
class DemoFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: TodoAggHandler,
) {
    // Queries — 无事务，Facade 只构造 mc 转发
    fun findById(ctx: OperationContext, id: UUID): Todo? =
        handler.findById(mcFactory.forApp(ctx), ctx.mustGetAppId(), id)

    // Mutations — 无 TxRunner（事务由 DataFetcher 层 GlobalTxRunner 管理）
    fun create(ctx: OperationContext, title: String, ...): Todo =
        handler.create(mcFactory.forApp(ctx), title, ...)
}

// ======================== TodoAggHandler ========================
@Component
class TodoAggHandler(
    private val todoRepo: TodoRepository,
    private val todoItemRepo: TodoItemRepository,
) {
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID): Todo? =
        todoRepo.findById(mc, appId, id)

    fun create(mc: ModuleCtx, title: String, ...): Todo {
        val todo = Todo { ... }
        return todoRepo.save(mc, todo)
    }
}

// ======================== DemoFetcher ========================
@DgsComponent
class DemoFetcher(
    private val demoService: DemoFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: OperationContextProvider,
) {
    @DgsQuery(field = "q_demo_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val ctx = ctxProvider.fromDfe(dfe)
        return demoService.findById(ctx, id) ?: throw IllegalArgumentException("Todo not found")
    }

    @DgsMutation(field = "m_demo_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = globalTx.withTx(ctx) { txCtx -> demoService.create(txCtx, input.title, ...) }
        return CreateTodoPayload(todo = todo)
    }
}
```

## 数据访问层 (Jimmer)

### Entity — 直出 GraphQL

Jimmer interface entity 直出为 GraphQL output type。DGS PropertyDataFetcher 按 selection set 取字段，`select(table)` 保证标量全 loaded，零转换层。

```kotlin
// entity/demo/Todo.kt
@Entity
interface Todo : AppScopedProps, MutableProps {
    @Id val id: UUID
    val title: String
    val done: Boolean
    val note: String?
    val installId: UUID?
    val userId: UUID?
}
```

- 不写 `toDto()`，DataFetcher 直返 entity
- Schema 不声明的字段（appId, deletedAt 等）不暴露
- UUID 通过 Jackson 全局模块自动转 Base58
- 关联字段走 DataLoader，不走 entity getter

### Repository — CrudRepoTemplate 组合模式

```kotlin
@Repository
class TodoRepository {
    companion object {
        private val tpl = CrudRepoTemplate(Todo::class, appId = "appId")

        val FILTERABLE = listOf(
            TodoProps.TITLE,
            TodoProps.DONE,
            TodoProps.USER_ID,
        )
    }

    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun findByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>) = tpl.findByIds(mc, appId, ids)
    fun save(mc: ModuleCtx, entity: Todo) = tpl.save(mc, entity)
    fun deleteById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.deleteById(mc, appId, id)
    fun deleteByIds(mc: ModuleCtx, appId: UUID, ids: Collection<UUID>) = tpl.deleteByIds(mc, appId, ids)

    // 自定义查询直接用 mc.sql
    fun findByCursor(mc: ModuleCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter?) =
        tpl.findByCursor(mc, appId, cursor, limit) {
            filter?.done?.let { where(table.done eq it) }
        }
}
```

**CrudRepoTemplate** 提供：
- `findById` / `findByIds` / `findByCursor`（返回 `Page<E>`）/ `exists`
- `save` / `batchSave`
- `deleteById` / `deleteByIds`
- 所有方法通过 `mc.sql` 执行（确保读写分离路由正确）
- `findByCursor` 接受 where lambda 追加额外条件，内部自动 limit+1 判断 hasMore

**设计原则：**
- `tpl` 放 companion object（无状态、零实例开销）
- 构造时指定字段名：`id`、`appId`（null 表示全局实体）
- 自定义查询直接用 `mc.sql.createQuery(...)`，不受 template 限制

### FilterGroup — 动态条件查询

通用 Filter DSL，支持 AND/OR 嵌套组合：

```graphql
input FilterGroup {
  and: [FilterExpr!]
  or: [FilterExpr!]
}
input FilterExpr {
  field: FieldFilter
  group: FilterGroup   # 嵌套
}
input FieldFilter {
  field: String!       # 字段名（白名单校验）
  op: FilterOp!        # EQ/NE/GT/GTE/LT/LTE/IN/NIN/LIKE/IS_NULL/IS_NOT_NULL
  value: JSON
  values: [JSON!]
}
```

后端使用 `FilterGroupResolver` 将 FilterGroup 转为 Jimmer 谓词：
- **白名单校验**：通过 `TypedProp.Scalar` 列表，不在白名单的字段直接 400
- **类型自动转换**：根据 `prop.returnClass` 自动将 JSON 值转为 UUID/Instant/Boolean 等

## 多集群路由 + 读写分离

### 模型

每个集群有一对 KSqlClient（writer + reader）。

```kotlin
data class ClusterSqlPair(val writer: KSqlClient, val reader: KSqlClient)

interface ClusterRouter {
    fun forApp(appId: UUID): ClusterSqlPair
    fun forTenant(tenantId: UUID): ClusterSqlPair
}
```

### 数据流

```
Query DataFetcher:
  opCtx.preferReader = true (默认)
  → ModuleCtxFactory.chooseSql → pair.reader

Mutation DataFetcher:
  opCtx.preferReader = false
  globalTx.withTx(opCtx):
    → router.forApp(appId) → ClusterSqlPair
    → pair.writer 开事务 → opCtx.globalTxSql = pair.writer
    → ModuleCtxFactory.chooseSql 检测 globalTxSql != null → 复用事务 writer
```

## GraphQL 设计

### Endpoint & Schema

- **Customer**: `POST /customer/graphql` — schema 从 `schema/common/` + `schema/customer/` 合并
- **Admin**: `POST /admin/graphql` — 未来实现

### Operation 命名

```
${query|mutation}_${module}_${action}
```
示例: `q_demo_findTodoById`, `m_ai_createScan`, `m_auth_loginGoogle`

**约定：**
- action 动词开头：find/create/update/delete/verify/login/logout
- 复数：`findXxxs` / `findXxxsByCursor`
- 单个：`findXxxById`
- batch：`batchDeleteTodos`

### DGS Codegen

- 从 `.graphqls` 生成 Kotlin input/payload/enum types
- output types 通过 `typeMapping` 映射到 Jimmer entity（entity 直出）
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

DataFetcher 按 `selectionSet` 判断是否回查 entity，避免无用查询。

### DataLoader

- `caching = false`（只 batching，防 mutation 间脏读）
- 关联字段走 DataLoader 批量加载
- mutation 中 DataLoader 走 globalTxSql（writer），确保读到最新

## 缓存分层

```
DataLoader (per-request, batching only, caching=false)
  → 关联字段 N+1 批量加载
Redis CacheAside (跨 request, TTL 分钟级)
  → query: readCache=true → 走 cache
  → mutation: readCache=false → 跳过
  → 写后: evict
DB (via Jimmer KSqlClient)
  → query: preferReader=true → 从库
  → mutation: preferReader=false → 主库（通过 GlobalTxRunner 走 writer）
```

## 模块职责

| 模块 | 功能 |
|------|------|
| auth | 社交登录(Google/Apple/WeChat)、设备密钥、Refresh Token 轮转、Access Token(EdDSA)、多租户 |
| ai | 古物扫描创建(限流+预签名上传)、AI 识别(Spring AI 多模态)、Key 轮换+模型 fallback、收藏管理 |
| demo | Todo 清单 CRUD、嵌入 items、游标分页、FilterGroup 示例 |
| payment | Apple/Google 购买验证、订阅管理、Webhook(JWS 验签)、Tier 映射 |
| cms | 用户反馈 |
| storage | 预签名上传/下载 |
| app | AppConfig 版本管理、AppInfo |

## 关键设计决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | GraphQL (DGS) 替代 REST | 移动端按需取字段、DataLoader 解决 N+1 |
| 2 | Jimmer 替代 jOOQ | Interface entity + KSP 扩展属性 + Draft DSL + 直出 GraphQL |
| 3 | GlobalTxRunner 在 DataFetcher 层 | 显式事务边界，整个 mutation field 一个事务 |
| 4 | TxRunner 替代 @Transactional | 多集群动态路由、显式控制、预留模块级事务 |
| 5 | Facade 只构造 mc + 转发 | 不做 cache/tx，保持 thin |
| 6 | CrudRepoTemplate 组合模式 | 不继承基类；tpl 放 companion object 无状态共享 |
| 7 | Entity 直出 GraphQL | 零 DTO 转换，Jimmer Jackson Module 跳过未加载字段 |
| 8 | DataLoader caching=false | 防 mutation 间脏读 |
| 9 | CacheAside 显式调用 | 不用 @Cacheable 魔法 |
| 10 | GraphQL input 全链路透传 | DGS codegen 生成 input，Fetcher→Facade→Handler→Repo 无中间 DTO |
| 11 | Context 三层 | RequestContext → OperationContext → ModuleCtx，职责清晰 |
| 12 | ModuleCtxFactory chooseSql | globalTxSql > preferReader 决策，统一读写分离逻辑 |
| 13 | set/unset Update 语义 | 防 null vs undefined 歧义 |
| 14 | AuthInterceptor 非阻塞 | 支持匿名+认证混合接口 |
| 15 | FilterGroup 动态查询 | 通用 Filter DSL + TypedProp 强类型白名单 + 类型自动转换 |
| 16 | Handler 命名 XxxAggHandler | Agg = 跨 entity 编排；简单场景只有 AggHandler |
| 17 | Operation 命名: ${q\|m}_${module}_${action} | 清晰 + Federation 友好 |
| 18 | 枚举全链路 Int 透传 | GraphQL 不用 enum，灰度/多版本安全 |
| 19 | @Service/@Component 直注册 | 不在 Config 间接注册 |

## API 约定

- **GraphQL endpoint**: `/customer/graphql` (需 `x-app-id` header)
- **Webhook (REST)**: `POST /webhooks/iap/*` (JWS 验签)
- **JWKS (REST)**: `GET /.well-known/jwks`
- **所有 REST 响应包装**: `Envelope<T>` (`{code, msg, data}`)

## 数据库约定

- **表名前缀**: `core_` (如 `core_todo`, `core_app_user`)
- **主键**: UUIDv7 (时间有序，支持游标分页)
- **游标分页**: `WHERE id < cursor ORDER BY id DESC LIMIT n+1`
- **读写分离**: ClusterRegistry + ClusterRouter + ReadWriteRoutingDataSource
- **软删除**: `deletedAt` 列（继承 SoftDeletableProps）
- **Flyway**: V1-V24, 不可回退

### UUID 表示

- **PG/Jimmer**: 原生 UUID (16 bytes)
- **API/Redis/前端**: 22 位 Base58 (Bitcoin 字母表, URL-safe)
- **Jackson**: 全局模块自动转换 (`JacksonConfig.uuidBase58Module`)
- **工具**: `infra/codec/Base58.kt` — `uuid.toBase58()` / `str.toUuidFromBase58()`

### 枚举

- **GraphQL**: input/output 全部 `Int`，schema 注释写含义
- **PG**: SMALLINT
- **Kotlin Model**: `val status: Int`
- **内部辅助常量**: 放 model class 嵌套 object（如 `ScanRecord.Status.COMPLETED`）
- **跨模块共享**: 放 `entity/shared/`
- **编码规则**: 0 保留不用，从 10 开始步长 10（已有编码不变）

## 存储上传

- objectKey 格式: `app_{appId}/i_{installId}/...` 或 `app_{appId}/u_{userId}/...`
- 强制格式校验，禁止路径遍历 (`..`)
- `presignUpload` 不要求登录
- `presignDownload` 暂不做权限验证

## AI 扫描

- **模型 fallback**: 主模型 → fallback 列表
- **Key 重试**: 每个模型遍历所有可用 key（内层循环）
- **预扣配额**: 请求前扣减，失败归还

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
./gradlew :core-api:compileKotlin          # 编译（含 KSP）
./gradlew :core-api:test                   # 全部测试
./gradlew :core-api:test --tests "*.e2e.*" # E2E
./gradlew :core-api:bootRun                # 运行 (需 PG + Redis)
```

- **测试框架**: JUnit 5 + Mockito + assertk
- **集成测试**: Testcontainers (PostgreSQL + Redis)
- **E2E**: WebTestClient + Testcontainers

## 待办

### 🔴 需立即修复

- **OperationContextHolder 未设置**: `OperationContextProvider.fromDfe()` 未调用 `OperationContextHolder.set(ctx)`，导致 DataLoader 和关联字段解析运行时抛 `IllegalStateException`。修复后需在请求结束时 `clear()`。
- **newScan AI 调用在事务内**: `AiFetcher.newScan` 把 `aiService.newScan()` 整个包在 `globalTx.withTx` 内，而 `ScanAggHandler.saveNewScan()` 内部调用 `scanRunner.run()`（外部 HTTP AI 调用，耗时数秒）。应拆为：AI 调用在事务外 → DB 写入在事务内。

### 🟠 分层违规

- **AiFetcher 直接注入 repo + mcFactory**: 违反 "DataFetcher 只注入 Facade + GlobalTxRunner + ctxProvider" 约束。`scanRecord()` 关联字段应改走 DataLoader，DataLoader 通过 Facade 或直接 repo 加载。
- **WebhookController 直接注入 AppConfigRepository**: 应通过 AppConfigFacade 暴露 `findAppIdByBundleId` / `findAppIdByAndroidPackage`。
- **ScanRecordsDataLoader O(N) 查询**: 逐个 `findById` 应改为 `findByIds` 单次批量。

### 🟡 业务逻辑

- **partialUpdate unset 未实现**: GraphQL schema 已有 `unset` 字段定义，但 `ScanRecordRepository.partialUpdate` 只处理 `set`，忽略 `unset`。
- **ai 模块 FilterGroup 未接入**: `ScanAggHandler.findByFilter` 是 stub（忽略 filter 参数），需接入 `FilterGroupResolver` + 声明 FILTERABLE 白名单。

### 🟡 API 重命名（Breaking Change，需客户端配合）

- **Payload → Result**: 当前 mutation 返回类型仍为 `XxxPayload`，计划改为 `XxxResult`
- **Operation 前缀缩短**: `q_` / `m_` → `q_` / `m_`（待定）

### 未来

- **Admin GraphQL**: `/admin/graphql` endpoint
- **Federation 预留**: 命名已兼容

## 迁移历史

| 日期 | 事项 | 状态 |
|------|------|------|
| 2026-08-18 | CrudRepoOps 实例级重构 | ✅ 完成（已演化为 CrudRepoTemplate） |
| 2026-08-18 | Internal Service 参数统一改为 SvcCtx | ✅ 完成（已演化为 ModuleCtx） |
| 2026-08-18 | Repo fetchInto + crud.insert 简化 | ✅ 完成（Jimmer 替代 jOOQ） |
| 2026-08-19 | jOOQ → Jimmer 全量迁移 | ✅ 完成 |
| 2026-08-19 | Jimmer Round 2 Review 修复 | ✅ 完成 |
| 2026-08-19 | 架构对齐（命名重构 + 事务边界） | ✅ 完成 |
| 2026-08-20 | 分层规范化（ModuleCtx + AggHandler + GlobalTx） | ✅ 主体完成，剩余见待办 |
