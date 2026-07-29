# Jimmer + PostgreSQL 迁移 — Phase 2.5 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。

**目标：**
1. 将项目目录结构从 module-first 重构为 layer-first（entity / repository / service 分层，每层内按模块分目录）
2. 实现 Phase 2 遗留的 Service 层 stub（auth、collection、iap 业务逻辑）
3. 将 Repository 中的全量加载 + 内存过滤替换为 Jimmer 查询 DSL

**前置条件：** Phase 2 已完成 — Entity/Repository/Migration 框架到位，编译通过，Service 层为 NotImplementedError stub。

**技术栈：** Kotlin 2.3.10, Spring Boot 4.1, Jimmer 0.11.5 (KSP), PostgreSQL, Flyway, Gradle 9.6.1

---

## 目录结构变更总览

### 当前结构（module-first + jimmer 嵌套）

```
com.ifmix.api.core/
├── common/
│   ├── jimmer/
│   │   ├── entity/{appconfig,auth,antique,collection,iap,ai,todo,feedback}/
│   │   ├── repository/{appconfig,auth,antique,collection,iap,ai,todo,feedback}/
│   │   ├── base/        ← BaseCrudRepository, BaseCrudService
│   │   ├── filter/      ← AppScopedFilter
│   │   └── cluster/     ← ReadWriteRouting, ClusterRegistry, JimmerConfig
│   ├── http/
│   ├── auth/
│   ├── ai/
│   ├── config/
│   ├── db/
│   ├── ratelimit/
│   ├── redis/
│   └── storage/
├── modules/
│   ├── appconfig/   ← AppConfigRepo (service), AppConfig (BO)
│   ├── auth/        ← AuthService, AuthConfig, DTOs, ProviderVerifier
│   ├── antique/     ← AntiqueService, AntiqueConfig, ScanRunner, etc.
│   ├── collection/  ← CollectionService, CollectionConfig
│   ├── iap/         ← IapService, IapConfig, types, verifiers
│   ├── feedback/    ← FeedbackService, FeedbackConfig
│   └── todo/        ← TodoService, TodoConfig
└── bff/             ← Controllers（不动）
```

### 目标结构（layer-first）

```
com.ifmix.api.core/
├── entity/                    ← 所有 Jimmer Entity（从 common/jimmer/entity 移出）
│   ├── AppScopedProps.kt
│   ├── appconfig/AppInfo.kt, AppConfig.kt
│   ├── auth/AuthTenant.kt, AuthIdentity.kt, ...
│   ├── antique/ScanRecord.kt
│   ├── collection/Collection.kt, CollectionItem.kt
│   ├── iap/Subscription.kt, StoreNotification.kt
│   ├── ai/AgnesKey.kt
│   ├── todo/Todo.kt, TodoItem.kt
│   └── feedback/Feedback.kt
├── repository/                ← 所有 Repository（从 common/jimmer/repository 移出）
│   ├── base/BaseCrudRepository.kt
│   ├── appconfig/
│   ├── auth/
│   ├── antique/
│   ├── collection/
│   ├── iap/
│   ├── ai/
│   ├── todo/
│   └── feedback/
├── service/                   ← 所有 Service（从 modules/ 重命名移入）
│   ├── base/BaseCrudService.kt
│   ├── appconfig/AppConfigRepo.kt, AppConfig.kt (BO)
│   ├── auth/AuthService.kt, AuthConfig.kt, AuthDtos.kt, ProviderVerifier.kt, ...
│   ├── antique/AntiqueService.kt, AntiqueConfig.kt, ScanRunner.kt, ...
│   ├── collection/CollectionService.kt, CollectionConfig.kt
│   ├── iap/IapService.kt, IapConfig.kt, IapTypes.kt, ...
│   ├── feedback/FeedbackService.kt, FeedbackConfig.kt
│   └── todo/TodoService.kt, TodoConfig.kt
├── infra/                     ← 基础设施（从 common/ 精简重组）
│   ├── jimmer/                ← Jimmer 特有配置（cluster, filter）
│   │   ├── ClusterRegistry.kt, ClusterProperties.kt, ClusterInitializer.kt
│   │   ├── JimmerConfig.kt, ReadWriteRoutingDataSource.kt
│   │   └── AppScopedFilter.kt
│   ├── http/                  ← (原 common/http)
│   ├── auth/                  ← (原 common/auth) JWT, Hashing, Interceptor
│   ├── ai/                    ← (原 common/ai) AgnesKeyStore, ChatClientFactory, ScanRunner
│   ├── config/                ← (原 common/config) Jackson, Web, OpenApi
│   ├── db/                    ← (原 common/db) Page, CursorQueryInput, Ownership
│   ├── ratelimit/             ← (原 common/ratelimit)
│   ├── redis/                 ← (原 common/redis)
│   └── storage/               ← (原 common/storage)
└── bff/                       ← Controllers（不动，仅更新 import）
```

### DTO 文件路径同步变更

```
src/main/dto/com/ifmix/api/core/entity/{module}/XxxEntity.dto
```
（从 `common/jimmer/entity/` 改为 `entity/`）

---

## 任务 1：目录结构重构（Layer-First）

**优先级：** 最高（后续所有任务基于新结构）

**策略：** 一次性批量 move + 全局 package rename。因为 Kotlin package 声明必须匹配目录，每个文件的 `package` 行和所有 `import` 引用都需要更新。

### 步骤

- [ ] **1.1：创建新目录结构**

```bash
# Entity 层
mkdir -p core-api/src/main/kotlin/com/ifmix/api/core/entity/{appconfig,auth,antique,collection,iap,ai,todo,feedback}
# Repository 层
mkdir -p core-api/src/main/kotlin/com/ifmix/api/core/repository/{base,appconfig,auth,antique,collection,iap,ai,todo,feedback}
# Service 层
mkdir -p core-api/src/main/kotlin/com/ifmix/api/core/service/{base,appconfig,auth,antique,collection,iap,ai,todo,feedback}
# Infra 层
mkdir -p core-api/src/main/kotlin/com/ifmix/api/core/infra/{jimmer,http,auth,ai,config,db,ratelimit,redis,storage}
```

- [ ] **1.2：移动 Entity 文件 + 更新 package 声明**

| 源路径 | 目标路径 | 新 package |
|--------|----------|-----------|
| `common/jimmer/entity/AppScopedProps.kt` | `entity/AppScopedProps.kt` | `com.ifmix.api.core.entity` |
| `common/jimmer/entity/appconfig/*.kt` | `entity/appconfig/*.kt` | `com.ifmix.api.core.entity.appconfig` |
| `common/jimmer/entity/auth/*.kt` | `entity/auth/*.kt` | `com.ifmix.api.core.entity.auth` |
| `common/jimmer/entity/antique/*.kt` | `entity/antique/*.kt` | `com.ifmix.api.core.entity.antique` |
| `common/jimmer/entity/collection/*.kt` | `entity/collection/*.kt` | `com.ifmix.api.core.entity.collection` |
| `common/jimmer/entity/iap/*.kt` | `entity/iap/*.kt` | `com.ifmix.api.core.entity.iap` |
| `common/jimmer/entity/ai/*.kt` | `entity/ai/*.kt` | `com.ifmix.api.core.entity.ai` |
| `common/jimmer/entity/todo/*.kt` | `entity/todo/*.kt` | `com.ifmix.api.core.entity.todo` |
| `common/jimmer/entity/feedback/*.kt` | `entity/feedback/*.kt` | `com.ifmix.api.core.entity.feedback` |

- [ ] **1.3：移动 Repository 文件 + 更新 package 声明**

| 源路径 | 目标路径 | 新 package |
|--------|----------|-----------|
| `common/jimmer/base/BaseCrudRepository.kt` | `repository/base/BaseCrudRepository.kt` | `com.ifmix.api.core.repository.base` |
| `common/jimmer/repository/appconfig/*.kt` | `repository/appconfig/*.kt` | `com.ifmix.api.core.repository.appconfig` |
| `common/jimmer/repository/auth/*.kt` | `repository/auth/*.kt` | `com.ifmix.api.core.repository.auth` |
| `common/jimmer/repository/antique/*.kt` | `repository/antique/*.kt` | `com.ifmix.api.core.repository.antique` |
| `common/jimmer/repository/collection/*.kt` | `repository/collection/*.kt` | `com.ifmix.api.core.repository.collection` |
| `common/jimmer/repository/iap/*.kt` | `repository/iap/*.kt` | `com.ifmix.api.core.repository.iap` |
| `common/jimmer/repository/ai/*.kt` | `repository/ai/*.kt` | `com.ifmix.api.core.repository.ai` |
| `common/jimmer/repository/todo/*.kt` | `repository/todo/*.kt` | `com.ifmix.api.core.repository.todo` |
| `common/jimmer/repository/feedback/*.kt` | `repository/feedback/*.kt` | `com.ifmix.api.core.repository.feedback` |

- [ ] **1.4：移动 Service 文件（modules/ → service/）+ 更新 package 声明**

| 源路径 | 目标路径 | 新 package |
|--------|----------|-----------|
| `common/jimmer/base/BaseCrudService.kt` | `service/base/BaseCrudService.kt` | `com.ifmix.api.core.service.base` |
| `modules/appconfig/*.kt` | `service/appconfig/*.kt` | `com.ifmix.api.core.service.appconfig` |
| `modules/auth/*.kt` | `service/auth/*.kt` | `com.ifmix.api.core.service.auth` |
| `modules/antique/*.kt` | `service/antique/*.kt` | `com.ifmix.api.core.service.antique` |
| `modules/collection/*.kt` | `service/collection/*.kt` | `com.ifmix.api.core.service.collection` |
| `modules/iap/*.kt` | `service/iap/*.kt` | `com.ifmix.api.core.service.iap` |
| `modules/feedback/*.kt` | `service/feedback/*.kt` | `com.ifmix.api.core.service.feedback` |
| `modules/todo/*.kt` | `service/todo/*.kt` | `com.ifmix.api.core.service.todo` |

- [ ] **1.5：移动 Infra 文件（common/ → infra/）+ 更新 package 声明**

| 源路径 | 目标路径 | 新 package |
|--------|----------|-----------|
| `common/jimmer/cluster/*.kt` | `infra/jimmer/*.kt` | `com.ifmix.api.core.infra.jimmer` |
| `common/jimmer/filter/*.kt` | `infra/jimmer/*.kt` | `com.ifmix.api.core.infra.jimmer` |
| `common/http/*.kt` | `infra/http/*.kt` | `com.ifmix.api.core.infra.http` |
| `common/auth/*.kt` | `infra/auth/*.kt` | `com.ifmix.api.core.infra.auth` |
| `common/ai/*.kt` | `infra/ai/*.kt` | `com.ifmix.api.core.infra.ai` |
| `common/config/*.kt` | `infra/config/*.kt` | `com.ifmix.api.core.infra.config` |
| `common/db/*.kt` | `infra/db/*.kt` | `com.ifmix.api.core.infra.db` |
| `common/ratelimit/*.kt` | `infra/ratelimit/*.kt` | `com.ifmix.api.core.infra.ratelimit` |
| `common/redis/*.kt` | `infra/redis/*.kt` | `com.ifmix.api.core.infra.redis` |
| `common/storage/*.kt` | `infra/storage/*.kt` | `com.ifmix.api.core.infra.storage` |

- [ ] **1.6：全局 import 替换**

使用批量 sed/find-replace 更新所有 `.kt` 文件中的 import：

```
com.ifmix.api.core.common.jimmer.entity  →  com.ifmix.api.core.entity
com.ifmix.api.core.common.jimmer.repository  →  com.ifmix.api.core.repository
com.ifmix.api.core.common.jimmer.base  →  com.ifmix.api.core.repository.base (for Repo) / com.ifmix.api.core.service.base (for Service)
com.ifmix.api.core.common.jimmer.filter  →  com.ifmix.api.core.infra.jimmer
com.ifmix.api.core.common.jimmer.cluster  →  com.ifmix.api.core.infra.jimmer
com.ifmix.api.core.common.http  →  com.ifmix.api.core.infra.http
com.ifmix.api.core.common.auth  →  com.ifmix.api.core.infra.auth
com.ifmix.api.core.common.ai  →  com.ifmix.api.core.infra.ai
com.ifmix.api.core.common.config  →  com.ifmix.api.core.infra.config
com.ifmix.api.core.common.db  →  com.ifmix.api.core.infra.db
com.ifmix.api.core.common.ratelimit  →  com.ifmix.api.core.infra.ratelimit
com.ifmix.api.core.common.redis  →  com.ifmix.api.core.infra.redis
com.ifmix.api.core.common.storage  →  com.ifmix.api.core.infra.storage
com.ifmix.api.core.modules.  →  com.ifmix.api.core.service.
```

也需要同步更新 `bff/` 下所有 Controller 的 import。

- [ ] **1.7：更新 Jimmer DTO 文件路径**

移动 `src/main/dto/com/ifmix/api/core/common/jimmer/entity/` → `src/main/dto/com/ifmix/api/core/entity/`

DTO 文件中的 `export` 声明路径也需要更新：
```
export com.ifmix.api.core.common.jimmer.entity.xxx → export com.ifmix.api.core.entity.xxx
```

- [ ] **1.8：更新 KSP 配置（如需要）**

检查 `build.gradle.kts` 中 `ksp { arg("jimmer.dto.dirs", ...) }` 是否需要调整。

- [ ] **1.9：删除旧 common/jimmer/ 和 modules/ 目录**

```bash
rm -rf core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer
rm -rf core-api/src/main/kotlin/com/ifmix/api/core/common/
rm -rf core-api/src/main/kotlin/com/ifmix/api/core/modules/
```

- [ ] **1.10：编译验证**

```bash
./gradlew :core-api:clean :core-api:compileKotlin
```

- [ ] **1.11：更新测试文件 import**

同步更新 `src/test/kotlin/` 下所有测试文件的 package 和 import。

- [ ] **1.12：Commit**

```bash
git add -A
git commit -m "refactor: restructure to layer-first (entity/repository/service/infra)"
```

---

## 任务 2：实现 AuthService 完整业务逻辑

**优先级：** 高（核心认证流程，用户无法登录）

**前置：** 任务 1 完成

**文件：** `service/auth/AuthService.kt`

### 需要实现的方法

#### 2.1 `loginWithProvider(ctx, provider, req) → LoginRes`

流程：
1. `tenantId(ctx)` — 从 AppConfig 获取 authTenantId
2. `verifiers[provider].verify(req.idToken)` — 验证 idToken，得到 `ProviderProfile`（email, accountId, metadata）
3. Upsert `AuthIdentity`：按 (tenantId, email) 查找或创建
4. Upsert `AuthProviderIdentity`：按 (tenantId, provider, providerAccountId) 查找或创建，关联到 AuthIdentity
5. `appUserRepo.ensure(appId, identityId)` — 确保 AppUser 存在
6. Issue `AuthDeviceSecret`：生成 random secret → hash → 存入 DB
7. Issue `AppRefreshToken`：生成 random token → hash → 存入 DB（关联 appUser + deviceSecret）
8. Sign access JWT：`jwt.sign(userId, tenantId, accessTtlSec)`
9. Publish `AuthLoggedInEvent`
10. 返回 `LoginRes(accessToken, refreshToken, expiresAt, deviceSecret, expiresIn, user)`

### 步骤

- [ ] **2.1：在 AuthIdentityRepository 添加 `findOrCreate(tenantId, email, displayName)` 方法**

使用 Jimmer 查询 DSL：
```kotlin
fun findByTenantAndEmail(tenantId: UUID, email: String): AuthIdentity? {
    return sql.createQuery(AuthIdentity::class) {
        where(table.authTenant.id eq tenantId)
        where(table.email eq email)
        select(table)
    }.fetchOneOrNull()
}
```

- [ ] **2.2：在 AuthProviderIdentityRepository 添加 `upsertByProvider(tenantId, provider, accountId, ...)`**

使用 Jimmer `save` with `@Key` 或者手动 find-then-insert/update。

- [ ] **2.3：在 AppRefreshTokenRepository 添加业务方法**

```kotlin
fun findByHash(appId: UUID, tokenHash: String): AppRefreshToken?
fun revokeByAppUser(appId: UUID, appUserId: UUID)
fun revokeByDeviceSecret(deviceSecretId: UUID)
```

- [ ] **2.4：在 AuthDeviceSecretRepository 添加业务方法**

```kotlin
fun findValid(tenantId: UUID, secretHash: String): AuthDeviceSecret?
fun touch(id: UUID)  // 更新 lastUsedAt
fun revoke(id: UUID) // 设置 revokedAt
```

- [ ] **2.5：实现 `loginWithProvider` 完整流程**

- [ ] **2.6：实现 `exchange(ctx, req) → ExchangeRes`**

流程：
1. Hash device secret
2. `deviceSecretRepo.findValid(tenantId, hash)` — 验证有效性
3. `touch(deviceSecret)` — 更新 lastUsedAt
4. 通过 deviceSecret → authIdentity → appUser 找到用户
5. Issue new refresh token（关联 deviceSecret）
6. Sign access JWT
7. 返回 ExchangeRes

- [ ] **2.7：实现 `refresh(ctx, req) → RefreshRes`**

流程（原子轮换）：
1. Hash refresh token
2. `refreshRepo.findByHash(appId, hash)` — 找到 token 记录
3. 校验未过期、未撤销
4. 生成新 refresh token → 存入 DB
5. 标记旧 token `replacedBy = newTokenId`、`revokedAt = now`
6. Sign new access JWT
7. 返回 RefreshRes

- [ ] **2.8：实现 `logout(ctx, req) → LogoutRes`**

流程：
1. Hash refresh token
2. 找到 token 记录 → 设置 revokedAt
3. 如果关联了 deviceSecret → revoke device secret
4. 返回 LogoutRes(ok=true)

- [ ] **2.9：编写 AuthServiceIntegrationTest（Testcontainers）**

测试覆盖：
- 完整 login → refresh → logout 流程
- exchange 流程
- 重复登录幂等（相同 provider+accountId 不创建重复 identity）
- 过期 token 拒绝
- 已撤销 token 拒绝

- [ ] **2.10：Commit**

```bash
git add -A
git commit -m "feat: implement AuthService full business logic (login/exchange/refresh/logout)"
```

---

## 任务 3：实现 CollectionService 完整业务逻辑

**优先级：** 中高

**前置：** 任务 1 完成

**文件：** `service/collection/CollectionService.kt`, `repository/collection/CollectionRepository.kt`, `repository/collection/CollectionItemRepository.kt`

### 步骤

- [ ] **3.1：在 CollectionRepository 添加 `findDefault(appId, installId, userId)`**

```kotlin
fun findDefault(appId: UUID, installId: String?, userId: String?): Collection? {
    return sql.createQuery(Collection::class) {
        where(table.appId eq appId)
        where(table.isDefault eq true)
        // ownership: userId OR installId
        where(
            or(
                userId?.let { table.userId eq it },
                installId?.let { table.installId eq it },
            )
        )
        select(table)
    }.fetchOneOrNull()
}
```

- [ ] **3.2：在 CollectionItemRepository 添加业务方法**

```kotlin
/** 幂等插入：利用 partial unique index，捕获约束违反返回已存在的 ID */
fun insertIfAbsent(appId: UUID, collectionId: UUID, scanRecordId: UUID): UUID

/** 批量软删 */
fun softDeleteByScanIds(appId: UUID, collectionId: UUID, scanRecordIds: List<UUID>): Long

/** Join 查询 scan_record（利用 Jimmer Fetcher） */
fun listWithScanRecords(appId: UUID, collectionId: UUID, limit: Int, cursor: UUID?): Page<CollectionItem>
```

- [ ] **3.3：实现 `getDefault(ctx) → Collection`**

流程：
1. `collectionRepo.findDefault(appId, installId, userId)`
2. 如果 null → 创建默认夹（isDefault=true, appId, installId, userId）
3. 返回 Collection

- [ ] **3.4：实现 `addItem(ctx, req) → AddItemRes`**

流程：
1. 解析 collectionId（null 时取默认夹）
2. `itemRepo.insertIfAbsent(appId, collectionId, scanRecordId)`
3. 返回 AddItemRes(itemId)

- [ ] **3.5：实现 `removeItems(ctx, req) → RemoveItemsRes`**

流程：
1. 解析 collectionId
2. `itemRepo.softDeleteByScanIds(appId, collectionId, scanRecordIds)`
3. 返回 RemoveItemsRes(removed)

- [ ] **3.6：实现 `listItems(ctx, req) → Page<ScanDto>`**

流程：
1. 解析 collectionId + cursor + limit
2. `itemRepo.listWithScanRecords(...)` — Jimmer join 取回 collectionItem.scanRecord
3. 映射为 ScanDto 列表
4. 返回 Page

- [ ] **3.7：实现 CollectionMembership 真实版本**

```kotlin
class CollectionMembershipImpl(
    private val itemRepo: CollectionItemRepository,
    private val collectionRepo: CollectionRepository,
) : CollectionMembership {
    override fun isCollected(ctx: RequestContext, scanRecordId: String): Boolean {
        val default = collectionRepo.findDefault(...) ?: return false
        return itemRepo.existsByScanRecordId(default.id, UUID.fromString(scanRecordId))
    }
}
```

- [ ] **3.8：编写 CollectionServiceIntegrationTest**

- [ ] **3.9：Commit**

```bash
git add -A
git commit -m "feat: implement CollectionService full business logic"
```

---

## 任务 4：实现 IapService 完整业务逻辑

**优先级：** 中

**前置：** 任务 1 完成

**文件：** `service/iap/IapService.kt`, `repository/iap/SubscriptionRepository.kt`, `repository/iap/StoreNotificationRepository.kt`

### 步骤

- [ ] **4.1：在 SubscriptionRepository 添加业务方法**

```kotlin
/** Jimmer upsert：利用 @Key(subscriptionPxid) 自动 INSERT or UPDATE */
fun upsertSubscription(entity: Subscription): Subscription = save(entity)

/** 按 subscriptionPxid 查找活跃订阅 */
fun findActiveByPxid(appId: UUID, pxid: String): Subscription?

/** 按 originalTransactionId 更新 */
fun updateByOriginalTxn(appId: UUID, originalTxnId: String, updater: (Subscription) -> Subscription)
```

- [ ] **4.2：在 StoreNotificationRepository 添加幂等检查方法**

```kotlin
fun existsByPlatformAndToken(platform: String, purchaseToken: String): Boolean
```

- [ ] **4.3：实现 `verifyPurchase(ctx, req) → VerifyRes`**

流程：
1. 根据 `req.platform` 选择 `appleVerifier` 或 `googleVerifier`
2. 调用 `verifier.verify(receipt/token)` → 得到 VerifiedPurchase（productId, expiryDate, originalTxnId, ...）
3. 构造 Subscription entity → `subscriptionRepo.upsertSubscription(...)`
4. 用 `tierOf(productId, productTierMap)` 查 tier
5. 返回 VerifyRes(subscriptionPxid, active, expiryDate, state)

- [ ] **4.4：实现通知处理 `handleNotification`**

流程：
1. 幂等检查：`storeNotificationRepo.existsByPlatformAndToken(...)` → 如果已处理则 skip
2. Decode notification payload → 提取 (subscriptionPxid, notificationType, ...)
3. 存入 store_notification 表（processed=false）
4. 根据 notificationType 更新 subscription 状态（active, subStatus, expiryDate）
5. 标记 store_notification processed=true, processedAt=now

- [ ] **4.5：编写 IapServiceIntegrationTest**

测试覆盖：
- verifyPurchase upsert 幂等
- notification 幂等（重复通知不重复处理）
- 订阅过期状态更新

- [ ] **4.6：Commit**

```bash
git add -A
git commit -m "feat: implement IapService full business logic (verify + notifications)"
```

---

## 任务 5：Repository 查询优化 — 替换 findAll + filter

**优先级：** 中（性能问题，数据量大时致命）

**前置：** 任务 1 完成

**影响文件：** 所有 Repository 中使用 `findAll().filter { ... }` 模式的方法

### 步骤

- [ ] **5.1：AppConfigRepository — 添加 Jimmer 查询 DSL 方法**

```kotlin
fun findCurrentByAppId(appId: UUID): AppConfig? {
    return sql.createQuery(AppConfig::class) {
        where(table.appId eq appId)
        // @LogicalDeleted 自动过滤 deletedAt IS NOT NULL
        select(table)
    }.fetchOneOrNull()
}

fun findByBundleId(bundleId: String): AppConfig? {
    return sql.createQuery(AppConfig::class) {
        where(table.appleBundleId eq bundleId)
        select(table)
    }.fetchOneOrNull()
}
```

- [ ] **5.2：更新 AppConfigRepo（Service）使用新查询方法**

```kotlin
fun getByAppId(appId: String): AppConfig? {
    val uuid = UUID.fromString(appId)
    return repo.findCurrentByAppId(uuid)?.let { toFlat(it) }
}
```

- [ ] **5.3：AgnesKeyRepository — 使用查询 DSL 替换 findAll + filter**

```kotlin
fun findAvailable(appId: UUID): List<AgnesKey> {
    val now = Instant.now()
    return sql.createQuery(AgnesKey::class) {
        where(table.appId eq appId)
        where(
            or(
                table.unavailableUntil.isNull(),
                table.unavailableUntil le now,
            )
        )
        select(table)
    }.execute()
}
```

- [ ] **5.4：AntiqueService.findByCursor — 使用查询 DSL + 游标分页**

```kotlin
fun findByCursor(appId: UUID, cursor: UUID?, limit: Int): Page<ScanRecord> {
    return sql.createQuery(ScanRecord::class) {
        where(table.appId eq appId)
        cursor?.let { where(table.id lt it) }
        orderBy(table.id.desc())
        select(table)
    }.limit(limit + 1).execute().let { results ->
        val hasMore = results.size > limit
        val items = if (hasMore) results.dropLast(1) else results
        Page(items, items.lastOrNull()?.id?.toString(), hasMore)
    }
}
```

- [ ] **5.5：AppUserRepository.ensure — 使用查询 DSL 替换 findAll**

```kotlin
fun findByAppAndIdentity(appId: UUID, identityId: UUID): AppUser? {
    return sql.createQuery(AppUser::class) {
        where(table.appId eq appId)
        where(table.authIdentity.id eq identityId)
        select(table)
    }.fetchOneOrNull()
}
```

- [ ] **5.6：编译验证 + 运行全量测试**

- [ ] **5.7：Commit**

```bash
git add -A
git commit -m "perf: replace findAll+filter with Jimmer query DSL in all repositories"
```

---

## 任务 6：清理 Konvert 依赖 + 最终验证

**优先级：** 低

**前置：** 任务 1-5 全部完成

### 步骤

- [ ] **6.1：确认无 Konvert 使用残留**

```bash
grep -r "Konverter\|konvert\|@Konvert" core-api/src/ --include="*.kt"
```

- [ ] **6.2：从 build.gradle.kts 移除 Konvert 依赖**

```kotlin
// 删除：
implementation("io.mcarle:konvert-api:4.5.0")
ksp("io.mcarle:konvert:4.5.0")
```

- [ ] **6.3：运行全量编译 + 测试**

```bash
./gradlew :core-api:clean :core-api:compileKotlin
./gradlew :core-api:test
```

- [ ] **6.4：验证应用启动**

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :core-api:bootRun
```

- [ ] **6.5：Commit**

```bash
git add -A
git commit -m "chore: remove Konvert dependency, final cleanup"
```

---

## 执行顺序与依赖关系

```
任务 1 (目录重构)
  ├─→ 任务 2 (AuthService)
  ├─→ 任务 3 (CollectionService)
  ├─→ 任务 4 (IapService)
  └─→ 任务 5 (查询优化)
         └─→ 任务 6 (最终清理)
```

任务 2/3/4/5 彼此独立，可并行（4 个子 agent）。任务 6 等全部完成后执行。

---

## 注意事项

### 1. Package 重命名的全局影响

这次重构涉及约 80 个 `.kt` 文件的 package 声明 + 所有交叉引用的 import。建议用脚本批量替换而非逐文件手工改。关键替换规则见 1.6。

### 2. Jimmer DTO 文件的 export 路径

DTO 文件中 `export com.ifmix.api.core.common.jimmer.entity.xxx` 必须改为 `export com.ifmix.api.core.entity.xxx`，否则 KSP 生成的 DTO 类找不到对应 Entity。

### 3. Spring Component Scan

`@SpringBootApplication` 默认扫描其所在包及子包。只要 `CoreApplication.kt` 仍在 `com.ifmix.api.core` 根包，所有子包（entity/repository/service/infra/bff）的 `@Component`/`@Configuration` 都会被自动扫描，无需额外配置。

### 4. `bff/` 目录保持不动

Controller 层（BFF）不改名——它已经是 layer-first 的。只需更新其 import 路径。

### 5. AuthService 的 Hashing 策略

- Device secret: `SHA-256(raw)` → 存 hex string
- Refresh token: `SHA-256(raw)` → 存 hex string
- Password: `BCrypt`（如果后续支持密码登录）

使用 `infra/auth/Hashing.kt` 中已有的工具函数。
