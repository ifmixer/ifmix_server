# jOOQ → Jimmer 迁移实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 ORM 层从 jOOQ 替换为 Jimmer，保留现有 GraphQL (DGS) + TxRunner + ClusterRouter 分层架构。

**架构：** Entity 从 data class 改为 Jimmer `@Entity interface`；Repository 从 CrudRepoOps/手写 DSL 改为继承 Jimmer BaseAppCrudRepository；Service 层和 DataFetcher 层结构不变，仅适配新的 entity 创建语法和 repo API。事务通过 Spring PlatformTransactionManager 统一管理（TxRunner 底层不变）。

**技术栈：** Kotlin 2.3.10, Jimmer 0.11.5+, Spring Boot 4.1.0, DGS 12.x, KSP, PostgreSQL

**参考分支：** `feature/swagger-jimmer`（Entity 定义、Repository 模式可直接复用）

---

## 文件结构概览

### 删除的文件

| 路径 | 原因 |
|------|------|
| `core-api/src/generated/jooq/` 整个目录 | jOOQ codegen 输出，不再需要 |
| `infra/jooq/CrudRepoOps.kt` | 被 Jimmer BaseCrudRepository 替代 |
| `infra/jooq/CrudRepoOpsFactory.kt` | 同上 |
| `infra/jooq/DataSourceConfig.kt` | 改用 Jimmer 的数据源配置 |
| `infra/jooq/AuditRecordListener.kt` | Jimmer 有自己的 DraftInterceptor |
| `infra/jooq/InstantConverter.kt` | Jimmer 原生支持 Instant |
| `infra/jooq/SmallintToIntConverter.kt` | Jimmer 直接映射 Int |
| `infra/jooq/ConfigContentConverter.kt` | `@Serialized` 替代 |
| `infra/jooq/ImageRefListConverter.kt` | `@Serialized` 替代 |
| `infra/jooq/JsonMapConverter.kt` | `@Serialized` 替代 |
| `infra/jooq/JsonStringConverter.kt` | `@Serialized` 替代 |

### 新建的文件

| 路径 | 职责 |
|------|------|
| `entity/AppScopedProps.kt` | 多租户标记接口 `val appId: UUID` |
| `entity/MutableProps.kt` | `createdAt + updatedAt` 基类 |
| `entity/SoftDeletableProps.kt` | `@LogicalDeleted deletedAt` |
| `entity/ai/ScanRecord.kt` | `@Entity interface`（替代 data class） |
| `entity/ai/ScanCollection.kt` | 同上 |
| `entity/ai/ScanCollectionItem.kt` | 同上 |
| `entity/ai/AgnesKey.kt` | 同上 |
| `entity/app/AppConfigRevision.kt` | 同上（含 ConfigContent 等值对象） |
| `entity/app/AppInfo.kt` | 同上 |
| `entity/auth/AppUser.kt` | 同上 |
| `entity/auth/AuthIdentity.kt` | 同上 |
| `entity/auth/AuthProviderIdentity.kt` | 同上 |
| `entity/auth/AuthDeviceSecret.kt` | 同上 |
| `entity/auth/AppRefreshToken.kt` | 同上 |
| `entity/auth/AuthTenant.kt` | 同上 |
| `entity/auth/UserInstallBinding.kt` | 同上 |
| `entity/iap/Subscription.kt` | 同上 |
| `entity/iap/StoreNotification.kt` | 同上 |
| `entity/feedback/Feedback.kt` | 同上 |
| `entity/storage/UploadRecord.kt` | 同上 |
| `entity/shared/Enums.kt` | 枚举常量（Tiers、Platforms 等） |
| `infra/jimmer/JimmerConfig.kt` | KSqlClient bean 配置 + 多数据源 |
| `infra/jimmer/BaseAppCrudRepository.kt` | 通用 CRUD（从 swagger-jimmer 分支搬运） |
| `infra/jimmer/BaseCrudRepository.kt` | 无 appId 的通用 CRUD |
| `infra/jimmer/AppScopedFilter.kt` | 全局过滤器（非必须，可选） |
| `infra/jimmer/DraftInterceptor.kt` | 自动填充 createdAt/updatedAt |

### 修改的文件

| 路径 | 改动 |
|------|------|
| `build.gradle.kts` (root) | 添加 KSP 插件、Jimmer 版本管理 |
| `core-api/build.gradle.kts` | 移除 jOOQ 插件/依赖，添加 Jimmer 依赖 + KSP |
| `infra/db/SvcCtx.kt` | `dsl: DSLContext` → `sql: KSqlClient` |
| `infra/db/SvcCtxFactory.kt` | 使用 KSqlClient 替代 DSLContext |
| `infra/db/ClusterRouter.kt` | 返回 KSqlClient 而非 DSLContext |
| `infra/jooq/TxRunner.kt` | 路径不变（移到 `infra/tx/`），底层改用 Spring TX |
| `infra/jooq/FilterConditionParser.kt` | 改用 Jimmer DSL 动态 where |
| 所有 `modules/*/repo/*.kt` | 改为继承 BaseCrudRepository/BaseAppCrudRepository |
| 所有 `modules/*/service/internal/*.kt` | 适配 Jimmer entity 创建语法 |
| `bff/webhooks/WebhookController.kt` | 适配新 repo API |

---

## 任务分解

### 任务 1：Gradle 依赖替换

**文件：**
- 修改：`build.gradle.kts`（root）
- 修改：`core-api/build.gradle.kts`

- [ ] **步骤 1：root build.gradle.kts 添加 KSP 插件和 Jimmer 版本**

```kotlin
plugins {
    // ... 已有
    id("com.google.devtools.ksp") version "2.3.10" apply false
}

extra["jimmerVersion"] = "0.11.5"
```

- [ ] **步骤 2：core-api/build.gradle.kts 替换 jOOQ → Jimmer**

移除：
```kotlin
id("nu.studer.jooq")  // plugins 块
jooqGenerator("org.postgresql:postgresql")  // dependencies
implementation("org.springframework.boot:spring-boot-starter-jooq")
// 整个 jooq { ... } 配置块
// sourceSets 中的 kotlin.srcDir("src/generated/jooq")
```

添加：
```kotlin
plugins {
    id("com.google.devtools.ksp")
}

val jimmerVersion = rootProject.extra["jimmerVersion"] as String

dependencies {
    implementation("org.babyfish.jimmer:jimmer-spring-boot-starter:$jimmerVersion")
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")
}

ksp {
    arg("jimmer.language", "kotlin")
}
```

- [ ] **步骤 3：删除 jOOQ 生成目录**

```bash
rm -rf core-api/src/generated/jooq
```

- [ ] **步骤 4：验证 Gradle sync 成功**

```bash
./gradlew :core-api:dependencies | grep jimmer
```

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "build: replace jOOQ with Jimmer dependencies"
```

---

### 任务 2：Entity 层改造 — 公共接口

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/entity/AppScopedProps.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/entity/MutableProps.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/entity/SoftDeletableProps.kt`

- [ ] **步骤 1：创建公共接口**

```kotlin
// AppScopedProps.kt
package com.ifmix.api.core.entity

import java.util.UUID

interface AppScopedProps {
    val appId: UUID
}

// MutableProps.kt
package com.ifmix.api.core.entity

import org.babyfish.jimmer.sql.MappedSuperclass
import java.time.Instant

@MappedSuperclass
interface CreatedAtProps {
    val createdAt: Instant
}

@MappedSuperclass
interface MutableProps : CreatedAtProps {
    val updatedAt: Instant
}

// SoftDeletableProps.kt
package com.ifmix.api.core.entity

import org.babyfish.jimmer.sql.LogicalDeleted
import org.babyfish.jimmer.sql.MappedSuperclass
import java.time.Instant

@MappedSuperclass
interface SoftDeletableProps : MutableProps {
    @LogicalDeleted("now")
    val deletedAt: Instant?
}
```

- [ ] **步骤 2：验证编译**

```bash
./gradlew :core-api:compileKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "feat(entity): add Jimmer shared MappedSuperclass interfaces"
```

---

### 任务 3：Entity 层改造 — 核心实体（AI 模块）

**文件：**
- 创建/覆盖：`entity/ai/ScanRecord.kt`、`entity/ai/ScanCollection.kt`、`entity/ai/ScanCollectionItem.kt`、`entity/ai/AgnesKey.kt`、`entity/ImageRef.kt`
- 删除旧 data class 版本

- [ ] **步骤 1：创建 ScanRecord entity**

从 `feature/swagger-jimmer` 分支复制并适配：
```kotlin
package com.ifmix.api.core.entity.ai

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import com.ifmix.api.core.entity.ImageRef
import org.babyfish.jimmer.sql.*
import java.util.UUID

@Entity
@Table(name = "core_scan_record")
interface ScanRecord : AppScopedProps, SoftDeletableProps {
    @Id val id: UUID
    override val appId: UUID

    @Serialized
    @Column(name = "image_keys")
    val imageKeys: List<ImageRef>

    @Serialized
    @Column(name = "basic_result")
    val basicResult: Map<String, Any?>?

    @Serialized
    @Column(name = "premium_result")
    val premiumResult: Map<String, Any?>?

    val status: Int
    val clientIp: String?
    val lang: String?
    val country: String?
    val currency: String?
    val userDisplayName: String?
    val userNotes: String?
    val collected: Boolean
}
```

- [ ] **步骤 2：创建其他 AI entity（ScanCollection、ScanCollectionItem、AgnesKey、ImageRef）**

ImageRef 是值对象（`@Embeddable` 或纯 data class）：
```kotlin
package com.ifmix.api.core.entity

import org.babyfish.jimmer.sql.Embeddable

data class ImageRef(val key: String)
```

其他同理，按 `feature/swagger-jimmer` 分支模式创建。

- [ ] **步骤 3：验证 KSP 生成**

```bash
./gradlew :core-api:kspKotlin
```

- [ ] **步骤 4：Commit**

```bash
git add -A && git commit -m "feat(entity): add Jimmer AI module entities"
```

---

### 任务 4：Entity 层改造 — Auth 模块

**文件：**
- 创建/覆盖：`entity/auth/AppUser.kt`、`entity/auth/AuthIdentity.kt`、`entity/auth/AuthProviderIdentity.kt`、`entity/auth/AuthDeviceSecret.kt`、`entity/auth/AppRefreshToken.kt`、`entity/auth/AuthTenant.kt`、`entity/auth/UserInstallBinding.kt`

- [ ] **步骤 1：创建全部 Auth entity**

按 `feature/swagger-jimmer` 分支逐个创建。注意：
- `AuthIdentity` 有 `@ManyToOne` 关联到 `AuthTenant`
- `AuthProviderIdentity` 有 `@ManyToOne` 关联到 `AuthIdentity`
- JSONB 字段用 `@Serialized`
- 枚举字段保持 Int（不用 Jimmer 的 EnumType）

- [ ] **步骤 2：验证 KSP 生成**

```bash
./gradlew :core-api:kspKotlin
```

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "feat(entity): add Jimmer Auth module entities"
```

---

### 任务 5：Entity 层改造 — App/IAP/Storage/Feedback

**文件：**
- 创建/覆盖：`entity/app/AppConfigRevision.kt`（含 ConfigContent 值对象）、`entity/app/AppInfo.kt`、`entity/iap/Subscription.kt`、`entity/iap/StoreNotification.kt`、`entity/storage/UploadRecord.kt`、`entity/feedback/Feedback.kt`

- [ ] **步骤 1：创建全部剩余 entity**

AppConfigRevision 的 content 字段：
```kotlin
@Serialized
val content: ConfigContent
```

ConfigContent 和子值对象保持为 `data class`（Jimmer 的 `@Serialized` 字段值类型不需要是 entity）。

- [ ] **步骤 2：验证 KSP 全量编译通过**

```bash
./gradlew :core-api:compileKotlin
```

此时由于 Repository/Service 还引用旧 entity，大量编译错误是预期行为。只需 KSP 生成成功即可。

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "feat(entity): add Jimmer App/IAP/Storage/Feedback entities"
```

---

### 任务 6：基础设施层 — JimmerConfig + SvcCtx 适配

**文件：**
- 创建：`infra/jimmer/JimmerConfig.kt`
- 创建：`infra/jimmer/DraftInterceptor.kt`
- 修改：`infra/db/SvcCtx.kt`
- 修改：`infra/db/SvcCtxFactory.kt`
- 修改：`infra/db/ClusterRouter.kt`

- [ ] **步骤 1：创建 JimmerConfig**

```kotlin
package com.ifmix.api.core.infra.jimmer

import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.ConnectionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class JimmerConfig {
    @Bean
    fun sqlClient(dataSource: DataSource): KSqlClient = newKSqlClient {
        setConnectionManager(ConnectionManager.simpleConnectionManager(dataSource))
        // dialect、interceptor 等后续配置
    }
}
```

注：Spring Boot starter 自动配置 KSqlClient。如果用 `jimmer-spring-boot-starter`，可能不需要手动 bean。验证后决定。

- [ ] **步骤 2：修改 SvcCtx**

```kotlin
data class SvcCtx(
    val op: OperationContext,
    val sql: KSqlClient,         // 之前是 dsl: DSLContext
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
)
```

- [ ] **步骤 3：修改 ClusterRouter 和 SvcCtxFactory**

ClusterRouter 返回 `KSqlClient`；SvcCtxFactory 构建 `SvcCtx(sql = router.forApp(appId))`。

- [ ] **步骤 4：适配 TxRunner**

TxRunner 改用 Spring 的 `PlatformTransactionManager`：
```kotlin
@Component
class TxRunner(private val txManager: PlatformTransactionManager) {
    fun <R> withTx(svcCtx: SvcCtx, propagation: TxPropagation = TxPropagation.REQUIRED, body: (SvcCtx) -> R): R {
        // 使用 TransactionTemplate 执行
        val template = TransactionTemplate(txManager).apply {
            this.propagationBehavior = propagation.toSpring()
        }
        return template.execute { body(svcCtx.copy(inTransaction = true)) }!!
    }
}
```

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "infra: adapt SvcCtx/ClusterRouter/TxRunner for Jimmer KSqlClient"
```

---

### 任务 7：Repository 层改造 — 基类

**文件：**
- 创建：`infra/jimmer/BaseAppCrudRepository.kt`
- 创建：`infra/jimmer/BaseCrudRepository.kt`
- 删除：`infra/jooq/CrudRepoOps.kt`、`infra/jooq/CrudRepoOpsFactory.kt`

- [ ] **步骤 1：创建 BaseAppCrudRepository**

从 `feature/swagger-jimmer` 分支复制，适配 SvcCtx（将 RepoContext 替换为 SvcCtx）：
```kotlin
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    protected val entityType: KClass<E>,
) {
    open fun findById(ctx: SvcCtx, appId: UUID, id: UUID): E? =
        ctx.sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table)
        }.limit(1).execute().firstOrNull()

    open fun save(ctx: SvcCtx, entity: E): E =
        ctx.sql.entities.save(entity).modifiedEntity

    open fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean { ... }
    // ... 其他通用方法
}
```

关键改动：所有方法从 `(RepoContext, ...)` 改为 `(SvcCtx, ...)`，内部用 `ctx.sql` 获取 KSqlClient。

- [ ] **步骤 2：创建 BaseCrudRepository（无 appId 场景）**

同理，用于 AuthTenant、AuthIdentity 等无 appId 的表。

- [ ] **步骤 3：删除旧 jOOQ 基础类**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/infra/jooq/CrudRepoOps.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/infra/jooq/CrudRepoOpsFactory.kt
```

- [ ] **步骤 4：Commit**

```bash
git add -A && git commit -m "infra: add Jimmer BaseAppCrudRepository, remove jOOQ CrudRepoOps"
```

---

### 任务 8：Repository 层改造 — 逐模块迁移

**文件：** 所有 `modules/*/repo/*.kt`（16 个文件）

此任务按模块分批进行。每个 repo 的模式相同：

1. 继承 `BaseAppCrudRepository<E>` 或 `BaseCrudRepository<E>`
2. 构造函数不再注入 `CrudRepoOpsFactory`（不需要了，Jimmer 通过 Spring 注入 KSqlClient，基类持有）
3. 自定义查询改用 Jimmer DSL
4. 删除 FIELD_MAP companion（不再需要）

- [ ] **步骤 1：迁移 AI 模块 repo（ScanRecordRepository、ScanCollectionRepository、ScanCollectionItemRepository、AgnesKeyRepository）**

示例 ScanRecordRepository：
```kotlin
@Repository
class ScanRecordRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanRecord>(ScanRecord::class) {

    init { this.sql = sql } // 或通过构造函数传递

    fun findByCursor(ctx: SvcCtx, appId: UUID, collected: Boolean?, cursor: UUID?, limit: Int): List<ScanRecord> =
        ctx.sql.createQuery(ScanRecord::class) {
            where(table.appId eq appId)
            collected?.let { where(table.collected eq it) }
            cursor?.let { where(table.id lt it) }
            orderBy(table.id.desc())
            select(table)
        }.limit(limit).execute()
}
```

- [ ] **步骤 2：迁移 Auth 模块 repo（7 个文件）**

- [ ] **步骤 3：迁移 App/IAP/Storage/Feedback repo（6 个文件）**

- [ ] **步骤 4：验证编译**

```bash
./gradlew :core-api:compileKotlin
```

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat(repo): migrate all repositories to Jimmer DSL"
```

---

### 任务 9：Service 层适配

**文件：** 所有 `modules/*/service/**/*.kt`

主要改动：
1. Entity 创建语法从 `ScanRecord(id = ..., appId = ...)` 改为 Jimmer 的 `ScanRecord { id = ...; appId = ... }`
2. Entity 是不可变接口，更新用 `sql.createUpdate(ScanRecord::class) { ... }`
3. `fetchOneInto(Entity::class.java)` 不再存在——Jimmer 查询直接返回 entity

- [ ] **步骤 1：适配 ScanInternalService（AI 模块 - 最复杂）**

```kotlin
// 之前
val record = ScanRecord(id = scanId, appId = appId, ...)
scanRepo.insert(sc, record)

// 之后
val entity = ScanRecord {
    id = scanId
    this.appId = appId
    this.imageKeys = input.images.map { ImageRef(key = it.imageKey) }
    this.basicResult = result
    // ...
}
scanRepo.save(sc, entity)
```

- [ ] **步骤 2：适配 AuthInternalService**

- [ ] **步骤 3：适配 PaymentInternalService、StorageInternalService、其他**

- [ ] **步骤 4：适配 WebhookController（直接使用 repo）**

- [ ] **步骤 5：验证编译**

```bash
./gradlew :core-api:compileKotlin
```

- [ ] **步骤 6：Commit**

```bash
git add -A && git commit -m "feat(service): adapt all services to Jimmer entity creation"
```

---

### 任务 10：GraphQL DataFetcher 适配

**文件：** `bff/graphql/customer/**/*Fetcher.kt`

DataFetcher 层改动最小——它只调 Service，不直接接触 ORM。主要变化：
1. Jimmer entity 是接口，Jackson 序列化需确认 DGS 能正确输出
2. `FilterConditionParser` 改用 Jimmer 动态 where

- [ ] **步骤 1：验证 DGS 能正确序列化 Jimmer entity**

Jimmer entity 默认支持 Jackson 序列化（有 `@JimmerModule`）。在 JacksonConfig 中确认注册了 Jimmer 的 Jackson module。

- [ ] **步骤 2：改造 FilterConditionParser → Jimmer 版**

```kotlin
// 之前（jOOQ）
val cond = filterParser.parse(filterMap)
ctx.dsl.selectFrom(TABLE).where(cond)

// 之后（Jimmer）
ctx.sql.createQuery(ScanRecord::class) {
    applyFilter(filter)  // 新的 helper
    select(table)
}
```

`applyFilter` 递归遍历 FilterGroup，调用 Jimmer 的 `where(table.get<T>(fieldName) eq/gt/lt... value)`。

- [ ] **步骤 3：验证全部 DataFetcher 编译通过**

```bash
./gradlew :core-api:compileKotlin
```

- [ ] **步骤 4：Commit**

```bash
git add -A && git commit -m "feat(bff): adapt DataFetchers and FilterParser for Jimmer"
```

---

### 任务 11：清理 jOOQ 残留

**文件：**
- 删除：`infra/jooq/` 整个目录（剩余文件）
- 删除：`core-api/src/generated/jooq/`（如果还在）
- 修改：`build.gradle.kts` 移除 jOOQ 相关 sourceSets

- [ ] **步骤 1：删除所有 jOOQ 相关文件**

```bash
rm -rf core-api/src/main/kotlin/com/ifmix/api/core/infra/jooq
rm -rf core-api/src/generated/jooq
```

- [ ] **步骤 2：清理 import 和编译错误**

```bash
./gradlew :core-api:compileKotlin
```

修复所有残留的 jOOQ import。

- [ ] **步骤 3：验证 full build**

```bash
./gradlew :core-api:build
```

- [ ] **步骤 4：Commit**

```bash
git add -A && git commit -m "chore: remove all jOOQ artifacts and codegen"
```

---

### 任务 12：测试验证

**文件：** `core-api/src/test/kotlin/`

- [ ] **步骤 1：修复现有测试编译**

更新测试中引用旧 entity/repo 的 import。

- [ ] **步骤 2：运行测试**

```bash
./gradlew :core-api:test
```

- [ ] **步骤 3：修复失败的测试**

主要是 entity 创建方式和 mock 方式的变化。

- [ ] **步骤 4：Commit**

```bash
git add -A && git commit -m "test: fix all tests for Jimmer migration"
```

---

## 风险和注意事项

1. **Jimmer 0.11.5 + Spring Boot 4.1.0 兼容性**：需要验证。如果有问题，升级 Jimmer 到最新版。
2. **KSqlClient 多实例**：ClusterRouter 需要为每个集群创建独立的 KSqlClient。Jimmer 支持多数据源，参考官方文档。
3. **GraphQL JSON scalar**：Jimmer 的 `@Serialized` 字段序列化为 JSON 时，DGS 的 `JSON` scalar 应能透传。验证 Map/List 字段输出。
4. **软删除**：Jimmer 的 `@LogicalDeleted` 会自动在所有查询中追加 `WHERE deleted_at IS NULL`。之前手动加的 `.and(DELETED_AT.isNull)` 要删除，否则重复。
5. **枚举字段**：保持 Int 类型，不用 Jimmer 的 `@EnumType`——和 AGENTS.md 设计决策一致。
