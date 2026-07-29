# Jimmer + PostgreSQL 迁移 — 第二阶段实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 ifmix_server 剩余全部模块从 MongoDB 迁移到 Jimmer + PostgreSQL：antique（古物扫描）、collection（收藏夹）、iap（内购订阅）、auth（认证）、appconfig（应用配置）、agnes_keys（AI 密钥）。

**前置条件：** 第一阶段已完成——Jimmer 基础设施（ClusterRegistry、ReadWriteRoutingDataSource、BaseCrudRepository/Service、AppScopedFilter）、todo 和 feedback 模块已迁移。

**技术栈：** Kotlin 2.3.10, Spring Boot 4.1, Jimmer 0.11.5 (KSP), PostgreSQL, Flyway, HikariCP, Gradle 9.6.1, Testcontainers

**迁移策略：**
1. 按依赖关系顺序迁移：appconfig → auth → antique → collection → iap → agnes_keys
2. 每个模块独立一个 Flyway migration 文件（V2–V7）
3. 迁移完成后删除对应 MongoDB Document/Repo/Mapper 文件
4. 最终删除 MongoDB 依赖和通用基础设施（CRUDRepository 等）

**规格文件：** `docs/superpowers/specs/2026-07-29-jimmer-pg-migration-design.md`

---

## 文件结构总览

### 新建 Jimmer Entity 文件

| 文件路径 | 职责 |
|---|---|
| `common/jimmer/entity/appconfig/AppInfo.kt` | 应用注册表实体 |
| `common/jimmer/entity/appconfig/AppConfig.kt` | 应用配置实体（版本化） |
| `common/jimmer/entity/auth/AuthTenant.kt` | 认证租户实体 |
| `common/jimmer/entity/auth/AuthIdentity.kt` | 租户级用户身份实体 |
| `common/jimmer/entity/auth/AuthProviderIdentity.kt` | 第三方登录身份实体 |
| `common/jimmer/entity/auth/AppUser.kt` | App 级用户实体 |
| `common/jimmer/entity/auth/AppRefreshToken.kt` | Refresh Token 实体 |
| `common/jimmer/entity/auth/AuthDeviceSecret.kt` | 设备密钥实体 |
| `common/jimmer/entity/antique/ScanRecord.kt` | 古物扫描记录实体 |
| `common/jimmer/entity/collection/Collection.kt` | 收藏夹实体 |
| `common/jimmer/entity/collection/CollectionItem.kt` | 收藏条目实体 |
| `common/jimmer/entity/iap/Subscription.kt` | 订阅记录实体 |
| `common/jimmer/entity/iap/StoreNotification.kt` | 商店通知实体 |
| `common/jimmer/entity/ai/AgnesKey.kt` | AI API Key 实体 |

### 新建 DTO 文件

| 文件路径 | 职责 |
|---|---|
| `src/main/dto/.../appconfig/AppConfig.dto` | AppConfig DTO |
| `src/main/dto/.../auth/AppUser.dto` | AppUser DTO |
| `src/main/dto/.../antique/ScanRecord.dto` | ScanRecord DTO |
| `src/main/dto/.../collection/Collection.dto` | Collection DTO |
| `src/main/dto/.../collection/CollectionItem.dto` | CollectionItem DTO |
| `src/main/dto/.../iap/Subscription.dto` | Subscription DTO |

### 新建 Repository 文件

| 文件路径 | 职责 |
|---|---|
| `common/jimmer/repository/appconfig/AppInfoRepository.kt` | AppInfo 仓储 |
| `common/jimmer/repository/appconfig/AppConfigRepository.kt` | AppConfig 仓储（版本化） |
| `common/jimmer/repository/auth/AuthTenantRepository.kt` | AuthTenant 仓储 |
| `common/jimmer/repository/auth/AuthIdentityRepository.kt` | AuthIdentity 仓储 |
| `common/jimmer/repository/auth/AuthProviderIdentityRepository.kt` | Provider 身份仓储 |
| `common/jimmer/repository/auth/AppUserRepository.kt` | AppUser 仓储 |
| `common/jimmer/repository/auth/AppRefreshTokenRepository.kt` | RefreshToken 仓储 |
| `common/jimmer/repository/auth/AuthDeviceSecretRepository.kt` | DeviceSecret 仓储 |
| `common/jimmer/repository/antique/ScanRecordRepository.kt` | ScanRecord 仓储 |
| `common/jimmer/repository/collection/CollectionRepository.kt` | Collection 仓储 |
| `common/jimmer/repository/collection/CollectionItemRepository.kt` | CollectionItem 仓储 |
| `common/jimmer/repository/iap/SubscriptionRepository.kt` | Subscription 仓储 |
| `common/jimmer/repository/iap/StoreNotificationRepository.kt` | StoreNotification 仓储 |
| `common/jimmer/repository/ai/AgnesKeyRepository.kt` | AgnesKey 仓储 |

### Flyway Migration 文件

| 文件路径 | 内容 |
|---|---|
| `db/migration/V2__appconfig.sql` | app_info + app_config 表 |
| `db/migration/V3__auth.sql` | auth 全部 6 张表 |
| `db/migration/V4__antique.sql` | scan_record 表 |
| `db/migration/V5__collection.sql` | collection + collection_item 表 |
| `db/migration/V6__iap.sql` | subscription + store_notification 表 |
| `db/migration/V7__agnes_key.sql` | agnes_key 表 |

### 删除文件（迁移完成后）

| 模块 | 删除文件 |
|---|---|
| appconfig | `AppConfigDocument.kt`, `AppInfoDocument.kt`, `AppConfigRepo.kt`, `AppInfoRepo.kt`, `AppConfigMapper.kt` |
| auth | `AuthIdentityDocument.kt`, `AuthProviderIdentityDocument.kt`, `AppUserDocument.kt`, `AppRefreshTokenDocument.kt`, `AuthDeviceSecretDocument.kt`, `AuthTenantDocument.kt`, `AuthProviderIdentityRepo.kt`, `AppUserRepo.kt`, `AppRefreshTokenRepo.kt`, `AuthDeviceSecretRepo.kt`, `AuthTenantRepo.kt` |
| antique | `ScanRecordDocument.kt`, `ScanRecordRepository.kt`, `ScanMapper.kt`, `ScanDtos.kt` |
| collection | `CollectionDocument.kt`, `CollectionItemDocument.kt`, `CollectionRepository.kt`, `CollectionItemRepository.kt`, `CollectionDtos.kt` |
| iap | `SubscriptionDocument.kt`, `StoreNotificationDocument.kt`, `SubscriptionRepo.kt` |
| ai | `AgnesKeyDocument.kt` |
| common | `BaseDocument.kt`, `CRUDRepository.kt`, `CRUDService.kt`（全部模块迁移完后） |

---

## 任务 1：AppConfig 模块迁移

**优先级：** 最高（auth 模块依赖 appconfig 查询 authTenantId）

**文件：**
- 创建：`core-api/src/main/resources/db/migration/V2__appconfig.sql`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/appconfig/AppInfo.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/appconfig/AppConfig.kt`
- 创建：`core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/appconfig/AppConfig.dto`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/appconfig/AppInfoRepository.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/appconfig/AppConfigRepository.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/modules/appconfig/AppConfig.kt`（Service 重写）
- 删除：`AppConfigDocument.kt`, `AppInfoDocument.kt`, `AppConfigRepo.kt`, `AppInfoRepo.kt`, `AppConfigMapper.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/modules/appconfig/AppConfigServiceJimmerTest.kt`

### 步骤

- [ ] **1.1：创建 V2__appconfig.sql**

```sql
-- V2__appconfig.sql
-- AppInfo：全局应用注册表（不按 appId 分片，_id 即 appId）
CREATE TABLE IF NOT EXISTS app_info (
    id UUID NOT NULL PRIMARY KEY,
    name VARCHAR(255),
    description TEXT,
    slug VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS app_info_slug_uq ON app_info (slug) WHERE slug IS NOT NULL;

-- AppConfig：per-app 配置（版本化，partial unique 保证至多一条当前版本）
CREATE TABLE IF NOT EXISTS app_config (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    auth_tenant_id UUID,
    apple_bundle_id VARCHAR(255),
    android_package_name VARCHAR(255),
    -- Apple 配置 JSON
    apple_config JSONB NOT NULL DEFAULT '{}',
    -- Google 配置 JSON
    google_config JSONB NOT NULL DEFAULT '{}',
    -- IAP 配置 JSON
    iap_config JSONB NOT NULL DEFAULT '{}',
    revision INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS app_config_current_uq ON app_config (app_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS app_config_bundle_idx ON app_config (apple_bundle_id) WHERE apple_bundle_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS app_config_package_idx ON app_config (android_package_name) WHERE android_package_name IS NOT NULL;
```

- [ ] **1.2：创建 AppInfo Entity**

```kotlin
@Entity
@Table(name = "app_info")
interface AppInfo {
    @Id
    val id: UUID
    val name: String?
    val description: String?
    val slug: String?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **1.3：创建 AppConfig Entity**

注意：apple/google/iap 配置使用 `@Serialized`（Jimmer JSON 列映射）存储为 JSONB。

```kotlin
@Entity
@Table(name = "app_config")
interface AppConfig : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    override val appId: UUID
    val authTenantId: UUID?
    val appleBundleId: String?
    val androidPackageName: String?
    @Serialized
    val appleConfig: AppleConfigValue
    @Serialized
    val googleConfig: GoogleConfigValue
    @Serialized
    val iapConfig: IapConfigValue
    val revision: Int
    @LogicalDeleted("now")
    val deletedAt: Instant?
    val createdAt: Instant
    val updatedAt: Instant
}

/** JSONB 内嵌值对象 */
data class AppleConfigValue(
    val appAppleId: String? = null,
    val issuerId: String? = null,
    val keyId: String? = null,
    val privateKey: String? = null,
    val servicesId: String? = null,
)

data class GoogleConfigValue(
    val serviceAccount: String? = null,
    val clientIds: GoogleClientIdsValue = GoogleClientIdsValue(),
)

data class GoogleClientIdsValue(
    val ios: String? = null,
    val android: String? = null,
    val web: String? = null,
)

data class IapConfigValue(
    val productTierMap: Map<String, String> = emptyMap(),
    val env: String? = null,
)
```

- [ ] **1.4：创建 AppConfig.dto**

```
export com.ifmix.api.core.common.jimmer.entity.appconfig.AppConfig
    -> package com.ifmix.api.core.common.jimmer.dto.appconfig

AppConfigView {
    #allScalars
}
```

- [ ] **1.5：创建 AppInfoRepository + AppConfigRepository**

AppConfigRepository 需要自定义方法：
- `findCurrentByAppId(appId: UUID): AppConfig?` — 查找 deletedAt IS NULL 的当前配置
- `findByBundleId(bundleId: String): AppConfig?`

- [ ] **1.6：重写 AppConfigRepo（现有 Service 层）使用 Jimmer Repository**

保持对外接口不变：`getByAppId(appId: String): AppConfigDocument?` → `getByAppId(appId: String): AppConfig?`
注意：IapService、AuthService 都调用 `appConfigRepo.getByAppId()`，需要调整返回类型或提供兼容适配。

策略：引入新的 `AppConfigService` 返回 Jimmer Entity，同时调整 `IapService` 和 `AuthService` 的引用。

- [ ] **1.7：删除旧 MongoDB 文件**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/appconfig/AppConfigDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/appconfig/AppInfoDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/appconfig/AppConfigRepo.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/appconfig/AppInfoRepo.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/appconfig/AppConfigMapper.kt
```

- [ ] **1.8：编写 AppConfigServiceJimmerTest 并验证通过**

- [ ] **1.9：Commit**

```bash
git add -A
git commit -m "feat: migrate AppConfig module from MongoDB to Jimmer + PostgreSQL"
```

---

## 任务 2：Auth 模块迁移

**优先级：** 高（核心认证流程，6 张表，最复杂的模块）

**文件：**
- 创建：`core-api/src/main/resources/db/migration/V3__auth.sql`
- 创建 Entity：`AuthTenant.kt`, `AuthIdentity.kt`, `AuthProviderIdentity.kt`, `AppUser.kt`, `AppRefreshToken.kt`, `AuthDeviceSecret.kt`
- 创建 Repository：对应 6 个 Repository
- 修改：`AuthService.kt`（调整 Repo 引用）
- 删除：6 个 Document + 5 个旧 Repo
- 测试：`AuthServiceJimmerTest.kt`

### 步骤

- [ ] **2.1：创建 V3__auth.sql**

```sql
-- V3__auth.sql
-- Auth 模块：认证租户、身份、Provider 身份、App 用户、Refresh Token、设备密钥

-- 认证租户（全局，不按 appId）
CREATE TABLE IF NOT EXISTS auth_tenant (
    id UUID NOT NULL PRIMARY KEY,
    jwt_private_key_pem TEXT,
    jwt_issuer VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 租户级用户身份
CREATE TABLE IF NOT EXISTS auth_identity (
    id UUID NOT NULL PRIMARY KEY,
    auth_tenant_id UUID NOT NULL REFERENCES auth_tenant(id),
    raw_email VARCHAR(255),
    email VARCHAR(255),
    raw_phone VARCHAR(50),
    phone VARCHAR(50),
    contact_email VARCHAR(255),
    display_name VARCHAR(255),
    password_hash VARCHAR(255),
    profile JSONB,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS auth_identity_tenant_idx ON auth_identity (auth_tenant_id, id);
CREATE INDEX IF NOT EXISTS auth_identity_tenant_email_idx ON auth_identity (auth_tenant_id, email);
CREATE INDEX IF NOT EXISTS auth_identity_tenant_phone_idx ON auth_identity (auth_tenant_id, phone);

-- Provider 身份（第三方登录）
CREATE TABLE IF NOT EXISTS auth_provider_identity (
    id UUID NOT NULL PRIMARY KEY,
    auth_tenant_id UUID NOT NULL REFERENCES auth_tenant(id),
    auth_identity_id UUID NOT NULL REFERENCES auth_identity(id),
    provider VARCHAR(32) NOT NULL,
    provider_account_id VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone VARCHAR(50),
    user_metadata JSONB,
    provider_metadata JSONB,
    login_ip VARCHAR(45),
    login_install_id VARCHAR(255),
    login_app_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS auth_provider_uq
    ON auth_provider_identity (auth_tenant_id, provider, provider_account_id);
CREATE INDEX IF NOT EXISTS auth_provider_identity_idx
    ON auth_provider_identity (auth_tenant_id, auth_identity_id);

-- App 级用户
CREATE TABLE IF NOT EXISTS app_user (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    auth_identity_id UUID NOT NULL REFERENCES auth_identity(id),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS app_user_uq ON app_user (app_id, auth_identity_id);

-- Refresh Token
CREATE TABLE IF NOT EXISTS app_refresh_token (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    app_user_id UUID NOT NULL REFERENCES app_user(id),
    device_secret_id UUID REFERENCES auth_device_secret(id),
    token_hash VARCHAR(255) NOT NULL,
    login_install_id VARCHAR(255),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS refresh_token_uq ON app_refresh_token (app_id, token_hash);
CREATE INDEX IF NOT EXISTS refresh_appuser_idx ON app_refresh_token (app_id, app_user_id);
CREATE INDEX IF NOT EXISTS refresh_device_idx ON app_refresh_token (device_secret_id);

-- 设备密钥（先于 refresh_token 建表，因 FK 依赖）
CREATE TABLE IF NOT EXISTS auth_device_secret (
    id UUID NOT NULL PRIMARY KEY,
    auth_tenant_id UUID NOT NULL REFERENCES auth_tenant(id),
    auth_identity_id UUID NOT NULL REFERENCES auth_identity(id),
    secret_hash VARCHAR(255) NOT NULL,
    login_install_id VARCHAR(255),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS device_secret_uq ON auth_device_secret (auth_tenant_id, secret_hash);
CREATE INDEX IF NOT EXISTS device_secret_identity_idx ON auth_device_secret (auth_tenant_id, auth_identity_id);
```

注意：`app_refresh_token.device_secret_id` 引用 `auth_device_secret(id)`，需要调整建表顺序（device_secret 在 refresh_token 之前）。

- [ ] **2.2：创建 AuthTenant Entity**

```kotlin
@Entity
@Table(name = "auth_tenant")
interface AuthTenant {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    val jwtPrivateKeyPem: String?
    val jwtIssuer: String?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **2.3：创建 AuthIdentity Entity**

```kotlin
@Entity
@Table(name = "auth_identity")
interface AuthIdentity {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    @ManyToOne
    @JoinColumn(name = "auth_tenant_id")
    val authTenant: AuthTenant
    val rawEmail: String?
    val email: String?
    val rawPhone: String?
    val phone: String?
    val contactEmail: String?
    val displayName: String?
    val passwordHash: String?
    @Serialized
    val profile: Map<String, Any?>?
    @Serialized
    val metadata: Map<String, Any?>?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **2.4：创建 AuthProviderIdentity Entity**

```kotlin
@Entity
@Table(name = "auth_provider_identity")
interface AuthProviderIdentity {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    @ManyToOne
    @JoinColumn(name = "auth_tenant_id")
    val authTenant: AuthTenant
    @ManyToOne
    @JoinColumn(name = "auth_identity_id")
    val authIdentity: AuthIdentity
    val provider: String
    val providerAccountId: String
    val email: String?
    val emailVerified: Boolean
    val phone: String?
    @Serialized
    val userMetadata: Map<String, Any?>?
    @Serialized
    val providerMetadata: Map<String, Any?>?
    val loginIp: String?
    val loginInstallId: String?
    val loginAppId: String?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **2.5：创建 AppUser Entity**

```kotlin
@Entity
@Table(name = "app_user")
interface AppUser : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    override val appId: UUID
    @ManyToOne
    @JoinColumn(name = "auth_identity_id")
    val authIdentity: AuthIdentity
    @Serialized
    val metadata: Map<String, Any?>?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **2.6：创建 AppRefreshToken Entity**

```kotlin
@Entity
@Table(name = "app_refresh_token")
interface AppRefreshToken : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    override val appId: UUID
    @ManyToOne
    @JoinColumn(name = "app_user_id")
    val appUser: AppUser
    @ManyToOne
    @JoinColumn(name = "device_secret_id")
    val deviceSecret: AuthDeviceSecret?
    val tokenHash: String
    val loginInstallId: String?
    val expiresAt: Instant?
    val revokedAt: Instant?
    val replacedBy: UUID?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **2.7：创建 AuthDeviceSecret Entity**

```kotlin
@Entity
@Table(name = "auth_device_secret")
interface AuthDeviceSecret {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    @ManyToOne
    @JoinColumn(name = "auth_tenant_id")
    val authTenant: AuthTenant
    @ManyToOne
    @JoinColumn(name = "auth_identity_id")
    val authIdentity: AuthIdentity
    val secretHash: String
    val loginInstallId: String?
    val expiresAt: Instant?
    val revokedAt: Instant?
    val lastUsedAt: Instant?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **2.8：创建 Auth 模块全部 Repository（6 个）**

每个 Repository 需实现的自定义方法：

| Repository | 自定义方法 |
|---|---|
| `AuthTenantRepository` | `findById(id)` |
| `AuthIdentityRepository` | 基础 CRUD |
| `AuthProviderIdentityRepository` | `upsert(tenantId, input)` — 按 tenant+provider+accountId 查找或插入 |
| `AppUserRepository` | `ensure(appId, identityId)` — 按 app+identity 查找或创建，返回 userId |
| `AppRefreshTokenRepository` | `issue(...)`, `findByHash(appId, hash)`, `tryRotate(...)`, `revokeByAppUser(...)`, `revokeByDeviceSecret(...)` |
| `AuthDeviceSecretRepository` | `issue(...)`, `findValid(tenantId, hash)`, `touch(id)`, `revoke(id)` |

- [ ] **2.9：重写 AuthService 引用新 Repository**

AuthService 构造函数参数从旧 Repo 切换到新 Jimmer Repository。保持业务逻辑不变，仅替换数据访问层。

关键变化：
- ObjectId → UUID（所有 ID 类型统一为 UUID）
- `mongo.findOne(query, ...)` → `repository.findXxx(...)`
- `TxRunner.withTx` → `@Transactional` 注解（Jimmer + Spring TX 自动管理）

- [ ] **2.10：删除旧 MongoDB 文件**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AuthIdentityDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AuthProviderIdentityDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AppUserDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AppRefreshTokenDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AuthDeviceSecretDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AuthTenantDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AuthProviderIdentityRepo.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AppUserRepo.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AppRefreshTokenRepo.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AuthDeviceSecretRepo.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/auth/AuthTenantRepo.kt
```

- [ ] **2.11：编写 AuthServiceJimmerTest 并验证通过**

测试覆盖：
- `loginWithProvider` 完整流程（upsert identity → ensure user → issue tokens）
- `exchange` 设备密钥换 token
- `refresh` 原子轮换
- `logout` 吊销

- [ ] **2.12：Commit**

```bash
git add -A
git commit -m "feat: migrate Auth module (6 tables) from MongoDB to Jimmer + PostgreSQL"
```

---

## 任务 3：Antique（古物扫描）模块迁移

**优先级：** 中高（collection 模块依赖 scan_record 做 join 查询）

**文件：**
- 创建：`core-api/src/main/resources/db/migration/V4__antique.sql`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/antique/ScanRecord.kt`
- 创建：`core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/antique/ScanRecord.dto`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/antique/ScanRecordRepository.kt`
- 修改：`AntiqueService.kt`（去掉 MongoTemplate 直接调用，改用 Repository）
- 修改：`CustomerAntiqueController.kt`（使用 Jimmer DTO 替代 Konvert mapper）
- 删除：`ScanRecordDocument.kt`, `ScanRecordRepository.kt`（旧）, `ScanMapper.kt`, `ScanDtos.kt`
- 测试：`AntiqueServiceJimmerTest.kt`

### 步骤

- [ ] **3.1：创建 V4__antique.sql**

```sql
-- V4__antique.sql
CREATE TABLE IF NOT EXISTS scan_record (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    scan_id VARCHAR(255),
    image_url TEXT,
    result_json TEXT,
    status VARCHAR(32),
    tier VARCHAR(32),
    client_ip VARCHAR(45),
    related_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS scan_record_app_id_idx ON scan_record (app_id, id DESC);
CREATE INDEX IF NOT EXISTS scan_record_scan_id_idx ON scan_record (scan_id) WHERE scan_id IS NOT NULL;
```

- [ ] **3.2：创建 ScanRecord Entity**

```kotlin
@Entity
@Table(name = "scan_record")
interface ScanRecord : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    override val appId: UUID
    val scanId: String?
    val imageUrl: String?
    val resultJson: String?
    val status: String?
    val tier: String?
    val clientIp: String?
    val relatedId: String?
    @LogicalDeleted("now")
    val deletedAt: Instant?
    val createdAt: Instant
    val updatedAt: Instant

    /** 反向关联：被哪些 collection_item 收藏 */
    @OneToMany(mappedBy = "scanRecord")
    val collectionItems: List<CollectionItem>
}
```

注意：`collectionItems` 关联需要在 CollectionItem 迁移后才能生效，先用 `@Transient` 或延后添加。

- [ ] **3.3：创建 ScanRecord.dto**

```
export com.ifmix.api.core.common.jimmer.entity.antique.ScanRecord
    -> package com.ifmix.api.core.common.jimmer.dto.antique

ScanRecordView {
    #allScalars
}

ScanRecordListItem {
    id
    scanId
    status
    imageUrl
    createdAt
}

input ScanRecordCreateInput {
    scanId
    imageUrl
    status
    tier
    clientIp
    relatedId
}
```

- [ ] **3.4：创建 ScanRecordRepository**

自定义方法：
- `findByScanId(ctx, scanId: String): ScanRecord?`
- `findByCursor` 继承自 BaseAppCrudRepository

- [ ] **3.5：重写 AntiqueService**

关键变化：
- 移除 `private val mongo: MongoTemplate` 依赖
- 使用 `ScanRecordRepository` + `RequestContext` 参数
- `mongo.insert(record)` → `repo.insert(ctx, scanRecordCreateInput)`
- `mongo.findById(id, ...)` → `repo.findById(ctx, UUID.fromString(id))`
- `mongo.find(query, ...)` → `repo.findByCursor(ctx, input)`

- [ ] **3.6：重写 CustomerAntiqueController**

- 移除 Konvert `ScanMapper` 依赖
- 使用 Jimmer 生成的 `ScanRecordView` / `ScanRecordListItem` DTO
- `mapper.toDto(...)` → 直接从 Service 拿 DTO view

- [ ] **3.7：删除旧文件**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/antique/ScanRecordDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/antique/ScanRecordRepository.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/antique/ScanMapper.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/antique/ScanDtos.kt
```

- [ ] **3.8：编写 AntiqueServiceJimmerTest 并验证通过**

- [ ] **3.9：Commit**

```bash
git add -A
git commit -m "feat: migrate Antique module from MongoDB to Jimmer + PostgreSQL"
```

---

## 任务 4：Collection（收藏夹）模块迁移

**优先级：** 中（依赖 antique 模块的 ScanRecord 做 join）

**文件：**
- 创建：`core-api/src/main/resources/db/migration/V5__collection.sql`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/collection/Collection.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/collection/CollectionItem.kt`
- 创建：`core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/collection/Collection.dto`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/collection/CollectionRepository.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/collection/CollectionItemRepository.kt`
- 修改：`CollectionService.kt`
- 修改：`CustomerCollectionController.kt`
- 删除：`CollectionDocument.kt`, `CollectionItemDocument.kt`, `CollectionRepository.kt`（旧）, `CollectionItemRepository.kt`（旧）, `CollectionDtos.kt`
- 测试：`CollectionServiceJimmerTest.kt`

### 步骤

- [ ] **4.1：创建 V5__collection.sql**

```sql
-- V5__collection.sql
CREATE TABLE IF NOT EXISTS collection (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    install_id VARCHAR(255),
    user_id VARCHAR(255),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS coll_app_install_idx ON collection (app_id, install_id);
-- partial unique：同一用户只能有一个默认夹
CREATE UNIQUE INDEX IF NOT EXISTS coll_default_uq
    ON collection (app_id, install_id) WHERE is_default = TRUE AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS collection_item (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    collection_id UUID NOT NULL REFERENCES collection(id),
    scan_record_id UUID NOT NULL REFERENCES scan_record(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS citem_app_coll_idx ON collection_item (app_id, collection_id, id DESC);
-- partial unique：同一 scan_record 在同一夹中只有一条有效记录
CREATE UNIQUE INDEX IF NOT EXISTS citem_coll_scan_uq
    ON collection_item (collection_id, scan_record_id) WHERE deleted_at IS NULL;
```

- [ ] **4.2：创建 Collection Entity**

```kotlin
@Entity
@Table(name = "collection")
interface Collection : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    override val appId: UUID
    val installId: String?
    val userId: String?
    val isDefault: Boolean
    @LogicalDeleted("now")
    val deletedAt: Instant?
    val createdAt: Instant
    val updatedAt: Instant

    @OneToMany(mappedBy = "collection")
    val items: List<CollectionItem>
}
```

- [ ] **4.3：创建 CollectionItem Entity**

```kotlin
@Entity
@Table(name = "collection_item")
interface CollectionItem : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    override val appId: UUID
    @ManyToOne
    @JoinColumn(name = "collection_id")
    val collection: Collection
    @ManyToOne
    @JoinColumn(name = "scan_record_id")
    val scanRecord: ScanRecord
    @LogicalDeleted("now")
    val deletedAt: Instant?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **4.4：创建 Collection.dto**

```
export com.ifmix.api.core.common.jimmer.entity.collection.Collection
    -> package com.ifmix.api.core.common.jimmer.dto.collection

CollectionView {
    id
    isDefault
    createdAt
}
```

- [ ] **4.5：创建 CollectionRepository + CollectionItemRepository**

CollectionRepository 自定义方法：
- `findDefault(ctx, installId, userId): Collection?` — partial unique 逻辑

CollectionItemRepository 自定义方法：
- `insertIfAbsent(ctx, collectionId, scanRecordId): UUID` — 幂等插入
- `softDeleteByScanIds(ctx, collectionId, scanRecordIds): Long` — 批量软删
- `listWithScanRecords(ctx, collectionId, cursor, limit): Page<ScanRecordView>` — join 查询

关键优势：Jimmer 的关联查询可以用 Fetcher 一次性 join 取回 `collectionItem.scanRecord`，不再需要两步查询。

- [ ] **4.6：重写 CollectionService**

关键变化：
- `CRUDService<CollectionDocument>` → 直接使用 `CollectionRepository`
- `DuplicateKeyException` → 捕获 Jimmer/PostgreSQL 的唯一约束违反（`org.postgresql.util.PSQLException` / `DataIntegrityViolationException`）
- `ownsRow()` 辅助函数逻辑保持不变
- `listItems` 利用 Jimmer Fetcher join 简化两步查询为一步

- [ ] **4.7：重写 CustomerCollectionController**

- 移除 Konvert `ScanMapper` 依赖
- 使用 Jimmer DTO 返回类型
- `CollectionMembership` 接口保留（如果需要可通过 Jimmer 子查询实现 `isCollected`）

- [ ] **4.8：删除旧文件**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/collection/CollectionDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/collection/CollectionItemDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/collection/CollectionRepository.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/collection/CollectionItemRepository.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/collection/CollectionDtos.kt
```

- [ ] **4.9：编写 CollectionServiceJimmerTest 并验证通过**

测试覆盖：
- `getDefault` — 首次创建 + 幂等获取
- `addItem` — 幂等插入
- `removeItems` — 批量软删
- `listItems` — join 分页 + 排序

- [ ] **4.10：Commit**

```bash
git add -A
git commit -m "feat: migrate Collection module from MongoDB to Jimmer + PostgreSQL"
```

---

## 任务 5：IAP（内购订阅）模块迁移

**优先级：** 中（独立模块，仅依赖 appconfig）

**文件：**
- 创建：`core-api/src/main/resources/db/migration/V6__iap.sql`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/iap/Subscription.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/iap/StoreNotification.kt`
- 创建：`core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/iap/Subscription.dto`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/iap/SubscriptionRepository.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/iap/StoreNotificationRepository.kt`
- 修改：`IapService.kt`
- 删除：`SubscriptionDocument.kt`, `StoreNotificationDocument.kt`, `SubscriptionRepo.kt`
- 测试：`IapServiceJimmerTest.kt`

### 步骤

- [ ] **5.1：创建 V6__iap.sql**

```sql
-- V6__iap.sql
CREATE TABLE IF NOT EXISTS subscription (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    subscription_pxid VARCHAR(255),
    original_transaction_id VARCHAR(255),
    product_id VARCHAR(255),
    platform VARCHAR(16),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    sub_status VARCHAR(32),
    expiry_date TIMESTAMPTZ,
    purchase_token TEXT,
    raw_response TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS sub_pxid_active_idx ON subscription (subscription_pxid, active);
CREATE INDEX IF NOT EXISTS sub_original_txn_idx ON subscription (original_transaction_id);
CREATE INDEX IF NOT EXISTS sub_app_id_idx ON subscription (app_id);

CREATE TABLE IF NOT EXISTS store_notification (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    platform VARCHAR(16),
    subscription_pxid VARCHAR(255),
    notification_type VARCHAR(64),
    raw_payload TEXT,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS store_notif_platform_sub_idx
    ON store_notification (platform, subscription_pxid, processed_at);
```

- [ ] **5.2：创建 Subscription Entity**

```kotlin
@Entity
@Table(name = "subscription")
interface Subscription : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    override val appId: UUID
    val subscriptionPxid: String?
    val originalTransactionId: String?
    val productId: String?
    @EnumType(EnumType.Strategy.NAME)
    val platform: Platform?
    val active: Boolean
    @EnumType(EnumType.Strategy.NAME)
    val subStatus: SubStatus?
    val expiryDate: Instant?
    val purchaseToken: String?
    val rawResponse: String?
    @LogicalDeleted("now")
    val deletedAt: Instant?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **5.3：创建 StoreNotification Entity**

```kotlin
@Entity
@Table(name = "store_notification")
interface StoreNotification : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    override val appId: UUID
    @EnumType(EnumType.Strategy.NAME)
    val platform: Platform?
    val subscriptionPxid: String?
    val notificationType: String?
    val rawPayload: String?
    val processed: Boolean
    val processedAt: Instant?
    @LogicalDeleted("now")
    val deletedAt: Instant?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **5.4：创建 Subscription.dto**

```
export com.ifmix.api.core.common.jimmer.entity.iap.Subscription
    -> package com.ifmix.api.core.common.jimmer.dto.iap

SubscriptionView {
    #allScalars
}
```

- [ ] **5.5：创建 SubscriptionRepository + StoreNotificationRepository**

SubscriptionRepository 自定义方法（对应旧 `SubscriptionRepo`）：
- `upsert(ctx, subscription)` — 按 subscriptionPxid + appId 查找后插入或更新
- `findActiveBySubject(ctx, subscriptionPxid): Subscription?`
- `updateByOriginalTxn(ctx, originalTxnId, updater)` — 按原始交易 ID 更新

StoreNotificationRepository：
- `findByPlatformAndPxid(ctx, platform, pxid): StoreNotification?` — 幂等检查

Jimmer 优势：`upsert` 可使用 Jimmer 的 `save` command 配合 `@Key` 注解实现自动 upsert。
在 Subscription Entity 上添加：`@Key val subscriptionPxid` + `@Key val appId`

- [ ] **5.6：重写 IapService**

关键变化：
- 移除 `MongoTemplate` 直接调用
- `SubscriptionRepo.upsert()` → `SubscriptionRepository.save()` (Jimmer upsert)
- `SubscriptionRepo.findActiveBySubject()` → Repository 自定义查询
- 通知处理中的 TODO 实现：使用 `StoreNotificationRepository` 做幂等检查

- [ ] **5.7：删除旧文件**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/iap/SubscriptionDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/iap/StoreNotificationDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/iap/SubscriptionRepo.kt
```

- [ ] **5.8：编写 IapServiceJimmerTest 并验证通过**

测试覆盖：
- `verifyPurchase` — upsert 订阅记录
- `handleAppleNotification` — 幂等通知处理 + 状态更新

- [ ] **5.9：Commit**

```bash
git add -A
git commit -m "feat: migrate IAP module from MongoDB to Jimmer + PostgreSQL"
```

---

## 任务 6：Agnes Key（AI 密钥）模块迁移

**优先级：** 低（独立模块，无外部依赖）

**文件：**
- 创建：`core-api/src/main/resources/db/migration/V7__agnes_key.sql`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/ai/AgnesKey.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/ai/AgnesKeyRepository.kt`
- 修改：Agnes Key 相关 Service（如有）
- 删除：`AgnesKeyDocument.kt`
- 测试：`AgnesKeyRepositoryJimmerTest.kt`

### 步骤

- [ ] **6.1：创建 V7__agnes_key.sql**

```sql
-- V7__agnes_key.sql
CREATE TABLE IF NOT EXISTS agnes_key (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    key VARCHAR(512) NOT NULL,
    email VARCHAR(255),
    type VARCHAR(32),
    rate_limit BIGINT NOT NULL DEFAULT -1,
    window_sec BIGINT NOT NULL DEFAULT 86400,
    models TEXT,
    unavailable_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS agnes_key_uq ON agnes_key (key);
CREATE INDEX IF NOT EXISTS agnes_key_app_idx ON agnes_key (app_id) WHERE deleted_at IS NULL;
```

- [ ] **6.2：创建 AgnesKey Entity**

```kotlin
@Entity
@Table(name = "agnes_key")
interface AgnesKey : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID
    override val appId: UUID
    val key: String
    val email: String?
    val type: String?
    val rateLimit: Long
    val windowSec: Long
    val models: String?
    val unavailableUntil: Instant?
    @LogicalDeleted("now")
    val deletedAt: Instant?
    val createdAt: Instant
    val updatedAt: Instant
}
```

- [ ] **6.3：创建 AgnesKeyRepository**

自定义方法：
- `findAvailable(appId, model): List<AgnesKey>` — 查未删 + 未冷却 + 支持指定模型的 key
- `markUnavailable(id, until: Instant)` — 设置冷却

- [ ] **6.4：更新 Agnes Key 相关 Service 引用**

- [ ] **6.5：删除旧文件**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/common/ai/AgnesKeyDocument.kt
```

- [ ] **6.6：编写 AgnesKeyRepositoryJimmerTest 并验证通过**

- [ ] **6.7：Commit**

```bash
git add -A
git commit -m "feat: migrate AgnesKey module from MongoDB to Jimmer + PostgreSQL"
```

---

## 任务 7：移除 MongoDB 依赖 + 最终清理

**优先级：** 全部模块迁移完毕后执行

**前置：** 任务 1–6 全部完成

### 步骤

- [ ] **7.1：删除 MongoDB 通用基础设施**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/common/db/BaseDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/common/db/CRUDRepository.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/common/service/CRUDService.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/common/tx/TxRunner.kt
```

检查是否还有其他引用 MongoDB 的文件：
```bash
grep -r "MongoTemplate\|mongodb\|@Document\|BaseDocument\|BaseAppDocument" \
  core-api/src/main/kotlin --include="*.kt"
```

- [ ] **7.2：从 build.gradle.kts 移除 MongoDB 依赖**

```kotlin
// 删除以下行：
implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
testImplementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo.spring3x:...")
```

- [ ] **7.3：移除 application.yml 中的 MongoDB 配置**

删除 `spring.data.mongodb.*` 相关配置。

- [ ] **7.4：删除 MongoDB 测试基础设施**

```bash
rm core-api/src/test/kotlin/com/ifmix/api/core/support/AbstractMongoTest.kt
rm core-api/src/test/kotlin/com/ifmix/api/core/common/db/CRUDRepositoryTest.kt
rm core-api/src/test/kotlin/com/ifmix/api/core/common/db/CRUDAppRepositoryTest.kt
rm core-api/src/test/kotlin/com/ifmix/api/core/common/db/TestDocument.kt
```

- [ ] **7.5：移除 Konvert 依赖（如不再使用）**

```kotlin
// 检查是否还有 @Konverter 注解使用
grep -r "Konverter\|konvert" core-api/src/ --include="*.kt"
// 如无使用，删除 build.gradle.kts 中的 konvert 依赖
```

- [ ] **7.6：清理 ownsRow / ownerCriteria 等 MongoDB 辅助函数**

检查 `common/db/` 目录下是否有仅为 MongoDB 设计的辅助函数需要删除或迁移。

- [ ] **7.7：运行全量编译和测试**

```bash
./gradlew :core-api:clean :core-api:compileKotlin
./gradlew :core-api:test
```

预期：全部通过，无 MongoDB 相关代码残留。

- [ ] **7.8：验证应用启动**

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :core-api:bootRun
```

预期：启动成功，日志无 MongoDB 连接尝试，Flyway 执行 V1–V7 全部 migration。

- [ ] **7.9：最终 Commit**

```bash
git add -A
git commit -m "chore: remove MongoDB dependency, complete Jimmer + PostgreSQL migration"
```

---

## 注意事项与风险点

### 1. ID 类型迁移：ObjectId → UUID

MongoDB 使用 24 字符 hex ObjectId，PostgreSQL 使用 UUID。

**策略：**
- 新写入的记录全部使用 UUID
- 如果需要数据迁移（从现有 MongoDB 导入），需要建立 ObjectId → UUID 的映射表
- 客户端 API 返回的 `id` 字段格式会变化（`507f1f77bcf86cd799439011` → `f47ac10b-58cc-4372-a567-0e02b2c3d479`）
- **需要与前端协调**：确认客户端是否硬编码了 ObjectId 格式校验

### 2. Auth 模块的事务边界

MongoDB 的 `TxRunner.withTx` 是手动 session-based 事务。迁移到 Jimmer 后：
- 使用 Spring `@Transactional` 注解（声明式事务）
- `AuthService` 标注 `@Transactional`，内部方法自动参与同一事务
- 需要注意：Jimmer 的 `save` command 在事务内自动获取连接，不需要手动管理

### 3. Partial Unique Index

MongoDB 的 `partialFilter` 对应 PostgreSQL 的 `WHERE` 子句 partial index：
- `{ 'deletedAt': null }` → `WHERE deleted_at IS NULL`
- `{ 'isDefault': true, 'deletedAt': null }` → `WHERE is_default = TRUE AND deleted_at IS NULL`

### 4. JSONB 列与 @Serialized

Jimmer 的 `@Serialized` 注解将 Kotlin data class 序列化为 JSON 存入 JSONB 列。
- 需要确保 Jackson 能正确序列化/反序列化这些值对象
- Spring Boot 4 使用 Jackson 3（`tools.jackson`），注意包名变化

### 5. Konvert 移除

迁移到 Jimmer DTO 后，`io.mcarle:konvert` 不再需要：
- Jimmer DTO 文件（`.dto`）由 KSP 自动生成对应的 Kotlin 类
- 这些 DTO 类自带 `toEntity()` 方法，无需手动映射
- 逐步删除 `@Konverter` 注解和 Mapper 接口

### 6. AppScopedFilter 与非 AppScoped 实体

`auth_tenant`、`auth_identity`、`auth_provider_identity`、`auth_device_secret`、`app_info` 不按 appId 分片（不实现 `AppScopedProps`）。这些实体不受 `AppScopedFilter` 影响。

仅以下实体实现 `AppScopedProps`：
- `AppConfig`, `AppUser`, `AppRefreshToken`, `ScanRecord`, `Collection`, `CollectionItem`, `Subscription`, `StoreNotification`, `AgnesKey`

### 7. 数据迁移脚本（可选，生产部署时）

如果生产环境需要从 MongoDB 导入历史数据到 PostgreSQL，需要另写迁移脚本：
- 导出 MongoDB 集合为 JSON
- 转换 ObjectId → UUID（或用 UUID v5 deterministic 映射）
- 导入 PostgreSQL

此计划不包含数据迁移脚本——仅覆盖代码迁移。数据迁移作为独立任务处理。

### 8. 构建顺序

Jimmer KSP 需要先编译 Entity 接口才能生成 Draft/Fetcher/Table。如果 Entity 之间有循环引用（如 `ScanRecord.collectionItems` ↔ `CollectionItem.scanRecord`），需要确保它们在同一个 KSP 处理轮次中。将所有 Entity 放在 `common/jimmer/entity/` 包下可以保证这一点。
