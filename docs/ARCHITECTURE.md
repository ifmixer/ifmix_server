# ifmix_server 架构文档

> 最后更新: 2026-08-23
> 状态: Auth IDP 重设计完成，模块包名重命名完成（payment→pay, storage→media）

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
| 认证 | EdDSA(Ed25519) JWT + IDP OAuth2 | — |
| 构建 | Gradle 9.6.1 + KSP | — |
| 序列化 | Jackson 3 (tools.jackson) | — |
| GraphQL codegen | DGS codegen 8.6.0 | schema → input/payload/enum |

## 分层架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  BFF — GraphQL (DGS DataFetcher) + REST                              │
│  POST /customer/core/gql  (主 API)                                   │
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
│  Jimmer/CacheAside/GlobalTxRunner/Auth/RateLimit/Storage             │
├─────────────────────────────────────────────────────────────────────┤
│  Data: PostgreSQL (Writer + Reader) | Redis | S3                     │
│  Flyway V1-V28 | UUIDv7 时间有序 ID                                  │
└─────────────────────────────────────────────────────────────────────┘
```

### 分层约束

```
DataFetcher  →  只注入 Facade + GlobalTxRunner + OperationContextProvider
Facade       →  只注入 AggHandler + ModuleCtxFactory + 其他模块 Facade（跨模块）
Handler      →  只注入 Repo + CacheAside + 同模块 infra service
Repo         →  持有 CrudRepoTemplate（companion object）
```

**禁止跨级：**
- DataFetcher 不能 import handler/repo 包
- Facade 不能 import repo 包
- Handler 不能 import facade 包（可注入其他模块的 Facade）
- DataLoader/Resolver 通过 Facade 调用，不直接注入 repo
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
fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoResult {
    val ctx = ctxProvider.fromDfe(dfe)
    val todo = globalTx.withTx(ctx) { txCtx ->
        demoService.create(txCtx, input)
    }
    return CreateTodoResult(todo = todo)
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
│   │   ├── ai/                 # AiFetcher + DataLoaders
│   │   ├── auth/               # AuthFetcher
│   │   ├── cms/                # CmsFetcher
│   │   ├── demo/               # DemoFetcher + TodoItemsResolver
│   │   ├── pay/                # PayFetcher
│   │   └── media/              # MediaFetcher
│   ├── webhooks/               # WebhookController (Apple/Google IAP REST)
│   └── wellknown/              # JwksController
├── entity/                      # Jimmer interface entity (直出 GraphQL)
│   ├── common/                 # 基类: BaseEntity, BaseAppEntity, UUIDProps, MutableProps, etc.
│   ├── ai/                     # ScanRecord, ScanCollection, ScanCollectionItem, AgnesKey, ImageRef
│   ├── auth/                   # Idp, IdpIdentity, AppToIdpRelation, AppUserToIdpIdentityRelation, AppUserRefreshToken, AppUserToInstallRelation
│   ├── pay/                    # Subscription, StoreNotification
│   ├── demo/                   # Todo, TodoItem, TodoRecommend
│   ├── app/                    # AppConfigRevision, AppInfo, ConfigTypes
│   ├── user/                   # AppUser
│   ├── cms/                    # Feedback
│   ├── media/                  # UploadRecord
│   └── shared/                 # Platforms, Tiers (跨模块枚举常量)
├── modules/
│   ├── auth/
│   │   ├── AuthFacade.kt
│   │   ├── handler/AuthAggHandler.kt
│   │   ├── repo/               # IdpRepository, IdpIdentityRepository, AppToIdpRelationRepository, etc.
│   │   ├── AuthConfig.kt, ProviderVerifier.kt
│   │   └── MergeOnLoginListener.kt
│   ├── user/
│   │   ├── UserFacade.kt
│   │   └── repo/AppUserRepository.kt
│   ├── ai/
│   │   ├── AiFacade.kt, ScanCollectionFacade.kt
│   │   ├── handler/ScanAggHandler.kt, ScanCollectionAggHandler.kt
│   │   ├── repo/
│   │   └── service/            # AI infra（SpringAiScanRunner, AgnesKeyStore, etc.）
│   ├── pay/
│   │   ├── PayFacade.kt
│   │   ├── handler/PayAggHandler.kt, PayWebhookHandler.kt
│   │   └── repo/
│   ├── cms/
│   │   ├── CmsFacade.kt
│   │   ├── handler/FeedbackAggHandler.kt
│   │   └── repo/
│   ├── media/
│   │   ├── MediaFacade.kt
│   │   ├── handler/MediaAggHandler.kt
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
│   ├── db/                     # ModuleCtx, ModuleCtxFactory, ClusterRouter, ClusterSqlPair, UuidV7
│   ├── tx/                     # TxRunner, GlobalTxRunner, TxPropagation
│   ├── jimmer/                 # ClusterRegistry, ClusterProperties, JimmerConfig
│   │                           # ReadWriteRoutingDataSource, AppScopedFilter, TimestampDraftInterceptor
│   │                           # OperationContextHolder
│   ├── repo/                   # CrudRepoTemplate, AppCrudRepoTemplate, FilterGroupResolver
│   ├── service/                # CrudServiceOps (缓存层)
│   ├── graphql/                # OperationContextProvider, GraphQLExceptionHandler, EndpointConfig, scalars/
│   ├── http/                   # OperationContext, RequestContext, ApiError, ErrorCode, Envelope, Interceptors
│   ├── auth/                   # AuthInterceptor, AuthJwtService, AuthJwtKeys, Hashing
│   ├── redis/                  # CacheAside, RedisConfig
│   ├── ratelimit/              # RateLimiter, TierResolver, RateLimitConfig
│   ├── storage/                # ObjectStorage (interface), S3ObjectStorage, StorageConfig
│   └── config/                 # WebConfig, JacksonConfig, TransactionConfig
└── resources/
    ├── schema/common/          # GraphQL 公共 scalars
    ├── schema/customer/        # GraphQL Customer schema
    ├── db/migration/           # Flyway V1-V28
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

    // Mutations — 直传 GraphQL input，不逐字段粘贴
    fun create(ctx: OperationContext, input: CreateTodoInput): Todo =
        handler.create(mcFactory.forApp(ctx), input)
}

// ======================== TodoAggHandler ========================
@Component
class TodoAggHandler(
    private val todoRepo: TodoRepository,
    private val todoItemRepo: TodoItemRepository,
) {
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID): Todo? =
        todoRepo.findById(mc, appId, id)

    fun create(mc: ModuleCtx, input: CreateTodoInput): Todo {
        val todo = Todo { ... }
        todoRepo.save(mc, todo)
        return todo
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
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val todo = globalTx.withTx(ctx) { txCtx -> demoService.create(txCtx, input) }
        return CreateTodoResult(todo = todo)
    }
}
```

## 认证架构（IDP 模型）

### 核心概念

```
Idp (全局)                          — 身份提供商配置（Apple/Google），一旦创建只改 name/desc
IdpIdentity (全局)                  — IDP 下的用户身份，按 (idpId, idpIdentityId) 唯一
AppToIdpRelation (app 级)           — App 启用了哪些 IDP
AppUser (app 级, user 模块)         — App 内的用户
AppUserToIdpIdentityRelation (app)  — AppUser 绑定了哪些 IDP 身份
AppUserRefreshToken (app)           — Refresh Token
```

### 登录流程

```
1. 客户端传 idpId + credential (id_token)
2. 验证 app 是否启用了该 IDP (auth_app_to_idp_relation)
3. 加载 IDP 配置 (auth_idp.config)，验证 credential → 得到 accountId
4. 找/建 IdpIdentity（全局，按 idpId + accountId 唯一）
5. 通过 auth_appuser_to_idpidentity_relation 查该 identity 在此 app 下绑了哪个 AppUser
   - 有 → 拿到 appUserId
   - 没有 → 创建 AppUser → 建 relation
6. 签发 refresh token + access token
```

### providerType 编码

| 编码 | Provider |
|------|----------|
| 10 | Apple |
| 20 | Google |

### AuthInterceptor

- **非阻塞设计**：无效 token 不拦截，只是不填充 userId
- 需要强认证的接口由 Handler 层判断 `mc.userId ?: throw ApiError(UNAUTHORIZED)`
- OPTIONS 请求自动跳过（CORS preflight）

## 数据访问层 (Jimmer)

### Entity — 直出 GraphQL

Jimmer interface entity 直出为 GraphQL output type。DGS PropertyDataFetcher 按 selection set 取字段，`select(table)` 保证标量全 loaded，零转换层。

```kotlin
@Entity
@Table(name = "demo_todo")
interface Todo : BaseAppEntity, SoftDeletableProps {
    val title: String
    val done: Boolean
    val note: String?
    @Serialized
    val recommend: TodoRecommend?
}
```

- 不写 `toDto()`，DataFetcher 直返 entity
- Schema 不声明的字段（appId, deletedAt 等）不暴露
- UUID 通过 GraphQL 透传原始格式
- 关联字段走 DataLoader，不走 entity getter
- 跨模块字段用逻辑外键 `val xxxId: UUID`，不用 `@ManyToOne`
- 模块内可用 `@ManyToOne`

### Entity 基类

| 基类 | 包含字段 | 用于 |
|------|---------|------|
| `BaseAppEntity` | `id: UUID` + `appId: UUID` + `createdAt` + `updatedAt` | 大部分 app 级实体 |
| `BaseEntity` | `id: UUID` + `createdAt` + `updatedAt` | 全局实体（有 updatedAt） |
| `UUIDProps` | `@Id val id: UUID` | 只需要 ID 的组合场景 |

### JSONB 值对象

- `data class` + `@Serialized` 注解
- 领域模型定义在 `entity/` 下
- `toDomain()` 转换函数放同文件
- DateTime 在 JSONB 中存为 ISO-8601 字符串（Jimmer 默认 Jackson 行为）

### Repository — CrudRepoTemplate 组合模式

两个模板类，按是否需要租户隔离选用：

| 模板 | 适用场景 | 签名特征 |
|------|---------|----------|
| `CrudRepoTemplate<E>` | 全局实体（Idp, IdpIdentity, AppInfo） | `findById(ctx, id)` |
| `AppCrudRepoTemplate<E>` | App 级实体（Todo, AppUser 等） | `findById(ctx, appId, id)` |

两者都提供：
- **Read**: `findById` / `findByIds` / `exists` / `existsByIds` / `findByCursor`
- **Write**: `save`(→Boolean) / `batchSave`(→Int)
- **Delete**: `deleteById`(→Boolean) / `deleteByIds`(→Int)

```kotlin
@Repository
class TodoRepository {
    companion object {
        private val tpl = AppCrudRepoTemplate(Todo::class)
        val FILTERABLE = listOf(TodoProps.TITLE, TodoProps.DONE, TodoProps.USER_ID)
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

**设计原则：**
- `tpl` 放 companion object（无状态、零实例开销）
- `CrudRepoTemplate` 用于全局实体（无 appId）；`AppCrudRepoTemplate` 用于 app 级实体（所有操作强制 appId 参数）
- 单条操作返回 Boolean（成功/失败），batch 操作返回 Int（影响行数）
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

- **Customer**: `POST /customer/core/gql` — schema 从 `schema/common/` + `schema/customer/` 合并
- **GraphiQL**: `/apidocs/core/customer/gql`（开发环境）
- **Admin**: `POST /admin/graphql` — 未来实现

### Operation 命名

```
${query|mutation}_${module}_${action}
```
示例: `q_demo_findTodoById`, `m_ai_createScan`, `m_auth_login`

**约定：**
- action 动词开头：find/create/update/delete/verify/login/logout
- 复数：`findXxxs` / `findXxxsByCursor`
- 单个：`findXxxById`
- batch：`batchDeleteTodos`

### DateTime Scalar

- **输出**: ISO-8601 UTC 字符串（`"2026-08-22T04:50:00Z"`）
- **输入**: 接受 ISO-8601 字符串或 epoch millis 数字（兼容）
- **JSONB 存储**: 也是 ISO-8601（Jimmer 默认 Jackson 行为，一致）

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

### GraphQL input 全链路透传

```kotlin
// Fetcher → Facade → Handler 直传 input 对象，不逐字段粘贴
@DgsMutation(field = "m_demo_createTodo")
fun createTodo(..., @InputArgument input: CreateTodoInput): CreateTodoResult {
    val todo = globalTx.withTx(ctx) { txCtx -> demoService.create(txCtx, input) }
    return CreateTodoResult(todo = todo)
}
```

### Mutation Result

```graphql
type XxxResult {
    success: Boolean!
    xxx: Xxx          # 客户端 select 了才回查
}
```

DataFetcher 按 `selectionSet` 判断是否回查 entity，避免无用查询。

### DataLoader

- `caching = false`（只 batching，防 mutation 间脏读）
- 关联字段走 DataLoader 批量加载
- DataLoader 通过 Facade 调用，不直接注入 repo
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

## 数据库约定

### 表名规则

- 模块前缀: `{module}_`（如 `auth_`, `demo_`, `pay_`）
- 实体名小写下划线
- 关系表: `{module}_{from}_to_{to}_relation`

### 全部表

| 模块 | 表名 | 级别 | Entity |
|------|------|------|--------|
| auth | `auth_idp` | 全局 | Idp |
| auth | `auth_idpidentity` | 全局 | IdpIdentity |
| auth | `auth_app_to_idp_relation` | app | AppToIdpRelation |
| auth | `auth_appuser_to_idpidentity_relation` | app | AppUserToIdpIdentityRelation |
| auth | `auth_appuser_refreshtoken` | app | AppUserRefreshToken |
| auth | `auth_appuser_to_install_relation` | app | AppUserToInstallRelation |
| user | `user_appuser` | app | AppUser |
| demo | `demo_todo` | app | Todo |
| demo | `demo_todo_item` | app | TodoItem |
| app | `app_config_revision` | app | AppConfigRevision |
| app | `app_info` | 全局 | AppInfo |
| ai | `ai_scan_record` | app | ScanRecord |
| ai | `ai_scan_collection` | app | ScanCollection |
| ai | `ai_scan_collection_item` | app | ScanCollectionItem |
| ai | `ai_agnes_key` | app | AgnesKey |
| pay | `pay_subscription` | app | Subscription |
| pay | `pay_store_notification` | app | StoreNotification |
| media | `media_upload_record` | app | UploadRecord |
| cms | `cms_feedback` | app | Feedback |

### 其他约定

- **主键**: UUIDv7 (时间有序，支持游标分页)
- **游标分页**: `WHERE id < cursor ORDER BY id DESC LIMIT n+1`
- **读写分离**: ClusterRegistry + ClusterRouter + ReadWriteRoutingDataSource
- **软删除**: `deletedAt` 列（继承 SoftDeletableProps）
- **Flyway**: V1-V28, 不可回退

### UUID 表示

- **PG/Jimmer**: 原生 UUID (16 bytes)
- **API 输出**: 原始 36 字符格式（如 `01a0284c-e957-732a-9c00-0d3738224dab`）
- **objectKey（URL 场景）**: 22 位 Base58（短、URL-safe）— 待实现

### 枚举

- **GraphQL**: input/output 全部 `Int`，schema 注释写含义
- **PG**: SMALLINT
- **Kotlin Model**: `val status: Int`
- **内部辅助常量**: 放 model class 嵌套 object（如 `ScanRecord.Status.COMPLETED`）
- **跨模块共享**: 放 `entity/shared/`
- **编码规则**: 0 保留不用，从 10 开始步长 10（已有编码不变）

## 模块职责

| 模块 | 功能 |
|------|------|
| auth | IDP 登录(Apple/Google)、AppUser↔IDP 绑定、Refresh Token 轮转、Access Token(EdDSA) |
| user | AppUser CRUD |
| ai | 古物扫描创建(限流+预签名上传)、AI 识别(Spring AI 多模态)、Key 轮换+模型 fallback、收藏管理 |
| demo | Todo 清单 CRUD、嵌入 items、游标分页、FilterGroup 示例 |
| pay | Apple/Google 购买验证、订阅管理、Webhook(JWS 验签)、Tier 映射 |
| cms | 用户反馈 |
| media | 预签名上传/下载 |
| app | AppConfig 版本管理、AppInfo |

## 存储上传

- objectKey 格式: `app_{appId}/i_{installId}/...` 或 `app_{appId}/u_{userId}/...`
- 强制格式校验，禁止路径遍历 (`..`)
- `presignUpload` 不要求登录
- `presignDownload` 暂不做权限验证

## AI 扫描

- **模型 fallback**: 主模型 → fallback 列表
- **Key 重试**: 每个模型遍历所有可用 key（内层循环）
- **预扣配额**: 请求前扣减，失败归还

## 关键设计决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | GraphQL (DGS) 替代 REST | 移动端按需取字段、DataLoader 解决 N+1 |
| 2 | Jimmer 替代 jOOQ | Interface entity + KSP + Draft DSL + 直出 GraphQL |
| 3 | GlobalTxRunner 在 DataFetcher 层 | 显式事务边界，整个 mutation field 一个事务 |
| 4 | Facade 只构造 mc + 转发 | 不做 cache/tx，保持 thin |
| 5 | CrudRepoTemplate 分两类 | `CrudRepoTemplate`(全局) + `AppCrudRepoTemplate`(强制 appId)，类型安全 |
| 6 | Template 单条返回 Boolean，batch 返回 Int | 语义清晰 |
| 7 | Template 必须提供 batch 方法 | findByIds/existsByIds/batchSave/deleteByIds |
| 8 | Entity 直出 GraphQL | 零 DTO 转换 |
| 9 | DataLoader caching=false | 防 mutation 间脏读 |
| 10 | DataLoader/Resolver 通过 Facade | 不直接注入 repo |
| 11 | GraphQL input 全链路透传 | Fetcher→Facade→Handler 直传 input 对象 |
| 12 | JSONB 值对象 = data class + @Serialized | toDomain() 放同文件 |
| 13 | set/unset Update 语义 | 防 null vs undefined 歧义 |
| 14 | AuthInterceptor 非阻塞 | 支持匿名+认证混合接口 |
| 15 | 枚举全链路 Int 透传 | GraphQL 不用 enum，灰度安全 |
| 16 | DateTime 统一 ISO-8601 字符串 | GraphQL 输出 + JSONB 存储一致 |
| 17 | IDP 模型替代 AuthTenant | 去掉 tenant 层，简化为 IDP + relation |
| 18 | 跨模块用逻辑外键 UUID | 不用 Jimmer `@ManyToOne`，保持模块独立 |
| 19 | BaseEntity / BaseAppEntity 基类 | 减少样板：`id + createdAt + updatedAt`（+ appId） |
| 20 | 表名带模块前缀 | `auth_idp`, `demo_todo`, `pay_subscription` |
| 21 | payment→pay, storage→media | 包名/表名统一短名 |
| 22 | @Service/@Component 直注册 | 不在 Config 间接注册 |
| 23 | presignUpload 不要求登录 | 后续通过行为验证增强 |
| 24 | AI ScanRunner 每个模型遍历所有 key | 不是只试一个就跳下一个模型 |
| 25 | 限流超限不删 Redis key | 让 key 自然 TTL 过期 |
| 26 | objectKey 强格式校验 | 防路径遍历 |
| 27 | Webhook 必须验签 | Apple JWS / Google 通过 packageName 反查 appId |
| 28 | CacheAside 显式调用 | 不用 @Cacheable 魔法 |

## API 约定

- **GraphQL endpoint**: `/customer/core/gql` (需 `x-app-id` header)
- **GraphiQL**: `/apidocs/core/customer/gql`（DGS 提供）
- **Webhook (REST)**: `POST /webhooks/iap/*` (JWS 验签)
- **JWKS (REST)**: `GET /.well-known/jwks`
- **所有 REST 响应包装**: `Envelope<T>` (`{code, msg, data}`)

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

## 迁移历史

| 日期 | 事项 | 状态 |
|------|------|------|
| 2026-08-18 | CrudRepoOps → CrudRepoTemplate | ✅ |
| 2026-08-19 | jOOQ → Jimmer 全量迁移 | ✅ |
| 2026-08-20 | 分层规范化（ModuleCtx + AggHandler + GlobalTx） | ✅ |
| 2026-08-22 | CrudRepoTemplate 分 Global/App 两类 | ✅ |
| 2026-08-22 | 表名重命名（去 core_ 加模块前缀）| ✅ |
| 2026-08-22 | payment→pay, storage→media 包名重命名 | ✅ |
| 2026-08-22 | DateTime 统一为 ISO-8601 字符串 | ✅ |
| 2026-08-23 | Auth 重设计：去掉 AuthTenant，引入 IDP 模型 | ✅ |
| 2026-08-23 | BaseEntity / BaseAppEntity / UUIDProps 基类 | ✅ |
| 2026-08-23 | user 模块独立（AppUser 从 auth 移出）| ✅ |
