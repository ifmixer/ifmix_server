# 迁移计划：Jimmer → Exposed + DGS GraphQL + Persisted Query

> 创建时间: 2026-08-16
> 状态: Draft

## 目标

将当前 ifmix_server 从 Jimmer ORM + REST BFF 迁移到 Exposed + DGS GraphQL + Persisted Query 架构。

**核心收益：**
- 客户端按需取字段，通过 persisted query 配置化管理，不改代码不发版
- service/repo/cache 层稳定，缓存粒度为 entity-level（不按 view 拆）
- admin 端暴露完整 GraphQL，灵活探索数据
- 去掉 Jimmer 的 KSP 代码生成成本，编译速度提升

**保留不变：**
- PostgreSQL + Flyway（保留所有现有 migration）
- Redis 缓存/限流
- S3 存储
- Auth（EdDSA JWT + 社交登录）
- Spring Boot 4.1 + Jackson 3
- UUIDv7 + Base58 编码
- 枚举 SMALLINT 方案

## 架构变更

```
Before:
  REST Controller → Service → Jimmer Repository → PG

After:
  ┌──────────────────────────────────────────────────────┐
  │  GraphQL Layer (DGS)                                  │
  │  /customer/graphql  (persisted query only)            │
  │  /admin/graphql     (open introspection)              │
  │  TrustedDocumentFilter + PersistedQueryStore          │
  ├──────────────────────────────────────────────────────┤
  │  REST Layer (保留)                                    │
  │  /webhooks/iap/*    (商店回调)                        │
  │  /.well-known/jwks  (公钥暴露)                        │
  │  /customer/auth/*   (登录/刷新 token)                 │
  ├──────────────────────────────────────────────────────┤
  │  Service Layer (不变)                                 │
  │  AuthService | IapService | ScanService | ...         │
  ├──────────────────────────────────────────────────────┤
  │  Repository Layer (Exposed DSL 替代 Jimmer)           │
  │  Table 定义 + DAO，cursor 分页，多租户过滤            │
  ├──────────────────────────────────────────────────────┤
  │  PostgreSQL + Redis + S3                              │
  └──────────────────────────────────────────────────────┘
```

## 依赖变更

### 移除

```kotlin
// Jimmer 全家桶
- org.babyfish.jimmer:jimmer-spring-boot-starter
- org.babyfish.jimmer:jimmer-sql-kotlin
- org.babyfish.jimmer:jimmer-ksp (KSP processor)
// KSP plugin（如果没有其他 KSP 依赖的话）
- com.google.devtools.ksp (plugin)
```

### 新增

```kotlin
// Exposed (JetBrains ORM)
+ org.jetbrains.exposed:exposed-spring-boot-starter:0.61.0
+ org.jetbrains.exposed:exposed-java-time:0.61.0
+ org.jetbrains.exposed:exposed-json:0.61.0

// DGS GraphQL
+ platform("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:12.0.0")
+ com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter
+ com.netflix.graphql.dgs:graphql-dgs-extended-scalars
+ com.netflix.graphql.dgs:graphql-dgs-spring-boot-micrometer
+ com.jayway.jsonpath:json-path:3.0.0

// DGS Codegen (从 schema 生成 Kotlin types)
+ plugin: com.netflix.dgs.codegen (version 8.4.0)
```

## 分阶段执行

---

### Phase 1：Exposed 基础设施搭建

**目标**：在不移除 Jimmer 的情况下，并行引入 Exposed，先让一个模块跑通。

#### 1.1 添加 Exposed 依赖

`build.gradle.kts` 添加 Exposed starter + exposed-java-time + exposed-json。

#### 1.2 创建 Table 定义

在 `infra/exposed/tables/` 下逐表定义：

```kotlin
// infra/exposed/tables/ScanRecordTable.kt
object ScanRecordTable : UUIDTable("core_scan_record") {
    val appId = uuid("app_id")
    val installId = uuid("install_id").nullable()
    val userId = uuid("user_id").nullable()
    val imageKeys = jsonb<List<ImageRef>>("image_keys", jacksonMapper)
    val resultJson = jsonb<Map<String, Any?>>("result_json", jacksonMapper).nullable()
    val status = short("status")
    val clientIp = varchar("client_ip", 45).nullable()
    val lang = varchar("lang", 10).nullable()
    val country = varchar("country", 5).nullable()
    val currency = varchar("currency", 5).nullable()
    val userDisplayName = varchar("user_display_name", 255).nullable()
    val userNotes = text("user_notes").nullable()
    val collected = bool("collected").default(false)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
}
```

**所有表清单**（对应现有 Flyway schema）：

| Table 对象 | PG 表名 |
|-----------|---------|
| TodoTable | core_todo |
| TodoItemTable | core_todo_item |
| ScanRecordTable | core_scan_record |
| ScanCollectionTable | core_scan_collection |
| ScanCollectionItemTable | core_scan_collection_item |
| AppUserTable | core_app_user |
| AuthProviderIdentityTable | core_auth_provider_identity |
| AuthDeviceSecretTable | core_auth_device_secret |
| AppRefreshTokenTable | core_app_refresh_token |
| AuthTenantTable | core_auth_tenant |
| UserInstallBindingTable | core_user_install_binding |
| SubscriptionTable | core_subscription |
| StoreNotificationTable | core_store_notification |
| AppConfigRevisionTable | core_app_config_revision |
| AppInfoTable | core_app_info |
| AgnesKeyTable | core_agnes_key |
| FeedbackTable | core_feedback |
| UploadRecordTable | core_upload_record |

#### 1.3 基础 Repository 模板

```kotlin
// infra/exposed/BaseRepository.kt
abstract class BaseRepository<T : UUIDTable>(protected val table: T) {

    /** 软删除过滤 */
    protected fun Op<Boolean>.andNotDeleted(): Op<Boolean> =
        this and (table.column<Instant?>("deleted_at").isNull())

    /** appId 租户过滤 */
    protected fun Op<Boolean>.andAppScoped(appId: String): Op<Boolean> =
        this and (table.column<UUID>("app_id") eq UUID.fromString(appId))

    /** 游标分页 */
    protected fun Query.withCursor(cursor: UUID?, limit: Int): Query {
        cursor?.let { andWhere { table.id less it } }
        orderBy(table.id, SortOrder.DESC)
        limit(limit + 1)
        return this
    }
}
```

#### 1.4 迁移 Todo 模块作为 POC

选 todo 因为它最简单（CRUD + 父子关系）：
- 新建 `modules/todo/repo/TodoExposedRepo.kt`
- 验证 CRUD + cursor 分页 + 软删除
- 通过 `@ConditionalOnProperty` 切换新旧 repo 实现
- 跑通现有测试

---

### Phase 2：DGS GraphQL 层

**目标**：引入 DGS，搭建 GraphQL 入口，客户端 persisted query 机制。

#### 2.1 GraphQL Schema 定义

从 Mongo 版本搬过来并适配：

```
src/main/resources/schema/
├── common.graphqls      # scalar、directive、共享类型
├── customer.graphqls    # customer Query/Mutation
└── admin.graphqls       # admin Query/Mutation
```

关键适配点：
- ID 类型：GraphQL `ID!` = Base58 编码的 UUID 字符串
- DateTime scalar → `java.time.Instant`
- JSON scalar → `Map<String, Any?>`

#### 2.2 DGS Codegen 配置

```kotlin
// build.gradle.kts
plugins {
    id("com.netflix.dgs.codegen") version "8.4.0"
}

tasks.named<GenerateJavaTask>("generateJava") {
    language = "KOTLIN"
    packageName = "com.ifmix.api.core.graphql.generated"
    typeMapping = mutableMapOf(
        "DateTime" to "java.time.Instant",
        "JSON" to "Map<String, Any?>",
        "Long" to "kotlin.Long",
    )
}
```

#### 2.3 GraphQL 公共基础设施

从 Mongo 版搬过来，基本可以原样复用：

| 文件 | 职责 |
|------|------|
| `graphql/common/trusted/TrustedDocumentFilter.kt` | x-op-id / persisted query hash → 注入 query |
| `graphql/common/trusted/PersistedQueryStore.kt` | classpath JSON → allowlist 加载 |
| `graphql/common/context/GraphQLContextBuilder.kt` | HTTP headers → GraphQLRequestContext |
| `graphql/common/scalar/DateTimeScalar.kt` | Instant 序列化 |
| `graphql/common/directive/RequirePermission.kt` | 权限校验指令 |
| `graphql/common/monitor/OperationMetricsInstrumentation.kt` | Micrometer 埋点 |
| `graphql/router/GraphQLRouterController.kt` | /customer/graphql + /admin/graphql 双入口 |

适配点：
- `RequestContext` 从现有 `infra/http/RequestContext.kt` 复用
- AuthInterceptor 需要同时拦截 REST 和 GraphQL 路径
- Base58 UUID ↔ String 的 scalar 处理

#### 2.4 Persisted Query 文件

```
src/main/resources/graphql/persisted-queries/
├── customer.json    # { "hash": { "name": "op_name", "query": "..." } }
└── admin.json       # admin 可为空（开放模式）
```

#### 2.5 Customer Fetcher 实现

每个模块一个 fetcher 文件，调用现有 service：

```kotlin
@DgsComponent
class CustomerScanFetcher(private val scanService: ScanService) {

    @DgsQuery(field = "scan_get")
    fun getById(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): ScanRecord? {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val uuid = id.toUuidFromBase58()
        return scanService.getById(ctx.requestContext, uuid)?.toGraphQL()
    }

    @DgsQuery(field = "scan_list")
    fun list(...): ScanConnection { ... }
}
```

#### 2.6 配置

```yaml
# application.yml
graphql:
  trusted-documents:
    enabled: true   # customer 端只允许 persisted query
  # DGS 配置
dgs:
  graphql:
    path: /internal/dgs  # 内部路径，由 router controller 代理
```

---

### Phase 3：逐模块迁移 Repository 到 Exposed

按依赖关系排序：

| 顺序 | 模块 | 表数量 | 复杂度 | 说明 |
|------|------|--------|--------|------|
| 1 | todo | 2 | 低 | 父子关系，Phase 1 已完成 |
| 2 | feedback | 1 | 低 | 纯写入 |
| 3 | storage | 1 | 低 | 纯写入 |
| 4 | appconfig | 2 | 低 | 读为主 |
| 5 | scan | 3 | 中 | scan_record + collection + collection_item |
| 6 | auth | 7 | 高 | 多表交互、token 轮转 |
| 7 | iap | 2 | 中 | 订阅状态管理 |
| 8 | ai (agnes_key) | 1 | 低 | key 轮换逻辑在 service |

每个模块迁移步骤：
1. 创建 `XxxExposedRepo.kt` 实现现有 repo 接口
2. 单元测试通过（用 Testcontainers PG）
3. 删除 Jimmer 版 repo
4. 对应的 DGS fetcher 接入

---

### Phase 4：移除 Jimmer + 清理 REST BFF

#### 4.1 移除 Jimmer

- 删除 `entity/` 目录下所有 Jimmer `@Entity` 接口
- 删除 `infra/jimmer/` 目录（ClusterRegistry、AppScopedFilter、TimestampDraftInterceptor 等）
- 删除 `infra/repo/BaseAppCrudRepository.kt`、`BaseCrudRepository.kt`
- 从 `build.gradle.kts` 移除 jimmer 依赖和 KSP 配置
- 删除 `src/main/dto/` 目录

#### 4.2 精简 REST BFF

保留：
- `/customer/auth/*` — 登录/刷新不走 GraphQL（因为是获取 token 的前置步骤）
- `/webhooks/iap/*` — 外部回调，固定格式
- `/.well-known/jwks` — 标准协议

删除：
- `bff/customer/scan/*`
- `bff/customer/todo/*`
- `bff/customer/feedback/*`
- `bff/customer/iap/*` (iap_verifyPurchase 走 GraphQL mutation)
- `bff/customer/storage/*`
- `bff/app/*`

#### 4.3 移除不再需要的基础设施

- `infra/dto/` — DTO 由 DGS codegen 生成
- `infra/config/OpenApiConfig.kt` — GraphQL 有自己的 schema 文档
- `infra/config/EnvelopeSchemaCustomizer.kt` — GraphQL 不用 Envelope 包装
- `infra/http/EnvelopeResponseAdvice.kt` — 只保留给 REST 端点用

---

### Phase 5：DataLoader + 缓存

#### 5.1 DataLoader

为跨 service 的 ID 反查提供批量加载：

```kotlin
@DgsDataLoader(name = "scanRecordById")
class ScanRecordDataLoader(private val scanService: ScanService)
    : MappedBatchLoader<String, ScanRecordGraphQL> {

    override fun load(ids: Set<String>): Map<String, ScanRecordGraphQL> {
        val uuids = ids.map { it.toUuidFromBase58() }
        return scanService.findByIds(uuids)
            .associate { it.id.toBase58() to it.toGraphQL() }
    }
}
```

场景：
- `collectionItem → scanRecord`（收藏列表展示扫描详情）
- `todo → user`（admin 查看 todo 的创建者）

#### 5.2 Entity-level 缓存

```kotlin
// service 层用 @Cacheable，key = entity:id
@Cacheable(value = ["scan_record"], key = "#id.toString()")
fun getById(ctx: RequestContext, id: UUID): ScanRecordEntity? { ... }

@CacheEvict(value = ["scan_record"], key = "#id.toString()")
fun update(ctx: RequestContext, id: UUID, patch: ScanPatch) { ... }
```

因为 GraphQL 层在返回时自动裁剪字段，service 永远查全量 → cache key 只需 entity ID。

---

## 数据模型映射

### Exposed Entity（data class，替代 Jimmer interface）

```kotlin
// domain/model/ScanRecordEntity.kt
data class ScanRecordEntity(
    val id: UUID,
    val appId: UUID,
    val installId: UUID?,
    val userId: UUID?,
    val images: List<ImageRef>,
    val result: Map<String, Any?>?,
    val status: ScanStatus,
    val clientIp: String?,
    val lang: String?,
    val country: String?,
    val currency: String?,
    val userDisplayName: String?,
    val userNotes: String?,
    val collected: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

### GraphQL Type（DGS codegen 生成）

从 `schema/common.graphqls` 自动生成，无需手写。

### 映射方向

```
PG Row → ResultRow → ScanRecordEntity (Exposed toEntity 扩展)
ScanRecordEntity → ScanRecord (GraphQL type, toGraphQL 扩展)
```

---

## 枚举处理

Exposed 不像 Jimmer 有 `@EnumItem`，需要手动映射：

```kotlin
// 自定义 column type
fun Table.scanStatus(name: String) = short(name).transform(
    wrap = { ScanStatus.fromCode(it.toInt()) },
    unwrap = { it.code.toShort() }
)

// 使用
object ScanRecordTable : UUIDTable("core_scan_record") {
    val status = scanStatus("status")
}
```

---

## UUID Base58 处理

GraphQL scalar 层面处理：

```kotlin
@DgsScalar(name = "ID")  // 或自定义 scalar
class Base58IdScalar : Coercing<UUID, String> {
    override fun serialize(input: Any) = (input as UUID).toBase58()
    override fun parseValue(input: Any) = (input as String).toUuidFromBase58()
    override fun parseLiteral(input: Value<*>) = ...
}
```

或者更简单：fetcher 里手动转，ID 在 schema 里保持 `String` 语义。

---

## 风险与注意事项

| 风险 | 缓解措施 |
|------|---------|
| Exposed 对 Spring Boot 4 / Jackson 3 的兼容性 | 先验证 exposed-spring-boot-starter 在 Boot 4 下能正常初始化 |
| DGS 12 + Spring Boot 4 | Mongo 版已验证通过，可以复用配置 |
| 迁移期间两套 repo 并存 | 通过 `@ConditionalOnProperty` 切换，逐模块灰度 |
| 现有 23 个 Flyway migration | 不动，Exposed 只是读写已有 schema |
| 软删除逻辑 | BaseRepository 模板统一处理 `deleted_at IS NULL` |
| 多租户 appId 过滤 | BaseRepository 模板统一注入 |
| 读写分离路由 | Spring `@Transactional(readOnly=true)` + `AbstractRoutingDataSource` 保留 |

---

## 时间估算

| Phase | 工作量 | 说明 |
|-------|--------|------|
| Phase 1 | 2-3 天 | Exposed 基础 + todo POC |
| Phase 2 | 2-3 天 | DGS 搭建 + schema + trusted doc |
| Phase 3 | 4-5 天 | 8 模块逐个迁移 repo |
| Phase 4 | 1 天 | 清理 Jimmer + REST |
| Phase 5 | 1-2 天 | DataLoader + 缓存策略 |
| **合计** | **10-14 天** | |

---

## 验收标准

- [ ] `./gradlew compileKotlin` 无 Jimmer 依赖，无 KSP
- [ ] GraphQL customer 端：只能通过 persisted query 访问
- [ ] GraphQL admin 端：可自由查询，支持 introspection
- [ ] 所有现有测试通过（Testcontainers PG）
- [ ] Auth 登录/刷新 走 REST（保持不变）
- [ ] Webhook 回调走 REST（保持不变）
- [ ] 新增 persisted query 只需编辑 JSON 文件 + 重启（不改代码）
- [ ] 缓存命中率：entity-level cache 不区分 query shape
