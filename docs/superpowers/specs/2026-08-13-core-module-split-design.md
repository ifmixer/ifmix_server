# 设计文档：core-api / core-admin-api 模块拆分

## 概述

将当前单体 `core-api` 拆分为三个 Gradle 模块：`core-common`（共享库）、`core-api`（Customer 端）、`core-admin-api`（Admin 端）。同时重构数据源路由，用双 `KSqlClient` 实例替代 `@Transactional(readOnly=true)` 驱动的路由方式，实现显式、无事务、无 ThreadLocal 的读写分离。

### 动机

1. **流量特征不同**：Customer 端高并发简单读写，Admin 端低流量复杂分析查询 — 独立部署才能各自按需扩缩
2. **安全模型不同**：Customer 用 EdDSA JWT 非阻塞认证，Admin 需要 RBAC + 审计日志 — 混在一起增加攻击面
3. **DTO 完全不同**：Customer 返回精简视图，Admin 返回完整字段 + 统计聚合 — 各自维护 Jimmer `.dto` 文件
4. **连接资源隔离**：Customer 的高并发读不应影响 Admin 的重分析查询，反之亦然
5. **读操作不需要事务**：当前 `@Transactional(readOnly=true)` 仅为路由到 reader，但会占用 PG session 直到方法结束，浪费连接

### 当前状态

- 所有功能尚未上线，无兼容性负担
- 单模块 `core-api`，内部已按 `bff/` / `modules/` / `entity/` / `infra/` 分层
- 目前无 Admin Controller，Admin API 即将新增
- `OperationContext` 已有 `readFromReplica: Boolean` 字段但未生效
- `RepoContext` 为空壳，预留基础设施路由
- 所有 Repository 注入单个 `KSqlClient`，读写均走同一实例

---

## 模块结构

```
ifmix-server/
├── core-common/              ← 纯 library（不可独立启动）
├── core-api/                 ← Customer Spring Boot Application
├── core-admin-api/           ← Admin Spring Boot Application
├── build.gradle.kts          ← 版本集中管理
└── settings.gradle.kts
```

### 模块职责

| 模块 | 类型 | 职责 | 部署 |
|------|------|------|------|
| `core-common` | `java-library` | Entity、Repository、共享 Service、Infra | 不独立部署 |
| `core-api` | Spring Boot `application` | Customer BFF + Customer 专有 Service + IP 限流 | 高流量，auto-scale |
| `core-admin-api` | Spring Boot `application` | Admin BFF + Admin 专有 Service + Webhook + RBAC | 低流量，固定副本 |

### 依赖关系

```
core-api  ──────┐
                ├──→  core-common
core-admin-api ─┘
```

---

## 目录结构

### core-common

```
core-common/src/main/kotlin/com/ifmix/api/core/
├── entity/                         ← 全部 Jimmer Entity
│   ├── auth/
│   ├── scan/
│   ├── iap/
│   ├── todo/
│   ├── feedback/
│   ├── appconfig/
│   ├── ai/
│   ├── storage/
│   └── enums/
├── repo/                           ← 全部 Repository
│   ├── base/                       ← BaseCrudRepository, BaseAppCrudRepository
│   ├── auth/
│   ├── scan/
│   ├── iap/
│   ├── todo/
│   ├── feedback/
│   ├── ai/
│   ├── storage/
│   └── app/
├── service/                        ← 共享原子 Service（基础 CRUD 编排）
│   ├── auth/                       ← AuthService（登录、token 签发）
│   ├── iap/                        ← IapService（验购、通知处理）
│   └── app/                        ← AppConfigService
└── infra/                          ← 全部基础设施
    ├── auth/                       ← JWT 签发/验签, Hashing
    ├── ai/                         ← Spring AI, AgnesKeyStore, ScanRunner
    ├── db/                         ← RepoContext, UUIDv7, CursorQueryInput, Page
    ├── http/                       ← Envelope, ApiError, ErrorCode, OperationContext
    ├── jimmer/                     ← ClusterRegistry, ReadWriteRouting, Flyway
    ├── ratelimit/                  ← RateLimiter, Tier, TierResolver
    ├── redis/                      ← Redis 配置
    ├── storage/                    ← ObjectStorage 接口 + S3 实现
    ├── codec/                      ← Base58
    └── config/                     ← JacksonConfig, 公共 bean
```

### core-api

```
core-api/src/main/kotlin/com/ifmix/api/core/
├── CoreApplication.kt
├── bff/
│   ├── customer/                   ← 现有全部 Customer Controller
│   │   ├── auth/
│   │   ├── scan/
│   │   ├── todo/
│   │   ├── iap/
│   │   ├── storage/
│   │   └── feedback/
│   └── wellknown/                  ← JWKS
├── service/                        ← Customer 专有 Service
│   ├── scan/                       ← CustomerScanService
│   ├── todo/                       ← CustomerTodoService
│   └── collection/                 ← CustomerCollectionService
└── config/
    ├── CustomerWebConfig.kt        ← AuthInterceptor（非阻塞）
    └── CustomerRateLimitConfig.kt  ← IP 限流（后续）

core-api/src/main/dto/             ← Customer Jimmer DTO
core-api/src/test/                  ← Customer UT + E2E
```

### core-admin-api

```
core-admin-api/src/main/kotlin/com/ifmix/api/admin/
├── AdminApplication.kt
├── bff/
│   ├── admin/                      ← Admin 管理端 Controller
│   │   ├── AdminUserController
│   │   ├── AdminScanController
│   │   ├── AdminIapController
│   │   └── AdminAgnesKeyController
│   ├── app/                        ← App 配置管理（从 core-api 迁入）
│   │   └── AppConfigController     ← /app/core/mutation/**
│   └── webhooks/                   ← Apple/Google 商店回调
│       └── WebhookController
├── service/                        ← Admin 专有 Service
│   ├── AdminScanService            ← 统计、批量、重跑
│   ├── AdminUserService            ← 用户管理、封禁
│   └── AdminIapService             ← 订阅管理、退款
└── config/
    ├── AdminWebConfig.kt           ← Admin 鉴权拦截器（阻塞式）
    ├── AdminSecurityConfig.kt      ← RBAC / 权限
    └── AdminAuditConfig.kt         ← 审计日志

core-admin-api/src/main/dto/       ← Admin Jimmer DTO
core-admin-api/src/test/            ← Admin UT + E2E
```

> **注**：`/app/` 路由属于管理端操作（创建/切换 app 配置版本），归 core-admin-api。

---

## Gradle 配置

### settings.gradle.kts

```kotlin
rootProject.name = "ifmix-server"

include("core-common", "core-api", "core-admin-api")
```

### build.gradle.kts (root)

```kotlin
plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.spring") version "2.3.10" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}

allprojects {
    group = "com.ifmix"
    version = "0.0.1-SNAPSHOT"
    repositories { mavenCentral() }
}

extra["jimmerVersion"] = "0.11.5"
```

### core-common/build.gradle.kts

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
}

// 不加 org.springframework.boot plugin — 不打 fat jar

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlin:kotlin-reflect")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Jimmer
    val jimmerVersion = rootProject.extra["jimmerVersion"] as String
    api("org.babyfish.jimmer:jimmer-spring-boot-starter:$jimmerVersion")
    api("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    // PostgreSQL + Flyway
    api("org.postgresql:postgresql")
    api("org.flywaydb:flyway-core")
    api("org.flywaydb:flyway-database-postgresql")

    // Auth
    api("org.springframework.security:spring-security-oauth2-jose")
    api("com.nimbusds:nimbus-jose-jwt:9.40")
    api("com.google.crypto.tink:tink:1.15.0")

    // S3
    api(platform("software.amazon.awssdk:bom:2.31.7"))
    api("software.amazon.awssdk:s3")

    // Spring AI
    api(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    api("org.springframework.ai:spring-ai-starter-model-openai") {
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }

    // UUIDv7
    api("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    // Redis
    api("org.springframework.boot:spring-boot-starter-data-redis")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
}

ksp {
    arg("jimmer.language", "kotlin")
    // core-common 生成 Entity 元数据（Fetcher/Draft/Table/Props）
    // 不设 jimmer.dto.dirs 或设为空 — DTO 由下游模块各自管理
}
```

### core-api/build.gradle.kts

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":core-common"))

    // KSP 仅为处理本模块的 .dto 文件
    val jimmerVersion = rootProject.extra["jimmerVersion"] as String
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    // Customer 专有依赖（如有）
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux") // WebTestClient
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
    testImplementation("org.wiremock:wiremock-standalone:3.12.1")
    testImplementation("com.h2database:h2")
}

ksp {
    arg("jimmer.language", "kotlin")
    arg("jimmer.dto.dirs", "src/main/dto")
    arg("jimmer.dto.defaultNullableInputModifier", "fuzzy")
}
```

### core-admin-api/build.gradle.kts

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":core-common"))

    val jimmerVersion = rootProject.extra["jimmerVersion"] as String
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    // Admin 专有依赖
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    // 后续可加: spring-security, audit log 等

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
}

ksp {
    arg("jimmer.language", "kotlin")
    arg("jimmer.dto.dirs", "src/main/dto")
    arg("jimmer.dto.defaultNullableInputModifier", "fuzzy")
}
```

---

## KSP 多模块工作方式

```
core-common (KSP)
  ├── 输入: entity/*.kt (@Entity 注解)
  ├── 输出: Fetcher, Draft, Table, Props 等元数据类
  └── 不处理 .dto 文件

core-api (KSP)
  ├── 输入: src/main/dto/**/*.dto
  ├── 依赖: core-common 编译产物（Entity 元数据）
  └── 输出: Customer DTO class（如 ScanRecordView, TodoListView）

core-admin-api (KSP)
  ├── 输入: src/main/dto/**/*.dto
  ├── 依赖: core-common 编译产物（Entity 元数据）
  └── 输出: Admin DTO class（如 AdminScanDetailView, AdminUserFullView）
```

同一个 Entity（如 `ScanRecord`）可以在两个模块分别定义不同的 DTO：

```
// core-api/src/main/dto/.../ScanRecord.dto
ScanRecordView {
    id
    imageUrl
    status
    result
    createdAt
}

// core-admin-api/src/main/dto/.../ScanRecord.dto
AdminScanRecordView {
    id
    appId
    userId
    installId
    imageUrl
    status
    result
    aiModel
    aiResponseRaw
    createdAt
    updatedAt
}
```

---

## 数据源路由：多集群 + 读写分离

### 问题

当前方案 `@Transactional(readOnly=true)` + 单集群 `ReadWriteRoutingDataSource`：
- 读操作开启事务 → 占用 PG session 直到方法返回 → 浪费连接池
- 依赖 `TransactionSynchronizationManager`（ThreadLocal） → Virtual Thread / 协程并行时丢失上下文
- 单条简单查询不需要事务语义（MVCC 快照、一致性读）
- 未来不同 appId/authTenantId 可能在不同 PG cluster — 当前架构无法支持

### 方案：多集群注册 + 双 KSqlClient(reader/writer) + ctx 透传 clusterId

**核心思想**：
1. 系统启动时注册 N 个 cluster，每个 cluster 持有 reader + writer 两个 KSqlClient
2. 请求入口根据 appId / authTenantId 解析出 clusterId，记录到 OperationContext
3. OperationContext 映射到 RepoContext（含 clusterId + preferReader）
4. Repository 执行时根据 RepoContext 选择具体的 KSqlClient 实例
5. 无 ThreadLocal、无 @Transactional 驱动路由

### 配置模型

```yaml
# application.yml
app:
  clusters:
    default:
      writer:
        jdbc-url: jdbc:postgresql://writer-1:5432/ifmix_core
        username: postgres
        password: xxx
        maximum-pool-size: 20
      reader:
        jdbc-url: jdbc:postgresql://reader-1:5432/ifmix_core
        username: postgres
        password: xxx
        maximum-pool-size: 30
    # 未来扩展：
    # cluster-2:
    #   writer: ...
    #   reader: ...

  # appId / tenantId → clusterId 映射（初期只有 default）
  routing:
    default-cluster: default
    # 未来：
    # tenant-map:
    #   "tenant-abc": cluster-2
```

### ClusterProperties

```kotlin
@ConfigurationProperties(prefix = "app")
data class AppDataSourceProperties(
    val clusters: Map<String, ClusterProps> = mapOf("default" to ClusterProps()),
    val routing: RoutingProps = RoutingProps(),
) {
    data class ClusterProps(
        val writer: DataSourceProps = DataSourceProps(),
        val reader: DataSourceProps = DataSourceProps(),
    )
    data class DataSourceProps(
        val jdbcUrl: String = "",
        val username: String = "",
        val password: String = "",
        val maximumPoolSize: Int = 10,
    )
    data class RoutingProps(
        val defaultCluster: String = "default",
        // 未来: val tenantMap: Map<String, String> = emptyMap()
    )
}
```

### Cluster 数据结构

```kotlin
/**
 * 一个物理 PG 集群：一对 reader/writer KSqlClient。
 */
class Cluster(
    val id: String,
    val writerDataSource: HikariDataSource,
    val readerDataSource: HikariDataSource,
    val writerClient: KSqlClient,
    val readerClient: KSqlClient,
) {
    fun sql(preferReader: Boolean): KSqlClient =
        if (preferReader) readerClient else writerClient

    fun close() {
        writerDataSource.close()
        readerDataSource.close()
    }
}
```

### ClusterRegistry

```kotlin
@Component
class ClusterRegistry(
    private val props: AppDataSourceProperties,
    private val draftInterceptors: List<DraftInterceptor<*, *>>,
    @Value("\${app.show-sql:false}") private val showSql: Boolean,
) {
    private val clusters: Map<String, Cluster> by lazy {
        props.clusters.mapValues { (id, clusterProps) ->
            val writerDs = createDataSource(clusterProps.writer, "pg-$id-writer")
            val readerDs = createDataSource(clusterProps.reader, "pg-$id-reader")
            Cluster(
                id = id,
                writerDataSource = writerDs,
                readerDataSource = readerDs,
                writerClient = buildClient(writerDs, autoCommit = false),
                readerClient = buildClient(readerDs, autoCommit = true),
            )
        }
    }

    val defaultClusterId: String get() = props.routing.defaultCluster

    fun getCluster(clusterId: String): Cluster =
        clusters[clusterId] ?: clusters[defaultClusterId]
            ?: error("Cluster not found: $clusterId")

    /** 默认集群（向后兼容、Flyway 等使用） */
    val defaultCluster: Cluster get() = getCluster(defaultClusterId)

    /** Flyway 只跑在 default cluster 的 writer 上 */
    val flywayDataSource: HikariDataSource get() = defaultCluster.writerDataSource

    /** Spring TransactionManager 绑定到 default cluster writer（写事务场景） */
    val transactionDataSource: DataSource get() = defaultCluster.writerDataSource

    @PreDestroy
    fun destroy() {
        clusters.values.forEach { it.close() }
    }

    private fun buildClient(ds: HikariDataSource, autoCommit: Boolean): KSqlClient {
        return newKSqlClient {
            setConnectionManager { block ->
                ds.connection.use { conn ->
                    if (autoCommit) conn.autoCommit = true
                    block(conn)
                }
            }
            setDialect(PostgresDialect())
            if (showSql) {
                setExecutor(Executor.log())
            }
            for (interceptor in draftInterceptors) {
                addDraftInterceptor(interceptor)
            }
        }
    }

    private fun createDataSource(props: AppDataSourceProperties.DataSourceProps, poolName: String): HikariDataSource {
        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = props.jdbcUrl
            username = props.username
            password = props.password
            maximumPoolSize = props.maximumPoolSize
            this.poolName = poolName
        })
    }
}
```

### ClusterRouter — 请求级路由决策

```kotlin
/**
 * 根据 appId / tenantId 解析目标 cluster。
 * 初期所有请求走 default cluster；未来扩展 tenant → cluster 映射。
 */
@Component
class ClusterRouter(private val props: AppDataSourceProperties) {

    fun resolve(appId: UUID?): String {
        // 初期：所有 app 走 default
        return props.routing.defaultCluster

        // 未来扩展：
        // val tenantId = lookupTenantId(appId)
        // return props.routing.tenantMap[tenantId] ?: props.routing.defaultCluster
    }
}
```

### RepoContext 改造

```kotlin
package com.ifmix.api.core.infra.db

/**
 * Repository 层基础设施上下文 — 决定数据路由。
 *
 * - clusterId: 目标 PG 集群（多集群路由）
 * - preferReader: true = reader 节点（autoCommit，无事务）; false = writer 节点
 */
data class RepoContext(
    val clusterId: String,
    val preferReader: Boolean = false,
) {
    companion object {
        /** 工厂方法：初期 default cluster */
        fun of(clusterId: String, preferReader: Boolean = false) =
            RepoContext(clusterId = clusterId, preferReader = preferReader)
    }
}
```

### OperationContext 改造

```kotlin
data class OperationContext(
    val appId: UUID? = null,
    val installId: UUID? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: UUID? = null,
    val clientIp: String? = null,
    /** 目标集群 ID（请求入口由 ClusterRouter 解析） */
    val clusterId: String = "default",
    /** true = 读操作走 reader 节点 */
    val readFromReplica: Boolean = false,
) {
    /** 映射为 Repository 层上下文 */
    val repoCtx: RepoContext
        get() = RepoContext(clusterId = clusterId, preferReader = readFromReplica)
}
```

### Interceptor 填充 clusterId

```kotlin
class OperationContextArgumentResolver(
    private val clusterRouter: ClusterRouter,
) : HandlerMethodArgumentResolver {

    override fun resolveArgument(...): OperationContext {
        val appId = request.getHeader("x-app-id")?.let { UUID.fromString(it) }
        val clusterId = clusterRouter.resolve(appId)

        return OperationContext(
            appId = appId,
            clusterId = clusterId,
            // ... 其他字段
        )
    }
}
```

### BaseRepository 改造

```kotlin
abstract class BaseCrudRepository<E : Any>(
    private val clusterRegistry: ClusterRegistry,
    protected val entityType: KClass<E>,
) {
    /** 根据 ctx 选择目标集群 + 读/写 client */
    protected fun sql(ctx: RepoContext): KSqlClient =
        clusterRegistry.getCluster(ctx.clusterId).sql(ctx.preferReader)

    /** 强制 writer（写操作） */
    protected fun writerSql(ctx: RepoContext): KSqlClient =
        clusterRegistry.getCluster(ctx.clusterId).sql(preferReader = false)

    open fun findById(ctx: RepoContext, id: UUID): E? =
        sql(ctx).entities.findById(entityType, id)

    open fun save(ctx: RepoContext, entity: E): E =
        writerSql(ctx).entities.save(entity).modifiedEntity

    // ... 其余方法同理
}
```

### Service 用法

```kotlin
@Service
class CustomerScanService(private val scanRepo: ScanRecordRepository) {

    fun listMyScans(ctx: OperationContext, cursor: CursorInput): Page<ScanRecordView> {
        // ctx.clusterId 已由 interceptor 填充
        // readFromReplica=true → repoCtx.preferReader=true → cluster.readerClient
        val readCtx = ctx.copy(readFromReplica = true)
        return scanRepo.findViewByCursor(readCtx.repoCtx, readCtx.appId!!, ScanRecordView::class, cursor)
    }

    @Transactional
    fun createScan(ctx: OperationContext, req: CreateScanRequest): ScanRecordView {
        // readFromReplica=false → cluster.writerClient
        val record = scanRepo.save(ctx.repoCtx, buildRecord(ctx, req))
        return scanRepo.findById(ctx.repoCtx, record.id)!!
    }
}
```

### 连接行为对比

| 场景 | 集群选择 | 节点 | 事务 | 连接占用 |
|------|----------|------|------|----------|
| 读（preferReader=true） | ctx.clusterId | reader | 无（autoCommit） | 仅 SQL 执行期间 |
| 写（preferReader=false） | ctx.clusterId | writer | @Transactional | 事务结束前 |
| 写后读（同一事务） | ctx.clusterId | writer | @Transactional | 事务结束前 |

### 写后读一致性规则

- 同一请求先写后读：不设 `readFromReplica = true`，从 writer 读，避免主从延迟
- 纯读接口：`readFromReplica = true`，走 reader
- Admin 重分析查询：`readFromReplica = true`，走 reader（可容忍秒级延迟）

### 多集群 @Transactional 注意事项

Spring `@Transactional` 绑定到一个 `PlatformTransactionManager`（一个 DataSource）。多集群场景下：

- **初期**（单集群）：TransactionManager 绑定 default cluster writer，正常工作
- **多集群扩展后**：需要按 clusterId 创建多个 TransactionManager + `@Transactional("tm-cluster-2")` 或手动编程式事务
- **跨集群事务**：不支持（也不需要）— 一个请求只落一个集群

初期不需要处理此复杂度，预留扩展点即可。

---

## 集群拓扑：Global Cluster + Tenant Cluster

### 设计

Auth 模块跨所有 app/tenant 共享（一个用户可登录多个 app），适合放全局集群：

| 集群 | 存储内容 | 特征 |
|------|----------|------|
| **global** | auth 相关表（auth_tenant, auth_identity, auth_provider_identity, auth_device_secret, app_user, app_refresh_token）| 全局唯一、跨 app 共享、写少读多 |
| **tenant-{x}** | 业务数据表（scan_record, scan_collection, todo, feedback, subscription, store_notification, agnes_key, upload_record, app_config_revision, app_info）| 按 app/tenant 隔离、可独立扩缩 |

### 当前阶段：Auth 放 core-api，但保持隔离

Auth 模块**暂时留在 core-api 中**（初期单集群，无需物理分离），但代码组织上保持独立性，为将来拆出做准备：

- Auth 的 Entity、Repository、Service 在 core-common 中独立成包（`entity/auth/`, `modules/auth/`）
- Auth Repository **始终使用 `ctx.globalRepoCtx`**（当前 global = default，但调用方式已就位）
- 业务 Repository 使用 `ctx.repoCtx`
- **禁止**业务 Entity 对 Auth Entity 建立 Jimmer `@ManyToOne` / `@OneToMany`——即使当前同库也不允许，从代码层面强制隔离
- Auth Service 只依赖 Auth Repository，**不注入**业务 Repository
- 业务 Service 需要 auth 数据时，通过注入 Auth Service（接口级依赖）获取，不直接访问 auth repo

这样将来独立 Auth 服务时：
1. 代码直接搬走（无交叉依赖）
2. 集群配置 `global` 指向独立库即可
3. 无需改任何调用方——`ctx.globalRepoCtx` 已隔离

```yaml
# 初期（单库）
app:
  clusters:
    default:
      writer: { jdbc-url: "jdbc:postgresql://localhost:5432/ifmix_core" }
      reader: { jdbc-url: "jdbc:postgresql://localhost:5432/ifmix_core" }
  routing:
    global-cluster: default    # ← auth 也走 default，同库
    default-cluster: default

# 未来（auth 独立库）
app:
  clusters:
    global:
      writer: { jdbc-url: "jdbc:postgresql://global-writer:5432/ifmix_auth" }
      reader: { jdbc-url: "jdbc:postgresql://global-reader:5432/ifmix_auth" }
    default:
      writer: { jdbc-url: "jdbc:postgresql://tenant-writer:5432/ifmix_core" }
      reader: { jdbc-url: "jdbc:postgresql://tenant-reader:5432/ifmix_core" }
  routing:
    global-cluster: global
    default-cluster: default
```

### Jimmer Join 约束

**跨集群的实体不能用 Jimmer `@ManyToOne` / `@OneToMany` 关联**——SQL JOIN 无法跨库执行。

当前 auth 实体之间存在的关联（全部在 global cluster 内部，可保留）：

```
AuthTenant ←── AuthIdentity ←── AuthProviderIdentity
                    ↑                    
                    ├── AuthDeviceSecret
                    ↑
              AppUser ←── AppRefreshToken
```

这些关联全在 auth 域内，同一个 global cluster，**Jimmer JOIN 保留不动**。

**需要解开的场景**：如果未来业务表需要关联 auth 表（如 ScanRecord 想 JOIN AppUser 拿 displayName），**不能**用 Jimmer 关联，必须：

```kotlin
// ❌ 不可以（跨集群 JOIN）
interface ScanRecord {
    @ManyToOne
    val user: AppUser  // ScanRecord 在 tenant cluster, AppUser 在 global cluster
}

// ✅ 正确做法：Service 层逻辑聚合
@Service
class CustomerScanService(
    private val scanRepo: ScanRecordRepository,    // tenant cluster
    private val appUserRepo: AppUserRepository,    // global cluster
) {
    fun getScanWithUser(ctx: OperationContext, scanId: UUID): ScanDetailDto {
        val scan = scanRepo.findById(ctx.repoCtx, scanId)!!
        val user = scan.userId?.let {
            appUserRepo.findById(ctx.globalRepoCtx, it)
        }
        return ScanDetailDto(scan = scan, userName = user?.authIdentity?.displayName)
    }
}
```

### OperationContext 扩展

```kotlin
data class OperationContext(
    val appId: UUID? = null,
    val userId: UUID? = null,
    val installId: UUID? = null,
    // ...
    /** 业务数据集群 ID（按 appId 路由） */
    val clusterId: String = "default",
    /** 全局集群 ID（auth 等共享数据） */
    val globalClusterId: String = "global",
    /** true = 读操作走 reader 节点 */
    val readFromReplica: Boolean = false,
) {
    /** 业务数据 RepoContext */
    val repoCtx: RepoContext
        get() = RepoContext(clusterId = clusterId, preferReader = readFromReplica)

    /** Auth / 全局数据 RepoContext */
    val globalRepoCtx: RepoContext
        get() = RepoContext(clusterId = globalClusterId, preferReader = readFromReplica)
}
```

### Repository 层使用

```kotlin
// Auth repo — 始终用 globalRepoCtx
@Repository
class AppUserRepository(private val clusterRegistry: ClusterRegistry, ...)
    : BaseCrudRepository<AppUser>(...) {

    fun findByAuthIdentityId(ctx: RepoContext, authIdentityId: UUID): AppUser? {
        // ctx 应为 globalRepoCtx
        return sql(ctx).createQuery(AppUser::class) { ... }
    }
}

// Scan repo — 用业务 repoCtx
@Repository  
class ScanRecordRepository(private val clusterRegistry: ClusterRegistry, ...)
    : BaseAppCrudRepository<ScanRecord>(...) {

    fun findByCursor(ctx: RepoContext, appId: UUID, input: CursorInput) = ...
}
```

### Service 层调用规范

```kotlin
@Service
class AuthService(
    private val authIdentityRepo: AuthIdentityRepository,
    private val appUserRepo: AppUserRepository,
    private val refreshTokenRepo: AppRefreshTokenRepository,
) {
    // Auth 操作全走 global cluster
    fun login(ctx: OperationContext, ...): TokenPair {
        val identity = authIdentityRepo.findByEmail(ctx.globalRepoCtx, email)
        // ...
    }
}

@Service
class CustomerScanService(
    private val scanRepo: ScanRecordRepository,
) {
    // 业务操作走 tenant cluster
    fun listMyScans(ctx: OperationContext, cursor: CursorInput): Page<ScanRecordView> {
        val readCtx = ctx.copy(readFromReplica = true)
        return scanRepo.findViewByCursor(readCtx.repoCtx, ...)
    }
}
```

### 表归属总结

| 集群 | 表 |
|------|-----|
| global | `core_auth_tenant`, `core_auth_identity`, `core_auth_provider_identity`, `core_auth_device_secret`, `core_app_user`, `core_app_refresh_token` |
| tenant (default) | `core_scan_record`, `core_scan_collection`, `core_scan_collection_item`, `core_todo`, `core_todo_item`, `core_feedback`, `core_subscription`, `core_store_notification`, `core_agnes_key`, `core_upload_record`, `core_app_config_revision`, `core_app_info` |

### Flyway 策略

- global cluster：独立 migration 目录 `db/migration/global/`
- tenant cluster：`db/migration/tenant/`
- 启动时 Flyway 分别对两个 cluster 的 writer 执行

```kotlin
@Component
class FlywayInitializer(private val clusterRegistry: ClusterRegistry) {
    @PostConstruct
    fun migrate() {
        // Global cluster
        Flyway.configure()
            .dataSource(clusterRegistry.getCluster("global").writerDataSource)
            .locations("classpath:db/migration/global")
            .load().migrate()

        // Default tenant cluster
        Flyway.configure()
            .dataSource(clusterRegistry.getCluster("default").writerDataSource)
            .locations("classpath:db/migration/tenant")
            .load().migrate()
    }
}
```

### 数据流全貌

```
Request
  │ x-app-id header
  ▼
Interceptor (OperationContextArgumentResolver)
  │ ClusterRouter.resolve(appId) → clusterId
  ▼
OperationContext { appId, clusterId, readFromReplica }
  │
  ▼
Service
  │ ctx.repoCtx → RepoContext { clusterId, preferReader }
  ▼
Repository.sql(ctx)
  │ ClusterRegistry.getCluster(clusterId).sql(preferReader)
  ▼
KSqlClient (reader or writer of target cluster)
  │
  ▼
PostgreSQL (target cluster, reader or writer node)
```

---

## Webhook 归属：core-admin-api

### 决策理由

| 维度 | 分析 |
|------|------|
| 流量特征 | 低频事件驱动，和 admin 一样"低流量但重要" |
| 安全模型 | JWS 验签 / token 验证，独立于用户 JWT |
| 故障隔离 | Apple burst 续订不应影响 customer latency |
| 运维属性 | 需要审计日志、重试、告警 — admin 服务已规划这些 |
| 域名规划 | `admin.xxx.com/webhooks/iap/*`，不暴露给客户端 |

### API 路由

```
core-admin-api:
  POST /webhooks/iap/apple        ← Apple Server Notifications v2
  POST /webhooks/iap/google       ← Google Play Pub/Sub
  PUT  /query/admin/...           ← Admin 查询
  POST /mutation/admin/...        ← Admin 修改
```

---

## 认证差异化

| 维度 | core-api (Customer) | core-admin-api (Admin) |
|------|---------------------|------------------------|
| 认证方式 | EdDSA JWT（现有） | 独立 Admin Token / OAuth2 SSO |
| 拦截策略 | 非阻塞（保持） | 阻塞式（必须登录） |
| 权限模型 | 无 RBAC（user 只操作自己的数据） | RBAC（角色 + 权限） |
| 多租户 | appId 隔离 | 超级管理员可跨 app |
| Webhook | — | 验签（JWS / token），无用户 JWT |

---

## Service 层拆分原则

```
core-common/service/     → 共享原子操作（多模块复用）
core-api/service/        → Customer 专有编排（限流、配额扣减、用户隔离）
core-admin-api/service/  → Admin 专有编排（跨用户、批量、统计、审计）
```

### 划分标准

| 归属 | 条件 |
|------|------|
| core-common | 两端都需要调用的基础能力（如 AuthService 签发 token、IapService 处理通知） |
| core-api | 只有 Customer 需要的逻辑（如限流检查、扫描创建 + AI 调用编排） |
| core-admin-api | 只有 Admin 需要的逻辑（如统计聚合、批量操作、用户管理） |

### 示例

```kotlin
// core-common：原子能力
@Service
class AuthService(...) {
    fun login(provider: Provider, idToken: String): TokenPair = ...
    fun refresh(refreshToken: String): TokenPair = ...
    fun revokeUser(userId: UUID) = ...  // admin 也能调
}

// core-api：Customer 编排
@Service
class CustomerScanService(...) {
    fun createScan(ctx: OperationContext, req: CreateScanRequest): ScanRecordView {
        rateLimiter.checkAndDecrement(ctx)
        val record = scanRepo.save(ctx.repoCtx, ...)
        scanRunner.runAsync(record.id)
        return ...
    }
}

// core-admin-api：Admin 编排
@Service
class AdminScanService(...) {
    fun listAllScans(ctx: OperationContext, filter: AdminScanFilter): Page<AdminScanView> {
        // 跨用户、可按 status/date/userId 过滤
        return scanRepo.findByFilter(ctx.repoCtx, filter)
    }

    fun getScanStats(appId: UUID, dateRange: DateRange): ScanStatsDto {
        // 重聚合查询，走 reader
        return scanRepo.aggregateStats(RepoContext.READER, appId, dateRange)
    }

    fun rerunFailedScan(scanId: UUID): AdminScanView {
        // 管理员强制重跑
    }
}
```

---

## 测试策略

| 模块 | 测试类型 | 内容 |
|------|----------|------|
| core-common | 单元测试 | Service 逻辑、Repository 集成测试 (Testcontainers PG) |
| core-api | Controller UT + E2E | Customer 接口完整流程 (WebTestClient + Testcontainers) |
| core-admin-api | Controller UT + E2E | Admin 接口 + Webhook 完整流程 |

每个模块独立跑 `./gradlew :module:test`。

---

## 部署拓扑

```
                         ┌───────────────────┐
   Mobile App ─────────→ │   core-api ×N     │  (auto-scale, IP 限流)
                         │   api.xxx.com     │
                         └─────────┬─────────┘
                                   │
                         ┌─────────▼─────────────────────────┐
                         │         Cluster Registry           │
                         │                                    │
                         │  ┌─ cluster "default" ──────────┐  │
                         │  │  Writer (PG primary)         │  │
                         │  │  Reader (PG replica)         │  │
                         │  └──────────────────────────────┘  │
                         │                                    │
                         │  ┌─ cluster "xxx" (future) ─────┐  │
                         │  │  Writer (PG primary)         │  │
                         │  │  Reader (PG replica)         │  │
                         │  └──────────────────────────────┘  │
                         │                                    │
                         │         Redis                      │
                         └─────────▲─────────────────────────┘
                                   │
   Admin Panel ────────→ ┌─────────┴─────────┐
   Apple/Google Store ─→ │ core-admin-api ×1  │  (固定副本, 审计日志)
                         │ admin.xxx.com     │
                         └───────────────────┘
```

请求路由流程：
1. 请求到达 → Interceptor 提取 `x-app-id`
2. `ClusterRouter.resolve(appId)` → `clusterId`
3. `OperationContext { clusterId, readFromReplica }` 构建完毕
4. Service → Repo → `clusterRegistry.getCluster(clusterId).sql(preferReader)` → 目标 KSqlClient

---

## Flyway Migration 策略

Migration 文件放 `core-common/src/main/resources/db/migration/`。

- `core-api` 启动时执行 Flyway migrate（主服务，先启动）
- `core-admin-api` 设置 `spring.flyway.enabled=false`（不重复执行）

或者后续抽为独立 migration job（CI/CD 中先跑 migration 再部署服务）。

---

## Spring Boot 扫描配置

```kotlin
// core-api
@SpringBootApplication(scanBasePackages = ["com.ifmix.api.core"])
class CoreApplication

// core-admin-api
@SpringBootApplication(scanBasePackages = ["com.ifmix.api.core", "com.ifmix.api.admin"])
class AdminApplication
```

`core-admin-api` 需要扫描 `com.ifmix.api.core`（core-common 的 bean）+ `com.ifmix.api.admin`（自己的 bean）。

### 条件加载

Admin 不需要 AI 功能时，通过 `@ConditionalOnProperty` 控制：

```yaml
# core-admin-api/application.yml
spring.ai.openai.enabled: false
app.scan-runner.enabled: false
```

---

## 迁移步骤

1. **创建 `core-common` 模块**
   - 新建 `core-common/build.gradle.kts`
   - 将 `entity/`, `modules/`(repo+共享service), `infra/` 移入
   - 将 Flyway migration 文件移入

2. **重构数据源为多集群 + 双 KSqlClient**
   - 改造 `ClusterProperties` → `AppDataSourceProperties`（支持多集群配置）
   - 改造 `ClusterRegistry`：按 cluster 配置创建 reader/writer KSqlClient 对
   - 新建 `Cluster` 数据类 + `ClusterRouter` 组件
   - 改造 `RepoContext` 加 `clusterId` + `preferReader`
   - 改造 `OperationContext` 加 `clusterId`，映射到 `repoCtx`
   - 改造 `BaseCrudRepository` / `BaseAppCrudRepository`：注入 `ClusterRegistry`，按 ctx 选 client
   - 删除 `ReadWriteRoutingDataSource`（不再需要）

3. **瘦身 `core-api`**
   - 只保留 `bff/customer/`, `bff/wellknown/`, Customer Service, `CoreApplication.kt`
   - Customer `.dto` 文件保留在 `core-api/src/main/dto/`
   - 依赖 `project(":core-common")`
   - 移除 `bff/app/`（归 admin）
   - 移除 `bff/webhooks/`（归 admin）

4. **验证 core-api 编译 + 测试通过**

5. **创建 `core-admin-api` 骨架**
   - `AdminApplication.kt` + `application.yml`
   - 移入 `WebhookController` + `AppConfigController`
   - 创建 Admin `.dto` 文件

6. **实现 Admin 认证和第一个 Admin Controller**

---

## 已确定的设计决策

1. **三模块拆分**：core-common (library) + core-api (app) + core-admin-api (app)
2. **多集群路由**：按 appId/authTenantId 解析 clusterId，记录到 OperationContext → RepoContext，repo 执行时按 clusterId 选目标集群
3. **Global + Tenant 集群拓扑**：auth 表放 global cluster（跨 app 共享），业务表放 tenant cluster（按 app 隔离）；初期 global = default 同库，代码层面先行隔离
4. **Auth 暂留 core-api 但强制隔离**：Auth repo 始终用 `globalRepoCtx`，禁止业务 Entity 对 Auth Entity 建 Jimmer 关联，Auth Service 不依赖业务 repo — 为将来独立 auth 服务做准备
5. **双 KSqlClient per cluster**：每个 cluster 持有 reader (autoCommit, 无 TX) + writer (@Transactional)，通过 RepoContext.preferReader 选择
6. **无 ThreadLocal**：路由决策（clusterId + preferReader）随 ctx 参数显式传递
7. **禁止跨集群 Jimmer JOIN**：global 和 tenant 之间不能用 @ManyToOne/@OneToMany 关联，只能 Service 层逻辑聚合
8. **KSP 分模块**：Entity 元数据在 core-common 生成，DTO 在各 app 模块各自生成
9. **Webhook 归 admin**：流量隔离 + 运维属性一致 + 审计日志
10. **app/ 路由归 admin**：App 配置管理属管理端操作
11. **Service 三层**：common（原子）、customer（编排）、admin（编排）各自独立
12. **UT 分模块**：每个 Gradle 模块维护自己的测试
13. **两端都走读写分离**：Customer 高频简单读 + Admin 重分析查询 均走 reader
14. **后续 core-api 加 IP 限流**：独立于现有 Redis 日窗口限流
