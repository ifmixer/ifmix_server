# ifmix_server 架构文档

> 最后更新: 2026-08-20
> 状态: Jimmer 迁移已完成，GraphQL DGS 已就位，CrudRepoTemplate 组合模式已就位

## 项目概述

面向移动端（iOS/Android）的后端 API 服务：古物扫描 + AI 图像识别 + 收藏管理 + 社交登录 + IAP。

## 技术栈

| 层级 | 选型 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.10 |
| 运行时 | JDK 25 (Virtual Threads) | — |
| 框架 | Spring Boot | 4.1.0 |
| API | GraphQL (Netflix DGS) | 12.0.1 |
| SQL | Jimmer | 0.11.5 |
| 数据库 | PostgreSQL (读写分离) | — |
| 缓存 | Redis + CacheAside | — |
| 对象存储 | S3 兼容 (AWS/R2/MinIO) | — |
| AI | Spring AI 2.0 (OpenAI-compatible) | — |
| 认证 | EdDSA(Ed25519) JWT + OAuth2 | — |
| 构建 | Gradle 9.6.1 + KSP | — |
| 序列化 | Jackson 3 (tools.jackson) | — |
| GraphQL codegen | DGS codegen 8.6.0 | schema → input/payload/enum |

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
│  Facade Layer (modules/*/XxxFacade.kt)                               │
│  业务编排 · TxRunner 事务(最小边界) · 只包写操作                      │
├─────────────────────────────────────────────────────────────────────┤
│  Handler Layer (modules/*/handler/XxxHandler.kt)                     │
│  纯业务逻辑 · 接收 SvcCtx · 不注入 TxRunner                         │
├─────────────────────────────────────────────────────────────────────┤
│  Repository Layer (modules/*/repo/)                                   │
│  纯数据访问 · 使用 Jimmer KSqlClient · 接收 SvcCtx                  │
├─────────────────────────────────────────────────────────────────────┤
│  Model (entity/)                                                      │
│  Jimmer interface + @MappedSuperclass · KSP 生成扩展属性               │
├─────────────────────────────────────────────────────────────────────┤
│  Infra (infra/)                                                      │
│  Jimmer/CacheAside/TxRunner/GraphQL scalars/Auth/RateLimit/Storage     │
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
├── entity/                      # Jimmer entities (interface + 注解)
│   ├── Todo, TodoItem, ScanRecord, ScanCollection, ...
│   ├── AppUser, AuthIdentity, AuthDeviceSecret, ...
│   └── Subscription, StoreNotification, Feedback, ...
├── modules/                    # 业务模块 (Facade + handler/)
│   ├── auth/                   # 认证 + 社交登录
│   │   ├── AuthFacade.kt       # @Service 对外入口
│   │   ├── handler/AuthHandler.kt  # @Component 业务逻辑
│   │   └── repo/
│   ├── ai/                     # 古物扫描 + AI 识别
│   │   ├── AiFacade.kt         # @Service 对外入口
│   │   ├── ScanCollectionFacade.kt
│   │   ├── handler/
│   │   │   ├── ScanHandler.kt
│   │   │   └── ScanCollectionHandler.kt
│   │   ├── repo/
│   │   └── service/            # AI infra (非 facade/handler)
│   ├── payment/                # 内购 + 订阅
│   │   ├── PaymentFacade.kt
│   │   ├── handler/PaymentHandler.kt
│   │   ├── handler/PaymentWebhookHandler.kt
│   │   └── repo/
│   ├── cms/                    # 用户反馈
│   │   ├── CmsFacade.kt
│   │   ├── handler/FeedbackHandler.kt
│   │   └── repo/
│   ├── storage/                # 对象存储
│   │   ├── StorageFacade.kt
│   │   ├── handler/StorageHandler.kt
│   │   └── repo/
│   ├── app/                    # AppConfig / AppInfo
│   │   ├── AppConfigFacade.kt
│   │   ├── handler/AppConfigHandler.kt
│   │   └── repo/
│   └── demo/                   # Todo 演示
│       ├── DemoFacade.kt
│       ├── handler/TodoHandler.kt
│       └── repo/
├── entity/                     # Jimmer 实体 (interface + 注解, KSP 生成扩展)
├── infra/
│   ├── jooq/                   # CrudOps, TxRunner, AuditRecordListener, JooqConfig, InstantConverter
│   ├── jimmer/                 # ClusterRegistry, ReadWriteRouting (迁移完后删除)
│   ├── graphql/                # OperationContextProvider, scalars, ExceptionHandler, EndpointConfig
│   ├── repo/                   # CrudRepoTemplate (组合模式), FilterGroupResolver
│   ├── service/                # CrudServiceOps (通用 service 操作)
│   ├── http/                   # Envelope, ApiError, ErrorCode, Interceptors, RequestContext
│   ├── auth/                   # JWT 签发/验签, AuthInterceptor, Hashing
│   ├── redis/                  # CacheAside, RedisConfig
│   ├── ratelimit/              # RateLimiter (Redis 日固定窗口)
│   ├── storage/                # ObjectStorage + S3 实现
│   ├── db/                     # UuidV7, RepoContext, Ownership
│   ├── dto/                    # CursorQueryInput, Page, CommonDto
│   └── config/                 # WebConfig, JacksonConfig, TransactionConfig
└── src/generated/ksp/main/kotlin/  # KSP 生成的 Jimmer 扩展属性和 Draft

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

## 数据访问层 (Jimmer)

### Entity 定义

- Jimmer `interface` + KSP 注解（`@Entity`, `@MappedSuperclass`, `@Id`, `@Column`, `@ManyToOne`, `@Serialized` 等）
- KSP 自动生成：包级扩展属性（`table.xxx`）、Draft DSL（`Xxx { ... }`）、TypedProp（`TodoProps.TITLE`）
- 不用手动维护 POJO，KSP 生成代码在 `build/generated/ksp/`

### Repository — CrudRepoTemplate 组合模式

Repository 不继承基类、不注入 `KSqlClient`，通过组合持有 `CrudRepoTemplate` 实例：

```kotlin
@Repository
class TodoRepository {
    companion object {
        private val tpl = CrudRepoTemplate(Todo::class, appId = "appId")

        /** 允许前端通过 FilterGroup 查询的字段（强类型白名单） */
        val FILTERABLE = listOf(
            TodoProps.TITLE,
            TodoProps.DONE,
            TodoProps.USER_ID,
        )
    }

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID) = tpl.findById(ctx, appId, id)
    fun save(ctx: SvcCtx, entity: Todo) = tpl.save(ctx, entity)

    // 自定义查询直接用 ctx.sql
    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int, filter: TodoFilter?) =
        tpl.findByCursor(ctx, appId, cursor, limit) {
            filter?.done?.let { where(table.done eq it) }
        }
}
```

**CrudRepoTemplate** 提供：
- `findById` / `findByIds` / `findByCursor`（返回 `Page<E>`）/ `exists`
- `save` / `batchSave`
- `deleteById` / `deleteByIds`
- 所有方法通过 `ctx.sql` 执行（确保读写分离路由正确）
- `findByCursor` 接受 where lambda 追加额外条件，内部自动 limit+1 判断 hasMore

**设计原则：**
- `tpl` 放 companion object（无状态、零实例开销）
- 构造时指定字段名：`id`、`appId`（null 表示全局实体）
- 自定义查询直接用 `ctx.sql.createQuery(...)`，不受 template 限制

### FilterGroup — 动态条件查询

通用 Filter DSL，支持 AND/OR 嵌套组合，类似 MongoDB 查询语法：

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
- 前端传参约定：UUID → 字符串, Instant → epoch millis, Boolean → true/false

### Input 类型透传

Repository/Handler 直接接受 DGS codegen 生成的 GraphQL input 类型（如 `UpdateTodoInput`、`UpdateTodoItemsMutationInput`），不自定义中间 DTO。GraphQL input 从 Fetcher 一路透传到 Repo。

### 事务管理 — TxRunner

```kotlin
fun createXxx(sc: SvcCtx, input) = tx.withTx(sc) { txSc ->
    repo.save(txSc, entity)
    entity.id
}
```
- 不用 `@Transactional`（动态路由，Spring 注解绑固定 DataSource）
- 传播行为: REQUIRED / REQUIRES_NEW / SUPPORTS / NOT_SUPPORTED
- 事务边界在 Facade 层，只包写操作

### SvcCtx

```kotlin
data class SvcCtx(
    val op: OperationContext,
    val sql: KSqlClient,    // 路由后的实例，确保读写分离
)
```

### OperationContext

```kotlin
data class OperationContext(
    // per-request (HTTP header): appId, installId, userId, lang, currency, country, clientPlatform, clientIp
    // per-operation (GraphQL):
    val req: RequestContext,
    val opName: String?,
    val isMutation: Boolean,
    val globalTxSql: KSqlClient? = null,
    val inGlobalTx: Boolean = false,
)
```

## 缓存分层

```
DataLoader (per-request, batching only, caching=false)
  → 关联字段 N+1 批量加载
Redis CacheAside (跨 request, TTL 分钟级)
  → query: readCache=true → 走 cache
  → mutation: readCache=false → 跳过
  → 写后: evict
DB (via Jimmer KSqlClient)
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
| 2 | Jimmer 替代 jOOQ | Interface entity + KSP 扩展属性 + Draft DSL + @MappedSuperclass 继承支持 |
| 3 | TxRunner 替代 @Transactional | 多集群动态路由、显式控制 |
| 4 | CrudRepoTemplate 组合模式 | Repo 不继承基类、不注入 sql；tpl 放 companion object 无状态共享 |
| 5 | DataLoader caching=false | 防 mutation 间脏读 |
| 6 | CacheAside 显式调用 | 不用 @Cacheable 魔法 |
| 7 | GraphQL input 全链路透传 | Fetcher→Facade→Handler→Repo 直接用 DGS codegen 生成的 input 类型，不自定义中间 DTO |
| 8 | KSP 自动生成代码 | 无需手动 codegen，编译时自动处理 |
| 9 | set/unset Update 语义 | 防 null vs undefined 歧义 |
| 10 | AuthInterceptor 非阻塞 | 支持匿名+认证混合 |
| 11 | presignUpload 不要求登录 | 已确定 |
| 12 | 限流超限不删 Redis key | 自然 TTL 过期 |
| 13 | Operation 命名: ${q\|m}_${module}_${action} | 清晰 + Federation 友好 |
| 14 | FilterGroup 动态查询 | 通用 Filter DSL + TypedProp 强类型白名单 + 类型自动转换 |
| 15 | findByCursor 返回 Page | 分页逻辑下沉到 Template，上层不关心 limit+1 细节 |
| 16 | FILTERABLE 用 TypedProp 强类型 | 编译时检查，字段重命名自动跟随 |

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

- **PG/Jimmer**: 原生 UUID (16 bytes)
- **API/Redis/前端**: 22 位 Base58 (Bitcoin 字母表, URL-safe)
- **Jackson**: 全局模块自动转换 (`JacksonConfig.uuidBase58Module`)
- **工具**: `infra/codec/Base58.kt` — `uuid.toBase58()` / `str.toUuidFromBase58()`

### 枚举

**全链路 Int 透传 + 内部常量辅助。**

- **GraphQL**: input/output 全部 `Int`，schema 注释写含义
- **PG**: SMALLINT（Jimmer @Column 自动映射 Int）
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

## 迁移状态 (2026-08-20)

Jimmer 迁移已完成。Entity 已转为 interface + 注解，KSP 生成扩展属性和 Draft DSL。

**本次完成：**
- Repository 从继承模式（BaseAppCrudRepository）改为组合模式（CrudRepoTemplate）
- Entity package 目录与声明对齐（entity/todo/ → package entity.todo）
- Entity 补全缺失字段（Todo.note/meta, TodoItem.note）
- DemoFacade 加 TxRunner 事务控制
- 补全 GraphQL schema 中未实现的 operation（findTodosByIds、batchUpdateTodoItems、findTodos）
- 实现 FilterGroupResolver（通用动态查询 DSL → Jimmer 谓词）
- Handler/Repo 全面使用 DGS codegen 生成的 input 类型，消除手写中间 DTO
- findByCursor 返回 Page，分页逻辑下沉到 CrudRepoTemplate

## 待办

- **其他模块迁移到 CrudRepoTemplate**: auth/ai/payment/cms/storage/app 模块的 Repo 从 BaseAppCrudRepository 继承改为 CrudRepoTemplate 组合
- **删除旧基类**: 所有模块迁移完后删除 BaseAppCrudRepository / BaseCrudRepository
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
./gradlew :core-api:compileKotlin          # 编译（含 KSP）
./gradlew :core-api:bootRun                # 运行 (需 PG + Redis)
```

- **测试框架**: JUnit 5 + Mockito + assertk
- **集成测试**: Testcontainers (PostgreSQL + Redis)
- **E2E**: WebTestClient + Testcontainers


---

## 模块分层约定（Facade + Handler）

### 规则

每个模块分为两层：

| 层 | 文件 | 职责 | 注解 |
|---|---|---|---|
| **Facade** | `XxxFacade.kt` (模块根目录) | opCtx→svcCtx 转换、事务边界控制、对外入口 | `@Service` |
| **Handler** | `handler/XxxHandler.kt` | 纯业务逻辑，接收 SvcCtx | `@Component` |

**约束：**
- Facade 是模块对外唯一入口，DataFetcher 只注入 Facade
- Handler **不注入 TxRunner**，**不构建 SvcCtx**，只接收 SvcCtx 参数
- 事务只包写操作：`tx.withTx { handler.writeOp() }`；读操作在事务外
- 外部 IO（HTTP/AI 调用）必须在外事务外，不在 `tx.withTx` 内

### 目录结构

```
modules/ai/
├── AiFacade.kt                     # @Service 对外入口
├── ScanCollectionFacade.kt          # @Service
├── handler/
│   ├── ScanHandler.kt              # @Component
│   └── ScanCollectionHandler.kt    # @Component
├── repo/                           # @Repository
└── service/                        # AI infra (非 facade/handler)
    ├── AgnesKeyStore.kt
    ├── AgnesChatClientFactory.kt
    └── SpringAiScanRunner.kt
```

简单模块：
```
modules/cms/
├── CmsFacade.kt                   # @Service
├── handler/FeedbackHandler.kt      # @Component
└── repo/
```

### 完整示例 — Scan 模块

```kotlin
// ======================== AiFacade ========================

@Service
class AiFacade(
    private val svcCtxFactory: SvcCtxFactory,
    private val scanHandler: ScanHandler,
    private val tx: TxRunner,
) {
    /** 读操作 — 无事务 */
    fun findById(opCtx: OperationContext, id: UUID): ScanRecord? =
        scanHandler.findById(svcCtxFactory.forApp(opCtx), id)

    /** 写操作 — 有事务 */
    fun updateScan(opCtx: OperationContext, input: UpdateScanInput): Boolean =
        tx.withTx(svcCtxFactory.forApp(opCtx)) { sc -> scanHandler.updateScan(sc, input) }

    /** AI 扫描 — 外部调用在事务外，DB 写入在事务内 */
    fun newScan(opCtx: OperationContext, input: NewScanInput): ScanRecord {
        val scanId = scanHandler.prepareNewScan(input)  // 无事务
        return tx.withTx(svcCtxFactory.forApp(opCtx)) { sc ->  // 事务只包 DB 写入
            scanHandler.saveNewScan(sc, scanId, input)
        }
    }
}

// ======================== Handler ========================

@Component
class ScanHandler(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val scanRepo: ScanRecordRepository,
) {
    /** 外部 AI 调用准备（无事务） */
    fun prepareNewScan(input: NewScanInput): UUID = /* ... */

    /** 事务内保存 */
    fun saveNewScan(sc: SvcCtx, scanId: UUID, input: NewScanInput): ScanRecord = /* ... */

    fun findById(sc: SvcCtx, id: UUID): ScanRecord? = scanRepo.findById(sc, appId, id)
    fun updateScan(sc: SvcCtx, input: UpdateScanInput): Boolean = { /* ... */ }
}
```

### DataFetcher（调用方）

```kotlin
@DgsComponent
class ScanFetcher(
    private val scanFacade: AiFacade,       // 只注入 Facade
    private val ctxProvider: OperationContextProvider,
) {
    @DgsMutation(field = "mutation_ai_updateScan")
    fun updateScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateScanInput): UpdateScanPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        // Step 1: 写 (有事务，Facade 内部 tx.withTx)
        val success = scanFacade.updateScan(ctx, input)
        // Step 2: 读 (无事务，可走从库/缓存)
        val record = if (success && dfe.selectionSet.fields.any { it.name == "scanRecord" }) {
            scanFacade.findById(ctx, input.id)
        } else null
        return UpdateScanPayload(success = success, scanRecord = record)
    }
}
```
