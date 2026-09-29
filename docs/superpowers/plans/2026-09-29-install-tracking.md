# Install 设备追踪 + Install↔Customer 关系 + Install Token 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为 ifmix_server 新增服务端生成的 install 设备表、install↔customer 绑定关系表、install token（type=5）签发/校验，以及 `createInstall`/`updateInstall` 两个 GraphQL mutation，并在现有 auth 流程中维护绑定关系。

**架构：** 沿用仓库既有分层（DataFetcher → Facade → AggHandler → Repository + Jimmer 实体 + Flyway 迁移）。install 不作为 actor，仅通过 JWT 的 `iid` claim 承载；token 新增正交 `type` claim（5/10/20，缺省 10）。关系表用 `@LogicalDeleted` 软删，`(install_id, customer_id)` 全局唯一，bind/unbind 复用同一行翻转 `deleted_at`。绑定/解绑/换绑判定抽为纯函数并单测。

**技术栈：** Kotlin 2.3.10 / Spring Boot 4.1 / Jimmer 0.11.5 (KSP) / DGS GraphQL / Flyway / PostgreSQL / EdDSA(Ed25519) JWT (nimbus-jose-jwt) / JUnit5 + assertk。

**设计依据：** `docs/design/install-tracking.md`（本计划实现其 §8 落地清单）。

---

## 文件结构

**新增：**
- `core-api/src/main/resources/db/migration/V5__install_tracking.sql` — 两张表 + 索引
- `core-api/src/main/kotlin/com/ifmix/core/api/entity/install/Install.kt` — 设备实体
- `core-api/src/main/kotlin/com/ifmix/core/api/entity/install/InstallCustomerRelation.kt` — 关系实体
- `core-api/src/main/kotlin/com/ifmix/core/api/modules/install/repo/InstallRepository.kt`
- `core-api/src/main/kotlin/com/ifmix/core/api/modules/install/repo/InstallCustomerRelationRepository.kt`
- `core-api/src/main/kotlin/com/ifmix/core/api/modules/install/handler/InstallAggHandler.kt` — 含纯函数 `decideBindAction`
- `core-api/src/main/kotlin/com/ifmix/core/api/modules/install/InstallFacade.kt`
- `core-api/src/main/kotlin/com/ifmix/core/api/bff/graphql/customer/install/InstallFetcher.kt`
- `core-api/src/main/resources/schema/customer/install.graphqls`
- `core-api/src/test/kotlin/com/ifmix/api/core/modules/install/BindActionDecisionTest.kt` — 纯函数单测
- `core-api/src/test/kotlin/com/ifmix/api/core/common/auth/InstallTokenTest.kt` — JWT type/iid 单测

**修改：**
- `core-api/.../infra/auth/AuthJwtService.kt` — 新增 `type` claim、`signInstall`、`iid`；`VerifiedToken` 加 `tokenType`+`installId`
- `core-api/.../infra/http/ActionContext.kt` — 加 `tokenInstallId: UUID?`
- `core-api/.../infra/auth/RequestParser.kt` — 从 token 取 iid（新方法）
- `core-api/.../infra/graphql/ActionContextProvider.kt` — 组装 `tokenInstallId`
- `core-api/.../modules/auth/handler/AuthAggHandler.kt` — createAnonymousCustomer/login/logout/requestAccountDeletion 挂关系维护 + 写 iid
- `core-api/.../entity/common/ActorType.kt` — 无需改（不加 act=5）

**分组说明：** entity/repo/handler/facade/fetcher 按 install 模块聚在一起。JWT 与 token 解析属 infra/auth 横切，单独任务。关系维护改动集中在 AuthAggHandler。

---

## 任务顺序总览

1. 迁移 + 两个实体（可编译的地基）
2. JWT `type`/`iid` claim + install token（纯逻辑，先单测）
3. ActionContext/RequestParser/Provider 取 token iid
4. 两个 Repository
5. 绑定判定纯函数 + 单测
6. InstallAggHandler（createInstall/updateInstall/关系维护）
7. Facade + Fetcher + schema（createInstall/updateInstall 打通）
8. AuthAggHandler 挂关系维护（create/login/logout/deleteAccount）
9. 全量编译 + 测试 + 文档同步

---

### 任务 1：Flyway 迁移 + Jimmer 实体

**文件：**
- 创建：`core-api/src/main/resources/db/migration/V5__install_tracking.sql`
- 创建：`core-api/src/main/kotlin/com/ifmix/core/api/entity/install/Install.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/core/api/entity/install/InstallCustomerRelation.kt`

- [ ] **步骤 1：编写迁移 SQL**

创建 `V5__install_tracking.sql`：

```sql
-- V5: install 设备表 + install↔customer 关系表。
-- install-id 由服务端生成（UuidV7）；关系表软删语义（deleted_at）+ (install_id, customer_id) 全局唯一。

CREATE TABLE IF NOT EXISTS public.core_install (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    install_id uuid NOT NULL,
    platform integer,
    device_info jsonb,
    app_version text,
    ota_version text,
    locale text,
    country text,
    currency text,
    reg_ip text,
    firebase_install_id text,
    fcm_token text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS core_install_project_install_uq
    ON public.core_install USING btree (project_id, install_id);

CREATE TABLE IF NOT EXISTS public.core_install_customer_relation (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    install_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone
);

-- 一对关系永远只有一行（复用行翻转 deleted_at）：全局唯一，不带 deleted_at 条件。
CREATE UNIQUE INDEX IF NOT EXISTS core_install_customer_rel_uq
    ON public.core_install_customer_relation USING btree (install_id, customer_id);

CREATE INDEX IF NOT EXISTS core_install_customer_rel_install_idx
    ON public.core_install_customer_relation USING btree (project_id, install_id);
```

- [ ] **步骤 2：编写 Install 实体**

创建 `entity/install/Install.kt`（参照 `entity/customer/Customer.kt` 风格）：

```kotlin
package com.ifmix.core.api.entity.install

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * install 设备表：服务端生成 install_id（UuidV7），独立于 customer 存在。
 * platform/app_version/... 来自请求 header；device_info 为自由结构 JSONB。
 */
@Entity
@Table(name = "core_install")
interface Install : BaseProjectEntity {

    /** 服务端生成的 installId（UuidV7）。(project_id, install_id) 唯一。 */
    @Column(name = "install_id")
    val installId: UUID

    /** 平台 Int 码：10=ANDROID / 20=IOS / 30=WEB。来自 x-client-platform。 */
    val platform: Int?

    /** 设备信息（自由结构 JSONB）。 */
    @Serialized
    @Column(name = "device_info")
    val deviceInfo: Map<String, Any?>?

    @Column(name = "app_version")
    val appVersion: String?

    @Column(name = "ota_version")
    val otaVersion: String?

    val locale: String?
    val country: String?
    val currency: String?

    /** 注册时 IP（createInstall 写入，updateInstall 不改）。 */
    @Column(name = "reg_ip")
    val regIp: String?

    @Column(name = "firebase_install_id")
    val firebaseInstallId: String?

    @Column(name = "fcm_token")
    val fcmToken: String?
}
```

- [ ] **步骤 3：编写 InstallCustomerRelation 实体**

创建 `entity/install/InstallCustomerRelation.kt`（参照 `entity/auth/AuthIdentityIdpRelation.kt`）：

```kotlin
package com.ifmix.core.api.entity.install

import com.ifmix.core.api.entity.common.BaseProjectEntity
import com.ifmix.core.api.entity.common.SoftDeletableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * install ↔ customer 绑定关系。(install_id, customer_id) 全局唯一，一对一行。
 * bind/unbind 复用同一行翻转 deleted_at：null=当前绑定，not null=已解绑。
 * 一个 install 同时只绑一个 customer（业务保证：绑新的前软删该 install 其它有效关系）。
 */
@Entity
@Table(name = "core_install_customer_relation")
interface InstallCustomerRelation : BaseProjectEntity, SoftDeletableProps {

    @Column(name = "install_id")
    val installId: UUID

    @Column(name = "customer_id")
    val customerId: UUID
}
```

- [ ] **步骤 4：编译验证（KSP 生成实体 draft）**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL。KSP 为两个实体生成 draft/props（`Install{}`、`InstallCustomerRelation{}` DSL 及 `installId`/`customerId`/`deletedAt` 等属性扩展）。若 `@Serialized Map` 报错，改为 `@Column(name="device_info") val deviceInfo: com.fasterxml.jackson.databind.JsonNode?`（对齐仓库 JSONB 现有写法，见 ScanRecord.basicResult）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/resources/db/migration/V5__install_tracking.sql \
        core-api/src/main/kotlin/com/ifmix/core/api/entity/install/
git commit -m "feat(install): add core_install + relation tables and Jimmer entities"
```

---

### 任务 2：JWT `type`/`iid` claim + install token 签发/校验

**文件：**
- 修改：`core-api/.../infra/auth/AuthJwtService.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/auth/InstallTokenTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `InstallTokenTest.kt`（参照现有测试用 assertk；`AuthJwtKeys(null)` 生成临时密钥）：

```kotlin
package com.ifmix.api.core.common.auth

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.infra.auth.AuthJwtKeys
import com.ifmix.core.api.infra.auth.AuthJwtService
import org.junit.jupiter.api.Test
import java.util.UUID

class InstallTokenTest {

    private val svc = AuthJwtService(AuthJwtKeys(null), issuer = "test-issuer")

    @Test
    fun `install token has type=5, iid, and no subject`() {
        val installId = UUID.randomUUID()
        val token = svc.signInstall(installId.toString(), projectId = "proj-a")
        val v = svc.verify(token)!!
        assertThat(v.tokenType).isEqualTo(5)
        assertThat(v.installId).isEqualTo(installId.toString())
        assertThat(v.actorId).isNull() // install token 不设 sub
    }

    @Test
    fun `customer token carries type=10 and iid`() {
        val customerId = UUID.randomUUID()
        val installId = UUID.randomUUID()
        val token = svc.signAccess(
            customerId.toString(), AuthJwtService.ACTOR_CUSTOMER, "proj-a",
            sessionId = "sid-1", anonymous = true, installId = installId.toString(),
        )
        val v = svc.verify(token)!!
        assertThat(v.tokenType).isEqualTo(10)
        assertThat(v.actorId).isEqualTo(customerId.toString())
        assertThat(v.installId).isEqualTo(installId.toString())
    }

    @Test
    fun `legacy token without type defaults to 10`() {
        // signAccess 不传 installId 时不写 iid；type 仍写 10（新签发一律带 type）。
        val token = svc.signAccess(
            UUID.randomUUID().toString(), AuthJwtService.ACTOR_CUSTOMER, "proj-a",
            sessionId = "sid-1", anonymous = false,
        )
        val v = svc.verify(token)!!
        assertThat(v.tokenType).isEqualTo(10)
        assertThat(v.installId).isNull()
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.auth.InstallTokenTest"`
预期：编译失败/FAIL —— `signInstall` 未定义、`signAccess` 无 `installId` 参数、`VerifiedToken` 无 `tokenType`/`installId`。

- [ ] **步骤 3：修改 AuthJwtService**

在 `AuthJwtService.kt`：

3a. 给 `signAccess` 加可空 `installId` 参数 + 写 `type=10` 与 `iid`：

```kotlin
    fun signAccess(
        actorId: String,
        actorType: ActorType,
        projectId: String,
        sessionId: String,
        anonymous: Boolean = false,
        installId: String? = null,
    ): String {
        val now = Date()
        val builder = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(actorId)
            .audience(listOf(projectId))
            .jwtID(UuidV7.generate().toString())
            .issueTime(now)
            .expirationTime(Date(now.time + accessTtlSec * 1000))
            .claim("act", actorType)
            .claim("ano", anonymous)
            .claim("sid", sessionId)
            .claim("type", TOKEN_TYPE_CUSTOMER)
        if (installId != null) builder.claim("iid", installId)
        val claims = builder.build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        return jwt.serialize()
    }
```

3b. 新增 `signInstall`（无 sub、无过期）：

```kotlin
    /** install token：type=5, iid=installId, aud=projectId, 无 sub, 永不过期。 */
    fun signInstall(installId: String, projectId: String): String {
        val now = Date()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(listOf(projectId))
            .jwtID(UuidV7.generate().toString())
            .issueTime(now)
            .claim("type", TOKEN_TYPE_INSTALL)
            .claim("iid", installId)
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(keys.kid).type(JOSEObjectType.JWT).build()
        val jwt = SignedJWT(header, claims)
        jwt.sign(Ed25519Signer(keys.signingKey))
        return jwt.serialize()
    }
```

3c. `companion object` 加常量：

```kotlin
        const val TOKEN_TYPE_INSTALL = 5
        const val TOKEN_TYPE_CUSTOMER = 10
        const val TOKEN_TYPE_MANAGER = 20
```

3d. `verify` 里读 type（缺省 10）+ iid，写进 `VerifiedToken`。注意 install token 无 sub，`subject` 为 null 时不再当作错误（`verify` 本身不校验 sub，sub 缺失的语义校验在 `parseActor`）：

```kotlin
        return VerifiedToken(
            actorId = claims.subject,
            projectId = claims.audience?.firstOrNull(),
            actorType = claims.getIntegerClaim("act") ?: ACTOR_CUSTOMER,
            anonymous = runCatching { claims.getBooleanClaim("ano") }.getOrNull() ?: false,
            sessionId = runCatching { claims.getStringClaim("sid") }.getOrNull(),
            tokenType = claims.getIntegerClaim("type") ?: TOKEN_TYPE_CUSTOMER,
            installId = runCatching { claims.getStringClaim("iid") }.getOrNull(),
        )
```

3e. `VerifiedToken` data class 加字段：

```kotlin
data class VerifiedToken(
    val actorId: String?,
    val projectId: String?,
    val actorType: ActorType,
    val anonymous: Boolean = false,
    val sessionId: String? = null,
    val tokenType: Int = AuthJwtService.TOKEN_TYPE_CUSTOMER,
    val installId: String? = null,
) {
    val isCustomer get() = actorType == AuthJwtService.ACTOR_CUSTOMER
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.common.auth.InstallTokenTest"`
预期：PASS（3 个测试）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/core/api/infra/auth/AuthJwtService.kt \
        core-api/src/test/kotlin/com/ifmix/api/core/common/auth/InstallTokenTest.kt
git commit -m "feat(auth): add token type + iid claim and signInstall"
```

---

### 任务 3：ActionContext + RequestParser + Provider 取 token iid

**文件：**
- 修改：`core-api/.../infra/http/ActionContext.kt`
- 修改：`core-api/.../infra/auth/RequestParser.kt`
- 修改：`core-api/.../infra/graphql/ActionContextProvider.kt`

- [ ] **步骤 1：ActionContext 加 tokenInstallId**

在 `ActionContext.kt` 现有 `installId: String?`（header 版，保留）之后加：

```kotlin
    /** token 的 iid claim（可信 installId）。install token 或 customer token 携带；无则 null。 */
    val tokenInstallId: UUID? = null,
```

- [ ] **步骤 2：RequestParser 缓存 token installId**

`RequestParser.parseActor` 目前对 install token（无 sub）会抛 "missing subject"。需让 install token 也能被解析出 iid，但**不经过 parseActor**（install token 无 actor）。新增独立方法，从已验签 token 取 iid：

```kotlin
    /** 从 Authorization token 取可信 installId（iid claim）。无 token / 无 iid → null。不抛（软取）。 */
    fun parseTokenInstallId(request: HttpServletRequest): UUID? {
        (request.getAttribute(ATTR_TOKEN_IID) as? UUID)?.let { return it }
        val auth = request.getHeader("Authorization")
        if (auth.isNullOrBlank() || !auth.startsWith("Bearer ")) return null
        val raw = auth.removePrefix("Bearer ").trim()
        if (raw.isBlank()) return null
        val verified = try { jwt.verify(raw) } catch (_: TokenExpiredException) { return null } ?: return null
        val iid = verified.installId?.let { tryUuid(it) } ?: return null
        request.setAttribute(ATTR_TOKEN_IID, iid)
        return iid
    }
```

在 `companion object` 加：

```kotlin
        private const val ATTR_TOKEN_IID = "com.ifmix.parsed.tokenInstallId"
```

- [ ] **步骤 3：ActionContextProvider 组装 tokenInstallId**

在 `ActionContextProvider.fromDfe`（构造 ActionContext 处）加 `tokenInstallId = requestParser.parseTokenInstallId(request)`。

> 读 `infra/graphql/ActionContextProvider.kt` 确认现有构造 ActionContext 的字段赋值位置，在同处补一行。若该文件通过 `RequestParser` 逐字段解析后 new ActionContext，就在其中加入 `tokenInstallId`。

- [ ] **步骤 4：编译验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/core/api/infra/http/ActionContext.kt \
        core-api/src/main/kotlin/com/ifmix/core/api/infra/auth/RequestParser.kt \
        core-api/src/main/kotlin/com/ifmix/core/api/infra/graphql/ActionContextProvider.kt
git commit -m "feat(auth): expose token iid as ActionContext.tokenInstallId"
```

---

### 任务 4：两个 Repository

**文件：**
- 创建：`core-api/.../modules/install/repo/InstallRepository.kt`
- 创建：`core-api/.../modules/install/repo/InstallCustomerRelationRepository.kt`

- [ ] **步骤 1：InstallRepository**

参照 `modules/customer/repo/CustomerRepository.kt` + `ProjectCrudRepoTemplate`：

```kotlin
package com.ifmix.core.api.modules.install.repo

import com.ifmix.core.api.entity.install.Install
import com.ifmix.core.api.entity.install.installId
import com.ifmix.core.api.entity.install.projectId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class InstallRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(Install::class, UUID::class) }

    fun save(mc: ModuleCtx, entity: Install): Boolean = tpl.save(mc, entity)

    /** 按 (projectId, installId) 查设备（installId 是业务唯一键，非主键）。 */
    fun findByInstallId(mc: ModuleCtx, projectId: String, installId: UUID): Install? =
        mc.sql.createQuery(Install::class) {
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            select(table)
        }.limit(1).execute().firstOrNull()
}
```

- [ ] **步骤 2：InstallCustomerRelationRepository**

参照 `modules/auth/repo/AuthIdentityIdpRelationRepository.kt`。关键：查含软删行时 `disable(LogicalDeletedFilter)`（见设计 §7 坑 1）；换绑软删用 update：

```kotlin
package com.ifmix.core.api.modules.install.repo

import com.ifmix.core.api.entity.install.InstallCustomerRelation
import com.ifmix.core.api.entity.install.installId
import com.ifmix.core.api.entity.install.customerId
import com.ifmix.core.api.entity.install.projectId
import com.ifmix.core.api.entity.install.deletedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.filter.LogicalDeletedBehavior
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.ne
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class InstallCustomerRelationRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(InstallCustomerRelation::class, UUID::class) }

    fun save(mc: ModuleCtx, entity: InstallCustomerRelation): Boolean = tpl.save(mc, entity)

    /**
     * 按 (installId, customerId) 查关系——**含软删行**（用于 upsert 决定 插/复活）。
     * 必须绕过 @LogicalDeleted 默认过滤，否则漏掉已软删行 → 误判不存在 → 插新行撞唯一约束。
     */
    fun findAnyByPair(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID): InstallCustomerRelation? =
        mc.sql.createQuery(InstallCustomerRelation::class) {
            filters { disable(LogicalDeletedBehavior::class) } // ponytail: API 名以编译为准，见步骤3
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            where(table.customerId eq customerId)
            select(table)
        }.limit(1).execute().firstOrNull()

    /** 软删该 install 当前其它 customer 的有效关系（换绑：一 install 只绑一 customer）。返回受影响行数。 */
    fun softDeleteOtherActiveByInstall(mc: ModuleCtx, projectId: String, installId: UUID, keepCustomerId: UUID): Int =
        mc.sql.createUpdate(InstallCustomerRelation::class) {
            set(table.deletedAt, Instant.now())
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            where(table.customerId ne keepCustomerId)
            where(table.deletedAt.isNull())
        }.execute()

    /** 软删指定有效关系（logout 解绑）。 */
    fun softDeleteActive(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID): Int =
        mc.sql.createUpdate(InstallCustomerRelation::class) {
            set(table.deletedAt, Instant.now())
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            where(table.customerId eq customerId)
            where(table.deletedAt.isNull())
        }.execute()

    /** 软删该 customer 的全部有效关系（deleteAccount）。 */
    fun softDeleteAllActiveByCustomer(mc: ModuleCtx, projectId: String, customerId: UUID): Int =
        mc.sql.createUpdate(InstallCustomerRelation::class) {
            set(table.deletedAt, Instant.now())
            where(table.projectId eq projectId)
            where(table.customerId eq customerId)
            where(table.deletedAt.isNull())
        }.execute()

    /** 复活软删行（re-bind）：deleted_at 置回 null。 */
    fun reactivate(mc: ModuleCtx, id: UUID): Int =
        mc.sql.createUpdate(InstallCustomerRelation::class) {
            set(table.deletedAt, null)
            where(table.get<UUID>("id") eq id)
        }.execute()
}
```

- [ ] **步骤 3：编译验证 + 修正 Jimmer API 名**

运行：`./gradlew :core-api:compileKotlin`
预期：可能因 `filters { disable(...) }` / `LogicalDeletedBehavior` API 名不符而 FAIL。Jimmer 0.11.x 关闭逻辑删除过滤的正确写法二选一（按报错修正）：
- 查询内：`filters { disable(LogicalDeletedFilter::class) }`（import `org.babyfish.jimmer.sql.filter.LogicalDeletedFilter`）
- 或用 `mc.sql.filters { ... }.createQuery(...)` 派生。
若 `set(table.deletedAt, null)` 因 `@LogicalDeleted` 字段不可直接 set 而报错，改用 `createUpdate` 的原生表达式或 JdbcClient（见设计 §7 坑 2）。修正后重新编译至 SUCCESSFUL。

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/core/api/modules/install/repo/
git commit -m "feat(install): add Install and relation repositories"
```

---

### 任务 5：绑定判定纯函数 + 单测

**文件：**
- 创建（先放 handler 里的 companion）：`core-api/.../modules/install/handler/InstallAggHandler.kt`（仅纯函数骨架）
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/modules/install/BindActionDecisionTest.kt`

- [ ] **步骤 1：编写失败的测试**

创建 `BindActionDecisionTest.kt`（参照 `LoginActionDecisionTest.kt`）：

```kotlin
package com.ifmix.api.core.modules.install

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.ifmix.core.api.modules.install.handler.InstallAggHandler.Companion.BindAction
import com.ifmix.core.api.modules.install.handler.InstallAggHandler.Companion.decideBindAction
import org.junit.jupiter.api.Test
import java.util.UUID

class BindActionDecisionTest {
    private val id = UUID.randomUUID()

    @Test
    fun `no existing row - insert`() {
        assertThat(decideBindAction(existingId = null, existingDeleted = false))
            .isEqualTo(BindAction.Insert)
    }

    @Test
    fun `existing active row - noop`() {
        assertThat(decideBindAction(existingId = id, existingDeleted = false))
            .isInstanceOf(BindAction.NoOp::class)
    }

    @Test
    fun `existing soft-deleted row - reactivate`() {
        val action = decideBindAction(existingId = id, existingDeleted = true)
        assertThat(action).isInstanceOf(BindAction.Reactivate::class)
        assertThat((action as BindAction.Reactivate).id).isEqualTo(id)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.modules.install.BindActionDecisionTest"`
预期：编译失败 —— `InstallAggHandler`/`BindAction`/`decideBindAction` 未定义。

- [ ] **步骤 3：编写纯函数（handler 骨架 + companion）**

创建 `InstallAggHandler.kt`（先只放 companion 纯逻辑，实例方法下一任务补）：

```kotlin
package com.ifmix.core.api.modules.install.handler

import org.springframework.stereotype.Component
import java.util.UUID

@Component
class InstallAggHandler {

    companion object {
        sealed interface BindAction {
            /** 无任何行 → 插新行。 */
            data object Insert : BindAction
            /** 已有有效行 → 幂等不动。 */
            data object NoOp : BindAction
            /** 有软删行 → 复活。 */
            data class Reactivate(val id: UUID) : BindAction
        }

        /**
         * 绑定判定（纯函数）：按 (install, customer) 的既有行状态决定 插/复活/不动。
         * @param existingId 既有行 id（含软删查得），null=无行
         * @param existingDeleted 既有行是否已软删
         */
        fun decideBindAction(existingId: UUID?, existingDeleted: Boolean): BindAction = when {
            existingId == null -> BindAction.Insert
            existingDeleted -> BindAction.Reactivate(existingId)
            else -> BindAction.NoOp
        }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.modules.install.BindActionDecisionTest"`
预期：PASS（3 个测试）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/core/api/modules/install/handler/InstallAggHandler.kt \
        core-api/src/test/kotlin/com/ifmix/api/core/modules/install/BindActionDecisionTest.kt
git commit -m "feat(install): add pure decideBindAction + tests"
```

---

### 任务 6：InstallAggHandler 业务方法（createInstall / updateInstall / 关系维护）

**文件：**
- 修改：`core-api/.../modules/install/handler/InstallAggHandler.kt`

- [ ] **步骤 1：注入依赖 + createInstall**

给 `InstallAggHandler` 加构造注入（repo + jwt），并加 createInstall。参照 `AuthAggHandler.createAnonymousCustomer` 的 UuidV7/Instant 风格：

```kotlin
@Component
class InstallAggHandler(
    private val installRepo: InstallRepository,
    private val relationRepo: InstallCustomerRelationRepository,
    private val jwt: AuthJwtService,
) {
    data class CreateInstallRes(val installId: UUID, val installToken: String)

    /** 生成 installId + 写 core_install + 签发 installToken(type=5)。header 字段从 mc.action 取。 */
    fun createInstall(mc: ModuleCtx, deviceInfo: Map<String, Any?>?): CreateInstallRes {
        val projectId = mc.projectId!!
        val installId = UuidV7.generate()
        val now = Instant.now()
        installRepo.save(mc, Install {
            this.id = UuidV7.generate()
            this.projectId = projectId
            this.installId = installId
            this.platform = platformInt(mc.action.clientPlatform)
            this.deviceInfo = deviceInfo
            this.appVersion = mc.action.appVersion
            this.otaVersion = mc.action.otaVersion
            this.locale = mc.action.locale
            this.country = mc.action.country
            this.currency = mc.action.currency
            this.regIp = mc.action.clientIp
            this.firebaseInstallId = null
            this.fcmToken = null
            this.createdAt = now
            this.updatedAt = now
        })
        val token = jwt.signInstall(installId.toString(), projectId)
        return CreateInstallRes(installId = installId, installToken = token)
    }

    private fun platformInt(p: ClientPlatform?): Int? = when (p) {
        ClientPlatform.ANDROID -> 10
        ClientPlatform.IOS -> 20
        ClientPlatform.WEB -> 30
        null -> null
    }
```

> import：`com.ifmix.core.api.entity.install.Install`、其属性扩展、`com.ifmix.core.api.infra.db.UuidV7`、`AuthJwtService`、`ClientPlatform`、`Instant`、install 属性 setter（Jimmer draft）。

- [ ] **步骤 2：updateInstall（仅更新非空字段）**

```kotlin
    /** 按 (projectId, installId) 更新，仅覆盖非空字段。installId 来自 token iid（调用方传入）。 */
    fun updateInstall(
        mc: ModuleCtx, installId: UUID,
        firebaseInstallId: String?, fcmToken: String?, deviceInfo: Map<String, Any?>?,
    ): Boolean {
        val projectId = mc.projectId!!
        val existing = installRepo.findByInstallId(mc, projectId, installId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "install not found")
        installRepo.save(mc, Install {
            this.id = existing.id
            this.projectId = projectId
            this.installId = installId
            // 仅覆盖非空：null 时沿用旧值
            this.platform = platformInt(mc.action.clientPlatform) ?: existing.platform
            this.deviceInfo = deviceInfo ?: existing.deviceInfo
            this.appVersion = mc.action.appVersion ?: existing.appVersion
            this.otaVersion = mc.action.otaVersion ?: existing.otaVersion
            this.locale = mc.action.locale ?: existing.locale
            this.country = mc.action.country ?: existing.country
            this.currency = mc.action.currency ?: existing.currency
            this.regIp = existing.regIp // write-once：updateInstall 不改，保留注册时 IP
            this.firebaseInstallId = firebaseInstallId ?: existing.firebaseInstallId
            this.fcmToken = fcmToken ?: existing.fcmToken
            this.createdAt = existing.createdAt
            this.updatedAt = Instant.now()
        })
        return true
    }
```

- [ ] **步骤 3：关系维护（bind / unbind / unbindAllForCustomer）供 AuthAggHandler 调用**

```kotlin
    /** 绑定 install↔customer：换绑（软删该 install 其它有效关系）+ upsert（插/复活/不动）。幂等。 */
    fun bind(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID) {
        relationRepo.softDeleteOtherActiveByInstall(mc, projectId, installId, keepCustomerId = customerId)
        val existing = relationRepo.findAnyByPair(mc, projectId, installId, customerId)
        when (val action = decideBindAction(existing?.id, existing?.deletedAt != null)) {
            BindAction.Insert -> relationRepo.save(mc, InstallCustomerRelation {
                this.id = UuidV7.generate()
                this.projectId = projectId
                this.installId = installId
                this.customerId = customerId
                this.createdAt = Instant.now()
                this.updatedAt = Instant.now()
                this.deletedAt = null
            })
            is BindAction.Reactivate -> relationRepo.reactivate(mc, action.id)
            BindAction.NoOp -> Unit
        }
    }

    /** 解绑（logout）：软删有效关系。 */
    fun unbind(mc: ModuleCtx, projectId: String, installId: UUID, customerId: UUID) {
        relationRepo.softDeleteActive(mc, projectId, installId, customerId)
    }

    /** 删除账号：软删该 customer 全部有效关系。 */
    fun unbindAllForCustomer(mc: ModuleCtx, projectId: String, customerId: UUID) {
        relationRepo.softDeleteAllActiveByCustomer(mc, projectId, customerId)
    }
```

- [ ] **步骤 4：编译验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL。若 Jimmer draft 对 `deletedAt` setter 报错（`@LogicalDeleted` 字段），bind 的 Insert 分支去掉 `this.deletedAt = null`（新建默认即 null）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/core/api/modules/install/handler/InstallAggHandler.kt
git commit -m "feat(install): createInstall/updateInstall + bind/unbind relation logic"
```

---

### 任务 7：Facade + Fetcher + schema（打通 createInstall/updateInstall）

**文件：**
- 创建：`core-api/.../modules/install/InstallFacade.kt`
- 创建：`core-api/.../bff/graphql/customer/install/InstallFetcher.kt`
- 创建：`core-api/src/main/resources/schema/customer/install.graphqls`

- [ ] **步骤 1：schema**

创建 `install.graphqls`（逐字对齐设计 §7.5 与前端文档 §1）：

```graphql
type CreateInstallResult {
    installId: UUID!
    installToken: String!
}
type UpdateInstallResult {
    success: Boolean!
}
input CreateInstallInput {
    deviceInfo: JSON
}
input UpdateInstallInput {
    firebaseInstallId: String
    fcmToken: String
    deviceInfo: JSON
}
extend type Mutation {
    m_install_createInstall(input: CreateInstallInput): CreateInstallResult!
    m_install_updateInstall(input: UpdateInstallInput!): UpdateInstallResult!
}
```

- [ ] **步骤 2：Facade**

```kotlin
package com.ifmix.core.api.modules.install

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.install.handler.InstallAggHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class InstallFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: InstallAggHandler,
) {
    fun createInstall(ctx: ActionContext, deviceInfo: Map<String, Any?>?) =
        handler.createInstall(mcFactory.forProject(ctx), deviceInfo)

    fun updateInstall(ctx: ActionContext, installId: UUID, fid: String?, fcmToken: String?, deviceInfo: Map<String, Any?>?) =
        handler.updateInstall(mcFactory.forProject(ctx), installId, fid, fcmToken, deviceInfo)
}
```

- [ ] **步骤 3：Fetcher**

参照 `CustomerFetcher`（限流）+ `AuthFetcher`（globalTx）。createInstall 无鉴权 + 限流；updateInstall 取 token iid：

```kotlin
package com.ifmix.core.api.bff.graphql.customer.install

import com.ifmix.core.api.generated.types.CreateInstallResult
import com.ifmix.core.api.generated.types.UpdateInstallResult
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.install.InstallFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument

@DgsComponent
class InstallFetcher(
    private val installFacade: InstallFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: ActionContextProvider,
    private val rateLimiter: RateLimiter,
) {
    @DgsMutation(field = "m_install_createInstall")
    fun createInstall(dfe: DgsDataFetchingEnvironment, @InputArgument input: Map<String, Any?>?): CreateInstallResult {
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null) // 无鉴权
        val clientIp = ctx.clientIp ?: "unknown"
        if (!rateLimiter.checkFixedWindow("install:$clientIp", RATE_LIMIT, RATE_WINDOW_SEC)) {
            throw ApiError(ErrorCode.RATE_LIMITED, "too many createInstall")
        }
        @Suppress("UNCHECKED_CAST")
        val deviceInfo = input?.get("deviceInfo") as? Map<String, Any?>
        val res = globalTx.withTx(ctx) { txCtx -> installFacade.createInstall(txCtx, deviceInfo) }
        return CreateInstallResult(installId = res.installId, installToken = res.installToken)
    }

    @DgsMutation(field = "m_install_updateInstall")
    fun updateInstall(dfe: DgsDataFetchingEnvironment, @InputArgument input: Map<String, Any?>): UpdateInstallResult {
        val ctx = ctxProvider.fromDfe(dfe, requireActorType = null)
        val installId = ctx.tokenInstallId
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "install token required")
        @Suppress("UNCHECKED_CAST")
        val deviceInfo = input["deviceInfo"] as? Map<String, Any?>
        val ok = globalTx.withTx(ctx) { txCtx ->
            installFacade.updateInstall(
                txCtx, installId,
                input["firebaseInstallId"] as? String,
                input["fcmToken"] as? String,
                deviceInfo,
            )
        }
        return UpdateInstallResult(success = ok)
    }

    companion object {
        private const val RATE_LIMIT = 10
        private const val RATE_WINDOW_SEC = 60L
    }
}
```

> 若 DGS codegen 未生成 `CreateInstallResult`/`UpdateInstallResult` 类型，先跑一次 `./gradlew :core-api:compileKotlin` 触发 codegen（DGS codegen 随 build 生成到 `generated/types`）。`@InputArgument input: Map` 因 `JSON` 标量嵌套用 Map 承接（对齐现有对 JSON 输入的处理；若仓库对 input 有生成类型则改用生成类型）。

- [ ] **步骤 4：编译验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL（DGS 生成新类型 + Fetcher 编译通过）。

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/core/api/modules/install/InstallFacade.kt \
        core-api/src/main/kotlin/com/ifmix/core/api/bff/graphql/customer/install/InstallFetcher.kt \
        core-api/src/main/resources/schema/customer/install.graphqls
git commit -m "feat(install): createInstall/updateInstall GraphQL mutations"
```

---

### 任务 8：AuthAggHandler 挂关系维护

**文件：**
- 修改：`core-api/.../modules/auth/handler/AuthAggHandler.kt`

> 依赖：AuthAggHandler 注入 `InstallAggHandler`（跨模块允许注入其他模块 Facade/Handler——见 AGENTS.md「Handler 可注入其他模块 Facade」。此处注入同为 handler 的 InstallAggHandler；若违反约定则改注入 `InstallFacade`）。优先注入 `InstallFacade` 以合规，需给 InstallFacade 加透传 bind/unbind/unbindAllForCustomer 方法。

- [ ] **步骤 1：InstallFacade 补关系维护透传方法**

在 `InstallFacade` 加：

```kotlin
    fun bind(ctx: ActionContext, installId: UUID, customerId: UUID) =
        handler.bind(mcFactory.forProject(ctx), ctx.mustGetProjectId(), installId, customerId)

    fun unbind(ctx: ActionContext, installId: UUID, customerId: UUID) =
        handler.unbind(mcFactory.forProject(ctx), ctx.mustGetProjectId(), installId, customerId)

    fun unbindAllForCustomer(ctx: ActionContext, customerId: UUID) =
        handler.unbindAllForCustomer(mcFactory.forProject(ctx), ctx.mustGetProjectId(), customerId)
```

> 注意：这些在同一 GlobalTx 内被 AuthAggHandler 调用时，`mcFactory.forProject(ctx)` 会复用全局事务 sql（见 ActionContext.globalTxSql 语义）。确认 mcFactory 复用事务，避免另开连接。

- [ ] **步骤 2：AuthAggHandler 注入 InstallFacade**

构造函数加 `private val installFacade: InstallFacade,`。

- [ ] **步骤 3：createAnonymousCustomer 写 iid + 过渡绑定**

修改 `createAnonymousCustomer`（第 291-324 行）：signAccess 传 iid（若有），并在有 iid 时绑定：

```kotlin
        val tokenIid = mc.action.tokenInstallId // installToken 的 iid（过渡：可空）
        val accessToken = jwt.signAccess(
            customerId.toString(), AuthJwtService.ACTOR_CUSTOMER, projectId.toString(),
            sessionId = refreshTokenId.toString(), anonymous = true,
            installId = tokenIid?.toString(),
        )
        if (tokenIid != null) {
            installFacade.bind(mc.action, tokenIid, customerId)
        }
```

> 注意：`installFacade.bind(mc.action, ...)` 传的是 ActionContext；bind 内部 `mcFactory.forProject` 复用全局事务。

- [ ] **步骤 4：login 写 iid + 绑定**

修改 `login`（第 139-237 行）签发 access token 处，传 iid 并绑定到 ownerId：

```kotlin
        val tokenIid = mc.action.tokenInstallId
        val accessToken = jwt.signAccess(
            ownerId.toString(), AuthJwtService.ACTOR_CUSTOMER, projectId.toString(),
            sessionId = refreshTokenId.toString(), anonymous = false,
            installId = tokenIid?.toString(),
        )
        if (tokenIid != null) {
            installFacade.bind(mc.action, tokenIid, ownerId)
        }
```

> Merge 分支：ownerId 已是合并后的 existing（`action.to`），bind 到 ownerId 即「指向合并后 existing，绝不反向」（设计 §5.3）。无需额外处理。

- [ ] **步骤 5：logout 解绑（缺 iid 报错）**

修改 `logout`（第 277-285 行）：

```kotlin
    fun logout(mc: ModuleCtx, req: LogoutReq): LogoutRes {
        val projectId = mc.projectId!!
        val tokenHash = Hashing.sha256Base64Url(req.refreshToken)
        val token = refreshTokenRepo.findValidByHash(mc, projectId, tokenHash)
        if (token != null) {
            refreshTokenRepo.revoke(mc, token.id)
        }
        // 关系解绑：logout 必须有 iid（设计 D9，不兼容老 token）
        val iid = mc.action.tokenInstallId
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "install id (iid) required for logout")
        val customerId = mc.action.actorId
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
        installFacade.unbind(mc.action, iid, customerId)
        return LogoutRes(ok = true)
    }
```

- [ ] **步骤 6：requestAccountDeletion 软删全部关系**

修改 `requestAccountDeletion`（第 326-330 行）：

```kotlin
    fun requestAccountDeletion(mc: ModuleCtx): DeleteAccountRes {
        val actorId = mc.action.actorId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        // 请求删除当下即软删该 customer 全部有效关系（设计 D9b；不依赖 iid）
        installFacade.unbindAllForCustomer(mc.action, actorId)
        val scheduledAt = Instant.now().plusSeconds(30L * 24 * 3600).toEpochMilli()
        return DeleteAccountRes(accepted = true, scheduledAt = scheduledAt)
    }
```

> 原代码 `mc.action.actorId ?: throw ApiError(ErrorCode.UNAUTHORIZED)` 未接收返回值，此处改为赋值给 `actorId` 复用。

- [ ] **步骤 7：编译验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 8：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/core/api/modules/auth/handler/AuthAggHandler.kt \
        core-api/src/main/kotlin/com/ifmix/core/api/modules/install/InstallFacade.kt
git commit -m "feat(auth): maintain install-customer relation on create/login/logout/delete"
```

---

### 任务 9：全量验证 + 文档同步

**文件：**
- 修改：`docs/AUTH_DESIGN.md`、`docs/DATABASE.md`

- [ ] **步骤 1：全量编译**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 2：全量测试**

运行：`./gradlew :core-api:test`
预期：全绿。重点确认 `InstallTokenTest`、`BindActionDecisionTest`、以及现有 `LoginActionDecisionTest`/`RequestParserTest` 未被破坏（signAccess 加了可选参数，向后兼容）。

- [ ] **步骤 3：迁移可跑验证（需本地 PG）**

运行：`./gradlew :core-api:flywayMigrate`
预期：V5 执行成功，`core_install` / `core_install_customer_relation` 建表。若无本地 PG 环境，跳过并在交接说明中标注「迁移未在真实 PG 验证」。

- [ ] **步骤 4：文档同步**

- `docs/DATABASE.md`：新增 `core_install` / `core_install_customer_relation` 两表条目（字段 + 唯一约束 + 软删语义），对齐现有表清单格式。
- `docs/AUTH_DESIGN.md`：补 token `type` claim（5/10/20，缺省 10）+ `iid` claim + install↔customer 关系维护规则（create/login/logout/delete 各分支），引用 `docs/design/install-tracking.md`。

- [ ] **步骤 5：Commit**

```bash
git add docs/DATABASE.md docs/AUTH_DESIGN.md
git commit -m "docs: document install tracking tables + token type/iid claims"
```

---

## 自检结果

**规格覆盖度：** 设计 §2 token(任务2/3)、§3 表(任务1)、§4 接口(任务7)、§5 关系维护(任务6/8：bind/unbind/换绑/deleteAccount 全覆盖)、§7 坑位(任务4步骤3 显式处理 disable 过滤 + 复活写字段)、§7.5 schema(任务7步骤1)。全覆盖。

**占位符扫描：** 无 TODO/待定；每个代码步骤含完整代码；Jimmer API 名不确定处（任务4步骤3、任务6步骤4）明确标注「以编译为准 + 修正方向」，非占位符而是已知实测点。

**类型一致性：** `signAccess(installId=...)`（任务2定义）在任务8调用一致；`VerifiedToken.tokenType/installId`（任务2）→ `ActionContext.tokenInstallId`（任务3）→ `ctx.tokenInstallId`（任务7/8）链路一致；`decideBindAction/BindAction`（任务5定义）在任务6调用一致；`InstallFacade.bind/unbind/unbindAllForCustomer`（任务8步骤1定义）在任务8步骤3-6调用一致；repo 方法名 `findAnyByPair/softDeleteOtherActiveByInstall/softDeleteActive/softDeleteAllActiveByCustomer/reactivate`（任务4）在任务6 handler 调用一致。

**已知实测点（非缺陷，实现时验证）：** ① Jimmer 0.11.x 关闭 `@LogicalDeleted` 过滤的确切 API（任务4）；② 直接 set `deletedAt` 复活是否需原生 update（任务4/6）；③ DGS 对 `JSON` 标量 input 是否生成类型 vs Map 承接（任务7）；④ `ActionContextProvider` 构造 ActionContext 的确切位置（任务3步骤3，需读文件确认）。

---

## 执行交接

**计划已完成并保存到 `docs/superpowers/plans/2026-09-29-install-tracking.md`。两种执行方式：**

**1. 子代理驱动（推荐）** - 每个任务调度一个新的子代理，任务间进行审查，快速迭代

**2. 内联执行** - 在当前会话中使用 executing-plans 执行任务，批量执行并设有检查点

**选哪种方式？**
