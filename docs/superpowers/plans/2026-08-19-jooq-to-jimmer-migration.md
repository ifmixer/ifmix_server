# jOOQ → Jimmer 迁移修复计划

> 执行者：Claude Code
> 日期：2026-08-19
> 前置条件：当前编译已有 103 个错误，大部分是本文档列出的模式性问题

## 总体评价

迁移方向正确：entity 已转为 interface + 注解、infra 层 JimmerConfig/ClusterRegistry/TxRunner 架构良好。
问题集中在 **Jimmer DSL 语法细节** — 主要是把 jOOQ/data class 思维惯性带入了 Jimmer。

---

## 问题分类与修复方案

### 1. [CRITICAL] `AppScopedProps` 缺少 `@MappedSuperclass`

**文件**: `entity/AppScopedProps.kt`

**问题**: 所有继承 `AppScopedProps` 的 entity 引用 `appId` 时，KSP 不会为它生成扩展属性（导致下游 `table.appId` 编译失败）。

**修复**:
```kotlin
package com.ifmix.api.core.entity

import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

@MappedSuperclass
interface AppScopedProps {
    val appId: UUID
}
```

---

### 2. [CRITICAL] 实体构造方式错误 — 用了 data class 构造器语法

**影响文件**:
- `modules/auth/service/internal/AuthEntityService.kt` — `AuthIdentity(...)`, `AuthDeviceSecret(...)`, `AppRefreshToken(...)`
- `modules/payment/service/internal/PaymentEntityService.kt` — `Subscription(...)`
- `modules/payment/service/internal/PaymentWebhookHandler.kt` — `StoreNotification(...)`
- `modules/cms/service/internal/CmsEntityService.kt` — `Feedback(...)`

**问题**: Jimmer entity 是 `interface`，不能 `Xxx(param=...)` 构造。KSP 生成的是 `Xxx { block }` DSL。

**修复模式**: 
```kotlin
// ❌ 错误
val entity = AuthIdentity(
    id = UuidV7.generate(),
    authTenantId = tenantId,
    rawEmail = rawEmail,
    ...
)

// ✅ 正确（Jimmer DSL）
val entity = AuthIdentity {
    id = UuidV7.generate()
    authTenant { id = tenantId }  // @ManyToOne 关联用嵌套 block
    rawEmail = rawEmail
    ...
}
```

**注意 @ManyToOne 字段的赋值**:
- `authTenantId = xxx` → 改为 `authTenant { id = xxx }` （设置关联对象的仅 id 版本）
- `authIdentityId = xxx` → 改为 `authIdentity { id = xxx }`
- `appUserId = xxx` → 改为 `appUser { id = xxx }`
- `deviceSecretId = xxx` → 改为 `deviceSecret { id = xxx }` （nullable 时用 `deviceSecret = null`）

---

### 3. [CRITICAL] `sub.copy(...)` 对 Jimmer interface 无效

**文件**: `modules/payment/service/internal/PaymentWebhookHandler.kt:69`

**修复**:
```kotlin
// ❌ 错误
val updated = sub.copy(active = false, subStatus = "refunded", updatedAt = now)

// ✅ 正确 — 基于现有对象创建新 Draft
val updated = Subscription(sub) {
    active = false
    subStatus = "refunded"
    // updatedAt 由 TimestampDraftInterceptor 自动填充，不用手动设
}
subscriptionRepo.save(sc, updated)
```

---

### 4. [CRITICAL] 调用不存在的 `insert()` 方法

**影响**: `AuthEntityService` 中大量 `xxxRepo.insert(sc, entity)` 调用。

**问题**: `BaseCrudRepository` 和 `BaseAppCrudRepository` 只有 `save()`，没有 `insert()`。

**修复方案（二选一）**:

**方案 A（推荐）**: 直接将 `insert` 替换为 `save`：
```kotlin
// ❌
deviceSecretRepo.insert(sc, entity)
// ✅
deviceSecretRepo.save(sc, entity)
```

Jimmer 的 `save()` 默认行为是 "有 id 就 upsert"，对 UUIDv7 新生成的 id 等价于 INSERT。

**方案 B**: 如果需要严格 INSERT（不允许 upsert），可以在 base repo 加方法：
```kotlin
open fun insert(ctx: SvcCtx, entity: E): E =
    sql.entities.save(entity) {
        setMode(SaveMode.INSERT_ONLY)
    }.modifiedEntity
```

我建议方案 A（简单替换），因为 UUIDv7 保证新 id 不冲突，save 行为等价于 insert。

---

### 5. [CRITICAL] `AppUserRepository` 缺少 `ensure()` 方法

**文件**: `modules/auth/repo/AppUserRepository.kt`

**问题**: `AuthEntityService` 调用 `appUserRepo.ensure(sc, appId, identityId)` 但方法不存在。

**修复** — 添加方法：
```kotlin
fun ensure(ctx: SvcCtx, appId: UUID, authIdentityId: UUID): UUID {
    val existing = findByAppAndIdentity(ctx, appId, authIdentityId)
    if (existing != null) return existing.id
    
    val id = UuidV7.generate()
    val now = Instant.now()
    val entity = AppUser {
        this.id = id
        this.appId = appId
        authIdentity { this.id = authIdentityId }
        metadata = null
        createdAt = now
        updatedAt = now
    }
    save(ctx, entity)
    return id
}
```

---

### 6. [CRITICAL] `SubscriptionRepository` 缺少 `upsertSubscription()` 方法

**文件**: `modules/payment/repo/SubscriptionRepository.kt`

**修复** — 添加方法（利用 Jimmer `@Key` 的 upsert 能力）：
```kotlin
fun upsertSubscription(ctx: SvcCtx, entity: Subscription) {
    // Subscription 有 @Key val subscriptionPxid，Jimmer save 会自动 upsert by key
    sql.entities.save(entity) {
        setKeyProps(Subscription::subscriptionPxid)
    }
}
```

---

### 7. [HIGH] `isNull` 需要调用为 `isNull()`

**影响文件**: 
- `AppRefreshTokenRepository.kt`
- `AuthDeviceSecretRepository.kt`
- `AgnesKeyRepository.kt`

**修复**:
```kotlin
// ❌ 错误
where(table.revokedAt.isNull)

// ✅ 正确
where(table.revokedAt.isNull())
```

---

### 8. [HIGH] `.or()` / `.gt()` 操作符使用错误

**影响**: `AppRefreshTokenRepository`、`AuthDeviceSecretRepository`、`AgnesKeyRepository`

**问题**: `table.expiresAt.isNull.or(table.expiresAt gt now)` — 链式 `.or()` 不是 Jimmer 的语法。

**修复**:
```kotlin
// ❌ 错误
where(table.expiresAt.isNull.or(table.expiresAt gt now))

// ✅ 正确 — 使用 or {} block
where(
    or(
        table.expiresAt.isNull(),
        table.expiresAt gt now
    )
)
```

需要 import: `import org.babyfish.jimmer.sql.kt.ast.expression.or`

---

### 9. [HIGH] `.desc()` 扩展函数未正确引用

**影响文件**: 多个 repo 的 `orderBy(table.id.desc())` 或 `orderBy(table.createdAt.desc())`

**修复**: 确保 import:
```kotlin
import org.babyfish.jimmer.sql.kt.ast.expression.desc
```

如果仍不行，使用完整写法：
```kotlin
orderBy(table.id.desc())
// 或
orderBy(table.getId<UUID>().desc())
```

---

### 10. [HIGH] `UserInstallBindingRepository` import 路径错误

**文件**: `modules/auth/repo/UserInstallBindingRepository.kt`

**问题**: 导入了 `com.ifmix.api.core.entity.auth.UserInstallBinding.appId` 形式（从 interface companion 导入），Jimmer 生成的扩展属性不在 companion 里。

**修复** — 删除所有 `UserInstallBinding.xxx` 导入，只保留包级导入：
```kotlin
// ❌ 错误
import com.ifmix.api.core.entity.auth.UserInstallBinding.appId
import com.ifmix.api.core.entity.auth.UserInstallBinding.userId

// ✅ 正确 — Jimmer KSP 生成的扩展属性在包级
import com.ifmix.api.core.entity.auth.appId
import com.ifmix.api.core.entity.auth.userId
import com.ifmix.api.core.entity.auth.installId
// ... 等等
```

同时 `createUpdate` 中的 `set(lastSeenAt, now)` 需要改为 `set(table.lastSeenAt, now)`。

---

### 11. [HIGH] `authIdentityId` 等 FK 字段在 service 层的访问方式

**影响**: `AuthEntityService` 中大量 `xxx.authIdentityId`、`xxx.appUserId`、`xxx.deviceSecretId`

**Jimmer 规则**: `@ManyToOne` 声明的关联，KSP 会自动生成 `xxxId` 属性（如 `authIdentity` → 自动有 `authIdentityId`）。这些可以直接访问，**前提是 KSP 成功运行**。

**修复**: 
1. 先确保 `AppScopedProps` 加上 `@MappedSuperclass`（问题 1）
2. 确保 KSP 正常运行（`./gradlew :core-api:kspKotlin`）
3. KSP 运行后，`entity.authIdentityId` 是可用的（Jimmer 自动生成的 IdView 属性）

如果 KSP 生成后仍编译失败，可能需要显式声明 `@IdView`：
```kotlin
// 在 AppUser entity 中
@IdView("authIdentity")
val authIdentityId: UUID
```

---

### 12. [HIGH] `findActiveByAppId()` 返回 nullable 但调用方未处理

**影响文件**:
- `AuthEntityService.kt:118` — `appConfigRepo.findActiveByAppId(sc, appId).authTenantId`
- `PaymentEntityService.kt:53` — `config.content.iap.productTierMap`

**修复**:
```kotlin
// ❌
val config = appConfigRepo.findActiveByAppId(sc, appId)
val productTierMap = config.content.iap.productTierMap

// ✅
val config = appConfigRepo.findActiveByAppId(sc, appId)
    ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
val productTierMap = config.content.iap.productTierMap
```

---

### 13. [HIGH] `rawResponse` 类型不匹配

**文件**: `PaymentEntityService.kt:85`

**问题**: entity 声明 `val rawResponse: Map<String, Any?>?` 但代码赋值 `ObjectMapper().writeValueAsString(...)` 返回 `String`。

**修复**:
```kotlin
// ❌ 
rawResponse = tools.jackson.databind.ObjectMapper().writeValueAsString(mapOf(...))

// ✅ — 直接赋 Map，Jimmer @Serialized 会自动序列化为 JSONB
rawResponse = mapOf(
    "original_transaction_id" to verifyResult.originalTransactionId,
    "product_id" to req.productId,
    "expiry_date" to verifyResult.expiryDate?.toString(),
    "sub_status" to verifyResult.subStatus.name,
    "platform" to req.platform,
)
```

---

### 14. [HIGH] `category = req.category.toShort()` 类型错误

**文件**: `CmsEntityService.kt:19`

**问题**: `Feedback.category` 类型是 `Int`，但 `.toShort()` 转成了 `Short`。

**修复**:
```kotlin
// ❌
category = req.category.toShort()
// ✅
category = req.category
```

---

### 15. [MEDIUM] `table.get<UUID>("appId")` 失去类型安全

**影响**: 全项目 32 处

**说明**: `table.get<T>("fieldName")` 是 Jimmer 的 string-based 属性引用，功能正确但失去了编译期检查。

**修复**:
- 等 `AppScopedProps` 加上 `@MappedSuperclass` 后，KSP 会为每个 entity 生成 `table.appId` 扩展属性
- 然后全局替换 `table.get<UUID>("appId")` → `table.appId`

先让项目编译通过，再做此优化。此项可以最后处理或作为后续 PR。

---

### 16. [MEDIUM] `ScanRecordsDataLoader` 完全坏掉

**文件**: `bff/graphql/customer/collection/CollectionFetcher.kt`

**问题**: 
```kotlin
val ctx = SvcCtx(op = OperationContext(req = RequestContext()), sql = sql)
```
创建了一个空的 OperationContext（没有 appId），然后 `findById(ctx, UUID.randomUUID(), id)` 用随机 UUID 当 appId 查询。

**修复**: DataLoader 需要从 DGS 的 context 获取 OperationContext：
```kotlin
@DgsDataLoader(name = ScanRecordsDataLoader.NAME, caching = false)
class ScanRecordsDataLoader(
    private val scanRecordRepo: ScanRecordRepository,
    private val svcCtxFactory: SvcCtxFactory,
) : MappedBatchLoader<UUID, ScanRecord?> {
    
    override fun load(ids: Set<UUID>): CompletionStage<Map<UUID, ScanRecord?>> {
        // DataLoader 在 DGS 中通过 DgsDataFetchingEnvironment 获取 context
        // 但 MappedBatchLoader 不接收 DFE — 需要通过 DgsContext / ThreadLocal
        // 
        // ponytail: 当前先用 OperationContextHolder 获取（在 AuthInterceptor 中设置过）
        val opCtx = OperationContextHolder.current()
        val sc = svcCtxFactory.forApp(opCtx)
        val appId = opCtx.mustGetAppId()
        
        val records = scanRecordRepo.findByIds(sc, appId, ids.toList())
        val map = records.associateBy { it.id }
        return CompletableFuture.completedFuture(
            ids.associateWith { map[it] }
        )
    }
    
    companion object { const val NAME = "scanRecords" }
}
```

---

### 17. [MEDIUM] `findByFilter` 被调用但从未实现

**影响**: `AiModuleService.findByFilter()` → `ScanEntityService.findByFilter()` 不存在

**修复**: 暂时先 stub 掉，或者实现一个基本版本：
```kotlin
// ScanEntityService.kt 中添加
fun findByFilter(sc: SvcCtx, filter: FilterGroup?, cursor: String?, limit: Int?): Page<ScanRecord> {
    // ponytail: FilterGroup 解析暂未实现，fallback 到普通游标查询
    return findByCursorFiltered(sc, cursor, limit, null)
}
```

---

### 18. [MEDIUM] `TodoItemRepository` 中 `TodoItem` 未 import

**文件**: `modules/demo/repo/TodoItemRepository.kt`

**问题**: `BaseCrudRepository<TodoItem>` 但 `TodoItem` 未导入。

**修复**: 添加 `import com.ifmix.api.core.entity.demo.TodoItem`

---

### 19. [MEDIUM] `ScanCollectionRepository.findById` 缺少 `override`

**问题**: 子类定义了与父类签名相同的方法但没有加 `override`。

**修复**: 加上 `override` 关键字。

---

### 20. [LOW] 文档过时

**需更新**:
1. `docs/ARCHITECTURE.md` — 状态说明改为 "已完成 Jimmer 迁移"，删除 jOOQ 相关段落
2. `AGENTS.md` — 技术栈中 jOOQ 替换为 Jimmer，更新常用命令（去掉 `generateJooq`）
3. `FeedbackFetcher.kt` — 删除 `（jOOQ 版）` 注释

---

### 21. [LOW] 项目根目录遗留文件

**删除**:
- `/org/babyfish/jimmer/` — 旧的 .class 文件缓存
- `/META-INF/jimmer-sql-kotlin.kotlin_module` — 同上
- `/META-INF/MANIFEST.MF`

这些是之前本地编译残留，对项目无影响但污染 git。

---

## 执行顺序

1. **先修 `AppScopedProps` 加 `@MappedSuperclass`**（问题 1），然后运行 `./gradlew :core-api:kspKotlin` 让 KSP 重新生成
2. **修所有 entity 构造方式**（问题 2, 3）— 批量 `Xxx(...)` → `Xxx {...}`
3. **修 `insert` → `save`**（问题 4）
4. **补缺失方法** `ensure()`, `upsertSubscription()`（问题 5, 6）
5. **修 DSL 语法** `isNull()`, `or()`, `desc()`, import（问题 7, 8, 9, 10）
6. **修 nullable 处理**（问题 12, 13, 14）
7. **修 DataLoader / stub**（问题 16, 17, 18, 19）
8. **验证编译通过**: `./gradlew :core-api:compileKotlin`
9. **最后做优化** `table.get<>` → 强类型（问题 15）
10. **更新文档 + 清理文件**（问题 20, 21）

---

## 关键原则

- **Jimmer entity 是 interface**，永远不能用 `Xxx(param=value)` 构造，必须用 `Xxx { prop = value }` DSL
- **Jimmer entity 没有 `.copy()`**，修改实体用 `Xxx(existingEntity) { 修改的字段 }`
- **`@ManyToOne` 关联的 FK 字段** 由 KSP 自动生成 `xxxId` 属性（`@IdView`），不用手动声明
- **`save()` 是万能写入**：新 id 等于 insert，已有 id 等于 upsert
- **`@MappedSuperclass`** 是继承链中必须有的注解，缺了它 KSP 不认识父接口的字段
- **Jimmer 的 `isNull` 是函数调用** `isNull()`，不是属性
- **`or` / `and`** 使用顶层函数 `or(expr1, expr2)` 或 `or { expr1; expr2 }`

## 验收标准

`./gradlew :core-api:compileKotlin` 零错误通过。

---

## 补充修复（2026-08-19 22:00 更新）

### 根因已确认并解决

#### 问题 A: `AppScopedProps` 的 `@MappedSuperclass` + 子类 override 冲突

**根因**: Jimmer 规则 — `@MappedSuperclass` 的属性由 KSP 自动继承，子 entity **不允许** `override`。

**已完成修复**:
1. `AppScopedProps.kt` 已加上 `@MappedSuperclass`
2. 所有 12 个子 entity 中的 `override val appId: UUID` 行已删除
3. KSP (`./gradlew :core-api:kspKotlin`) 已成功通过

---

#### 问题 B: [36 个] `'val' cannot be reassigned` — Draft DSL 里属性名与方法参数名冲突

**根因**: 当 Jimmer Draft DSL block `Entity { ... }` 里有一个属性名（如 `provider`）和外层方法参数名相同时，Kotlin 编译器把左侧解析为外层 val 参数，而不是 Draft 的 setter。

**修复**: 在 Draft block 里给**所有属性赋值加 `this.` 前缀**。

```kotlin
// ❌ 报错 'val' cannot be reassigned — provider 是外层函数参数
val entity = AuthProviderIdentity {
    id = id                          // id 也是外层 val
    provider = provider              // 编译器认为是 val provider = provider
    email = email
    ...
}

// ✅ 正确 — this. 明确指向 Draft 属性
val entity = AuthProviderIdentity {
    this.id = id
    this.provider = provider
    this.email = email
    this.emailVerified = emailVerified
    this.phone = phone
    this.userMetadata = userMetadata
    this.providerMetadata = providerMetadata
    this.loginIp = loginIp
    this.loginInstallId = loginInstallId
    this.loginAppId = loginAppId
    this.createdAt = existing?.createdAt ?: now
    this.updatedAt = now
}
```

**影响文件（需全量加 `this.`）**:
- `modules/auth/repo/AuthProviderIdentityRepository.kt` — upsert 方法
- `modules/auth/repo/AppUserRepository.kt` — ensure 方法
- `modules/auth/service/internal/AuthEntityService.kt` — 多个 Draft block
- `modules/cms/service/internal/CmsEntityService.kt` — submit 方法
- `modules/payment/service/internal/PaymentEntityService.kt` — verifyIapPurchase
- `modules/payment/service/internal/PaymentWebhookHandler.kt` — updateSubscription + createStoreNotification

**规则**: **在所有 `Entity { ... }` DSL block 中，属性赋值一律使用 `this.xxx = value`**，避免与外层变量歧义。这是 Jimmer 项目的最佳实践。

---

#### 问题 C: 其余 22 个编译错误

| 错误 | 修复 |
|------|------|
| `Unresolved reference 'authTenantId'` | 在 Query DSL 中用 `table.authTenantId` — KSP 已生成 `@IdView` 扩展。如果仍不行用 `table.authTenant.id` |
| `Unresolved reference 'getAuthIdentityId'` | Jimmer 不生成 getter 方法。直接 `entity.authIdentityId`（KSP 生成的属性）或 `entity.authIdentity.id` |
| `Unresolved reference 'appUserId'` | 改为 `entity.appUser.id` 或用生成的 `entity.appUserId`（@IdView） |
| `Unresolved reference 'deviceSecretId'` | 改为 `entity.deviceSecret?.id`（nullable @ManyToOne） |
| `Unresolved reference 'userId'` in SubscriptionRepository | Subscription entity 没有 userId 字段，检查原 schema |
| `Unresolved reference 'images'` | ScanRecord 已改名为 `imageKeys`，Service 需同步 |
| `Unresolved reference 'result'` | ScanRecord 已改名为 `basicResult`/`premiumResult`，Service 需同步 |
| `Unresolved reference 'AgnesKeyType'` | 确认 import 路径: `com.ifmix.api.core.entity.ai.AgnesKeyType` |
| `No value passed for parameter 'id'` | AppConfigEntityService 里构造实体时缺少 id |
| `Cannot infer type` | AppUserRepository 里嵌套 Draft 需要完整类型（见下） |
| `None of the following candidates` | ScanCollectionItemRepository 的 insertIfAbsent 里 Draft 构造语法错 |
| `Argument type mismatch: AppConfigRevision?` | 加 `?: throw ApiError(...)` null 检查 |
| `Assignment type mismatch: String vs NotificationType` | `notificationType = notificationType.name` 改为 `this.notificationType = notificationType` (entity 字段如果是 String 则 `.name` 是对的，检查 entity 定义) |

---

#### 问题 D: AppUserRepository.ensure() 里的 Draft 嵌套

CC 尝试用 `AppUserDraft` / `AuthIdentityDraft` 但这不是正确的方式。正确做法：

```kotlin
fun ensure(ctx: SvcCtx, appId: UUID, authIdentityId: UUID): UUID {
    val existing = findByAppAndIdentity(ctx, appId, authIdentityId)
    if (existing != null) return existing.id
    
    val id = UuidV7.generate()
    val entity = AppUser {
        this.id = id
        // appId 继承自 @MappedSuperclass，在 Draft 里直接赋值
        this.appId = appId
        // @ManyToOne 关联 — 只设置 id 用 makeIdOnly
        this.authIdentity = makeIdOnly(AuthIdentity::class, authIdentityId)
        this.metadata = null
    }
    save(ctx, entity)
    return id
}
```

或者更简单的写法（Jimmer 0.11.5 支持）：
```kotlin
val entity = AppUser {
    this.id = id
    this.appId = appId
    this.authIdentityId = authIdentityId  // KSP @IdView 生成的 shortcut
    this.metadata = null
}
```

如果 `authIdentityId` 不可用（KSP 没生成 @IdView），可以在 entity 里显式声明：
```kotlin
// entity/auth/AppUser.kt
@IdView("authIdentity")
val authIdentityId: UUID
```

---

## 执行步骤（CC 请按此顺序）

1. ✅ `AppScopedProps` 已加 `@MappedSuperclass`（已完成）
2. ✅ 所有子 entity 的 `override val appId` 已删除（已完成）
3. ✅ KSP 已通过（已完成）
4. **所有 `Entity { ... }` DSL block 内属性赋值加 `this.`** — 修 36 个 val 错误
5. 修剩余 22 个 unresolved/type 错误（参考上表）
6. `./gradlew :core-api:compileKotlin` 零错误
