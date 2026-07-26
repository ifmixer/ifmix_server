# ifmix core-api 剩余模块 实现计划（补充）

> 承接 `2026-07-26-ifmix-core-foundation.md`（地基 + todo 切片已实现）。本篇规划把旧项目
> `ifmix_apps/server/core` 除 auth 外的其余模块移植到 Kotlin/Spring/MongoDB。
> 参考：`ifmix_apps/docs/superpowers/specs/`（尤其 `2026-07-26-postgres-to-mongodb-design.md`
> 的逐模块建模 §五，及各模块设计）。对外 HTTP 契约沿用旧项目形状（信封、URL、DTO）。

**目标：** 移植 appconfig(app-info/app-config)、antique(AI 扫描 + 历史)、iap(内购)、
collection(收藏)、feedback(反馈)；auth 暂缓。

**架构约定（沿用地基，见「地基重构」任务后）：** module-first；`BaseDocument`（id + 时间戳）
+ 三个正交**能力接口** `AppScoped`/`SoftDeletable`/`Versioned` + 便利基类 `BaseAppDocument`
（= app 级 + 软删的常见组合）；单一 `CRUDRepository<T : BaseDocument>`（构造时反射 `type`
探测能力，自动适配 appId 注入与软删，**不再有** `CRUDAppRepository` 与 `softDelete` 开关）
+ `CRUDService`（组合复用）；`RequestContext` 显式传递 + `readPreference`；`ObjectId` 主键
（对外 hex）、`Instant`↔epoch ms；游标分页；Konvert 做 DTO 映射；分片键 `appId`
（by-id 操作经 `extraIdCriteria` 注入，`AppScoped` 文档自动带上）。

---

## 模块依赖层次（禁止环，单向向下）

```
common/                     http、db(BaseDocument/能力接口/CRUDRepository/CRUDService)、tx、config、redis、storage、ai   ← 基础设施
  ▲
modules/appconfig           app_info（身份）+ app_config（版本化配置）            ← 共享底座
  ▲          ▲        ▲
modules/antique  modules/iap   （antique 读 appconfig 取 tier/AI；iap 读 appconfig 取商店凭证）
  ▲                 │
modules/collection  │        （collection 引用 antique 的 scan_record + ScanDto）
modules/feedback    │
```

- 功能模块**向下依赖** `common` 与 `appconfig`；`collection → antique` 是唯一允许的横向依赖（单向，引用 scan 记录/DTO）。
- 不成环。可选后续加 ArchUnit 断言依赖方向。

## 需要先确认的外部依赖 / 决策

> 本节为**已确认决策**（2026-07-27）。

1. **时间戳统一 `Instant`（方案 C）**：文档/BO/DTO 一律 `Instant`，映射零转换；查询/游标走原生 `Instant↔Date`（可靠）。**对外 JSON** 用全局 Jackson（Jackson 3 / `tools.jackson`）`Instant → epoch 毫秒`序列化器统一输出；OpenAPI 用 springdoc `Instant → int64` 映射。**不用** `spring.jackson.serialization.*` 属性（Jackson 3 已改这些 date 开关，不可靠）。详见「任务 0」。
2. **Redis**（antique 限流）：`spring-boot-starter-data-redis`（Lettuce），连接映射自 `REDIS_URL` → `spring.data.redis.url`。固定窗口计数（UTC 日）。
3. **对象存储 R2/S3**：`software.amazon.awssdk:s3` + `s3-request-presigner`，endpoint/bucket/密钥全走 env。
4. **AI**：用 **Spring AI**（`ChatClient` + 多模态 + 结构化输出绑 `ScanResult`）。**单列 AI 专项计划**讨论（含是否保留多 key 轮换/配额）。antique 里先定义 `ScanRunner` 接缝，实现随 AI 计划落。
5. **IAP**：`com.apple:app-store-server-library`（Java）+ `com.google.apis:google-api-services-androidpublisher`，凭证从 app_config。
6. **事务**：config 版本化、iap upsert 用地基 `TxRunner`（副本集）。

> 实现顺序：**任务 0（时间戳 C）→ appconfig → antique(基础设施 redis/storage + ScanRunner 接缝 + 扫描；AI 实现单列)→ iap → collection → feedback**。auth 单独立项。

---

## 任务 R：地基重构（`BaseDocument` + 能力接口 + 单一 `CRUDRepository`）

**动机：** `appId`/`deletedAt`/`revision` 是正交、可自由组合的能力，单继承无法组合（组合爆炸）。
改用能力接口，配一个覆盖"app 级 + 软删"常见组合的便利基类，Repository 反射探测能力自动适配，
顺带合并掉 `CRUDAppRepository` 与 `softDelete: Boolean` 构造参数。

**命名定案：** `CRUDDocument` → `BaseDocument`；新增 `BaseAppDocument`（便利基类）；能力接口
`AppScoped`/`SoftDeletable`/`Versioned`；`CRUDAppService` → `CRUDService`。

**文件：**
- 重写：`common/db/CRUDDocument.kt` → 删除，新建 `common/db/BaseDocument.kt`（含能力接口 + `BaseAppDocument`）。
- 删除：`common/db/CRUDAppDocument.kt`（`appId` 下沉到 `AppScoped`/`BaseAppDocument`）、`common/db/CRUDAppRepository.kt`（能力下沉 `CRUDRepository`）。
- 重写：`common/db/CRUDRepository.kt`（泛型改 `<T : BaseDocument>`，构造去 `softDelete`，反射探测 `AppScoped`/`SoftDeletable`）。
- 重命名：`common/service/CRUDAppService.kt` → `CRUDService.kt`（泛型 `<T : BaseDocument>`，按能力条件盖章）。
- 修改：`modules/todo/TodoDocument.kt`（`CRUDAppDocument` → `BaseAppDocument`）、`modules/todo/TodoService.kt`（持有的 `CRUDAppService` → `CRUDService`，构造 repo 去掉 `softDelete` 实参）。
- 修改：`modules/todo` 及 `common/db` 相关测试（构造签名变化）。

- [ ] **步骤 1：新建 `BaseDocument.kt`（基类 + 能力接口 + 便利基类）**

```kotlin
package com.ifmix.api.core.common.db

import org.springframework.data.annotation.Id
import java.time.Instant

/** 所有文档的公共字段：id + 两个时间戳。deletedAt/appId/revision 均为可选能力，见下方接口。 */
abstract class BaseDocument {
    @Id
    var id: String? = null
    var createdAt: Instant? = null
    var updatedAt: Instant? = null
}

/** 能力：多租户，按 appId 分片。实现后 CRUDRepository 自动注入 appId 过滤与盖章。 */
interface AppScoped {
    var appId: String?
}

/** 能力：软删。实现后 CRUDRepository 的删除走 deletedAt 标记，读写自动过滤 deletedAt=null。 */
interface SoftDeletable {
    var deletedAt: Instant?
}

/** 能力：版本化（追加式）。仅提供字段；版本化逻辑由各模块 repo（如 AppConfigRepo）实现。 */
interface Versioned {
    var revision: Int
}

/** 便利基类：覆盖"app 级 + 软删"这个最常见组合，把字段写一次，避免每个文档重复 override。 */
abstract class BaseAppDocument : BaseDocument(), AppScoped, SoftDeletable {
    override var appId: String? = null
    override var deletedAt: Instant? = null
}
```

- [ ] **步骤 2：重写 `CRUDRepository.kt`（反射探测能力，合并租户与软删）**

关键变化（其余游标/更新逻辑保持不变，仅把 `softDelete` 换成 `softDeletable`、`extraCriteria`/
`extraIdCriteria` 内联成能力判断）：

```kotlin
open class CRUDRepository<T : BaseDocument>(
    protected val mongo: MongoTemplate,
    protected val type: Class<T>,
) {
    protected val appScoped: Boolean = AppScoped::class.java.isAssignableFrom(type)
    protected val softDeletable: Boolean = SoftDeletable::class.java.isAssignableFrom(type)

    /** app 级列表/by-id 过滤：分片键 appId。非 app 文档返回 null。 */
    private fun tenantCriteria(ctx: RequestContext): Criteria? =
        if (appScoped) Criteria.where("appId").`is`(ctx.appId) else null

    /** 附加过滤钩子（保留）：模块可覆写注入自定义列表过滤（如 collection 按 collectionId）。默认无。 */
    protected open fun extraCriteria(ctx: RequestContext): Criteria? = null
    /** 附加过滤钩子（保留）：模块可覆写注入自定义 by-id 过滤。默认无。 */
    protected open fun extraIdCriteria(ctx: RequestContext): Criteria? = null

    // findByCursor 内：tenantCriteria(ctx)?.let { query.addCriteria(it) }
    //                  extraCriteria(ctx)?.let { query.addCriteria(it) }
    //                  if (softDeletable) query.addCriteria(Criteria.where("deletedAt").`is`(null))
    // idCriteria 内：tenantCriteria(ctx)?.let { list.add(it) } + extraIdCriteria(ctx)?.let { list.add(it) } + _id
    // idQuery 内：  if (softDeletable) criteria.add(Criteria.where("deletedAt").`is`(null))
    // deleteById 内：if (softDeletable) 走 set deletedAt 否则 mongo.remove
}
```

> **保留** `extraCriteria`/`extraIdCriteria` 两个 open 钩子作为附加扩展点：自动的 appId（`appScoped`）
> 与软删（`softDeletable`）由基类内聚，钩子叠加在其上，供模块注入自定义过滤（collection 按
> collectionId、iap 按 platform 等）。`CRUDAppRepository` 仍删除（appId 已自动）。

- [ ] **步骤 3：删除 `CRUDDocument.kt`、`CRUDAppDocument.kt`、`CRUDAppRepository.kt`**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/common/db/CRUDDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/common/db/CRUDAppDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/common/db/CRUDAppRepository.kt
```

- [ ] **步骤 4：`CRUDAppService.kt` → `CRUDService.kt`（按能力盖章）**

```kotlin
class CRUDService<T : BaseDocument>(
    private val repo: CRUDRepository<T>,
) {
    fun createOne(ctx: RequestContext, entity: T): String {
        val now = Instant.now()
        if (entity is AppScoped) entity.appId = ctx.appId
        entity.createdAt = now
        entity.updatedAt = now
        if (entity is SoftDeletable) entity.deletedAt = null
        repo.insertOne(ctx, entity)
        return entity.id!!
    }
    // findById/getById/updateById/deleteById/findByCursor 原样委托
}
```

- [ ] **步骤 5：改 `TodoDocument`（`CRUDAppDocument` → `BaseAppDocument`）与 `TodoService`**

`TodoDocument : BaseAppDocument()`（appId/deletedAt 由基类提供）。`TodoService` 里
`CRUDAppService<TodoDocument>(CRUDAppRepository(mongo, TodoDocument::class.java, softDelete = true))`
→ `CRUDService<TodoDocument>(CRUDRepository(mongo, TodoDocument::class.java))`。

- [ ] **步骤 6：改相关测试**（`CRUDRepository`/`TodoService` 测试里 repo 构造去掉 `softDelete` 实参、类型改名）。

- [ ] **步骤 7：编译 + 非 Mongo 测试**

运行：`./gradlew :core-api:compileKotlin :core-api:compileTestKotlin`
再跑受影响的非 Mongo 单测（`--tests` 过滤）。预期全绿。

- [ ] **步骤 8：Commit**（不自动 commit，交用户 review 后手动）。

> 注：`BaseDocument` 的 `createdAt/updatedAt` 类型与「任务 0」的 `Instant` 方案一致，无冲突。

---

## 任务 0：时间戳统一 `Instant` + 全局 epoch-ms 序列化（方案 C，地基）

**动机：** 文档/BO/DTO 全 `Instant`，映射零转换，查询原生可靠；仅出站 JSON 转 epoch 毫秒。

**文件：**
- 修改：`application.yml`（移除之前误加的 `spring.jackson.serialization.*` 属性）。
- 创建：`common/config/JacksonConfig.kt`（注册 Jackson 3 `JacksonModule`：`Instant` → epoch 毫秒序列化器 + epoch 毫秒 → `Instant` 反序列化器备用）。
- 修改：`common/config/OpenApiConfig.kt`（`SpringDocUtils.replaceWithClass(Instant, Long)`）。
- 修改：DTO 时间字段用 `Instant?`（`TodoDto` 等）；mapper 直接拷贝（Konvert `Instant→Instant`，无 `@Mapping(expression)`）。

**任务（TDD，`@JsonTest` 无需 Docker）：**
1. 失败测试 `InstantJsonSerializationTest`（`@JsonTest` + Jackson 3 `ObjectMapper`，序列化含 `Instant` 的 DTO，断言输出 epoch 毫秒整数、null 保持 null）。
2. 实现 `JacksonConfig`：`@Bean` 返回 `tools.jackson.databind.JacksonModule`，`SimpleModule().addSerializer(Instant, 写 gen.writeNumber(it.toEpochMilli()))` + 反序列化 `Instant.ofEpochMilli(p.longValue)`。（Jackson 3 类名 `ValueSerializer`/`SimpleModule` 于 `tools.jackson.databind.*`，以编译为准。）
3. 运行通过。
4. OpenApiConfig 加 `replaceWithClass(Instant, Long)`；DTO/mapper 调为 `Instant`；`compileTestKotlin` + mapper 单测通过。
5. Commit。

> 之前仓促加的 Instant/appconfig 草稿以本计划理顺（application.yml 去属性、改注册序列化器）。

---

## 模块 1：appconfig（app_info + app_config）— 先实现，无外部依赖

参考：`2026-07-22-soft-delete-config-versioning-design.md`、`postgres-to-mongodb-design §五`、源码 `common/appconfig/*`。

### 文档（`modules/appconfig/`）

- `AppInfoDocument`（集合 `app_info`，稳定身份，**不软删/不版本化**，不分片——是 app 注册表）：
  - `_id`（= appId，ObjectId hex 对外）、`name`、`desc?`、`slug?`（唯一）、`createdAt`/`updatedAt`。
  - 直接继承 `BaseDocument`（它本身就是 app 身份，无 appId 外键、无软删）——**不实现任何能力接口**；用 `CRUDRepository<AppInfoDocument>` 直接读或 `MongoTemplate`。
- `AppConfigDocument`（集合 `app_config`，版本化）：`BaseAppDocument`（app 级 + 软删）**并实现 `Versioned`**：
  - `_id`(ObjectId)、`appId`、`authTenantId?`、`appleBundleId?`、`androidPackageName?`、
    `apple`(内嵌对象 `{appAppleId?,issuerId?,keyId?,privateKey?,servicesId?}`)、
    `google`(内嵌 `{serviceAccount?,clientIds?:{ios?,android?,web?}}`)、
    `iap`(内嵌 `{productTierMap?:Map<String,String>, env?}`)、
    `revision`(Int，来自 `Versioned`)、`deletedAt?`/`appId`（来自 `BaseAppDocument`）、`createdAt`/`updatedAt`。
  - 索引：`appleBundleId`、`androidPackageName`；**partial unique `(appId) where deletedAt=null`**（至多一条当前版本；分片键前缀）。

### 扁平视图 `AppConfig`（BO/值对象，下游读取用）

- 把三个内嵌对象扁平成 `appleBundleId/appleIssuerId/applePrivateKey/googleClientIds/productTierMap/iapEnv...`（沿用旧 `AppConfig` 接口字段名，下游零改动）。
- `AppConfigMapper`（Konvert 或手写）把 `AppConfigDocument` → `AppConfig`。

### 仓储/服务 `AppConfigRepo`（不走通用 `CRUDService`——按 appId/bundleId/packageName 查“当前版本”，自建查询）

- `getByAppId(appId)` / `getByAppleBundleId(bundleId)` / `getByAndroidPackage(pkg)`：查 `deletedAt=null` 当前版本，映射成 `AppConfig`；**内存缓存 TTL 60s**（可用 Caffeine 或简单 ConcurrentHashMap + 时间戳）。
- `newVersion(ctx, appId, patch)`：**`TxRunner.withTx`** 内：软删当前版本（set deletedAt）→ 插入 `revision+1` 的新当前版本 → 清缓存。
- `AppInfoRepo`：`getById(appId)`（基础 CRUD）。

### 任务（TDD，需 Docker/Mongo 副本集跑集成测试）

1. `AppInfoDocument` + `AppConfigDocument`（内嵌子对象用 data class 或 `Map`）。
2. `AppConfig` 扁平视图 + `AppConfigMapper`（单元测试：内嵌 → 扁平映射正确，含默认 `iapEnv="production"`、空 map）。
3. `AppInfoRepo`（`CRUDRepository<AppInfoDocument>` 直接用，或薄封装）。
4. `AppConfigRepo`：`getByX` + 缓存（集成测试：查当前版本、软删历史不返回；缓存命中）。
5. `newVersion` 版本化（集成测试：软删旧 + 插新版本号 +1；并发/唯一约束；缓存清除）。
6. 索引与 partial unique 的创建（`@CompoundIndex` / 启动 ensureIndexes；分片键前缀说明）。
7. 装配 bean（`AppConfigRepo`、`AppInfoRepo`）。

---

## 模块 2：antique（AI 扫描 + 历史）

参考：`2026-07-20-antique-unauth-scan-design.md`、`2026-07-25-antique-server-history-and-nav`、源码 `modules/antique/*`、`common/{ai,ratelimit,storage,redis}`。

### 新基础设施（common）

- `common/redis`：`RedisTemplate`/`StringRedisTemplate`（Lettuce）。
- `common/ratelimit`：固定窗口日限流 `checkRateLimit/refundRateLimit`（按 `(appId, subject)`；`subject = userId ?? installId ?? ip` 见 iap 后更新）；`limitForTier(tier, limits)`；`TierResolver`（v1 恒 free，iap 落地后改）。
- `common/storage`：R2/S3 `presignGetUrl(key)` / `presignPutUrl`（AWS SDK v2 presigner）。
- `common/ai`：**先定义接缝 `ScanRunner.runScan(input): ScanResult`**。真正的 AI 实现用 **Spring AI**（`ChatClient` 多模态 + 结构化输出绑 `ScanResult`），**单列 AI 专项计划**（含是否保留旧的多 key 轮换/配额/429 冷却/fallback）。antique 扫描流程先依赖接缝，AI 实现随专项计划落；期间可用一个占位/stub 实现打通端到端测试。

### 文档

- `ScanRecordDocument`（集合 `scan_record`，`BaseAppDocument`，软删，分片 appId）：
  `appId`、`installId`(字符串)、`ip?`、`userId?`(ObjectId 预留)、`imageKey`、`language?`、`currency?`、`aiResult`(内嵌对象/`Map`)、`modelUsed?`、`isAntique?`、`name?` + 时间戳。
  索引 `(appId, installId, _id)`。

### 端点（customer BFF）+ 通用 R2 签名

- `POST /customer/core/mutation/antique/newScan`：限流(resolveSubject/tier) → R2 取图 URL → `runScan` → 落库 → **失败退还限额**（对标旧 `AntiqueService.newScan`）。
- `PUT  /customer/core/query/antique/listScans`（游标分页，按 installId/userId 归属）、`getById`。
- `POST /customer/core/mutation/auth/r2SignUpload`（R2 预签名上传，跨 app 通用；先放在一个 storage/通用控制器，auth 落地前不属于 auth 模块本身）。

### 任务：见旧 `2026-07-20-antique-unauth-scan.md` 计划；按上面基础设施 + 文档 + 端点拆分 TDD。**需先确认外部依赖决策 1/2/3。**

---

## 模块 3：iap（内购 + webhooks）

参考：`2026-07-20-iap-design.md`、`2026-07-24-antique-app-iap-design.md`、源码 `modules/iap/*`。

### 文档

- `SubscriptionDocument`（`BaseAppDocument`，分片 appId）：`installId`、`userId?`、`platform`、`productId`、`originalTransactionId`、`status`(active/in_grace/canceled/expired/revoked)、`expiresAt?`、`autoRenew`、`environment`、`raw`(内嵌) + 时间戳。
  **唯一约束改 `(appId, platform, originalTransactionId)`**（分片键前缀；跨 app 去重靠 IAP 通知幂等 upsert）。索引 `(appId, installId)`、`(appId, userId)`。
- `StoreNotificationDocument`（审计，分片 appId）：`platform`、`notificationType`、`originalTransactionId`、`payload`(内嵌) + 时间戳。

### 端点

- `POST /customer/core/mutation/iap/verify`：验证凭证 → upsert 订阅 → 返回档位。已登录购买写 `userId`（auth 前向兼容，`ctx.userId` 现恒空）。
- `POST /webhooks/iap/apple`（ASSN v2）、`POST /webhooks/iap/google`（RTDN Pub/Sub）→ 更新订阅 + 审计（webhooks BFF，不校验 x-app-id）。
- 与 antique 限流联动：`resolveTier` 改为按订阅判定（active/in_grace 未过期 → pro）；`resolveSubject = userId ?? installId ?? ip`。

### 接缝

- `PurchaseVerifier.verify(config: AppConfig, input): SubscriptionState`（Apple/Google 实现，凭证从 app_config 注入）。
- `NotificationDecoder.decode(config, rawBody): DecodedNotification`。
- 依赖：Apple `app-store-server-library`(Java)、Google `androidpublisher`。**需确认外部依赖决策 4。**

### 任务：见旧 `2026-07-20-iap.md` 计划；按文档 + verify 流程 + webhook 拆分 TDD。

---

## 模块 4：collection（收藏）

参考：`2026-07-26-collections-feedback-design.md`、源码 `modules/collection/*`。

### 文档（均 `BaseAppDocument`，软删，分片 appId）

- `CollectionDocument`：`installId`、`userId?`、`isDefault`(Boolean) + 时间戳。partial unique `(appId, installId) where isDefault && deletedAt=null`。
- `CollectionItemDocument`：`collectionId`、`scanRecordId` + 时间戳。partial unique `(collectionId, scanRecordId) where deletedAt=null`。（item 不带 userId，归属由父 collection 派生。）

### 端点（customer BFF）

- `PUT /query/collection/getDefault`（get-or-create 默认夹）、`POST /mutation/collection/addItem`、`POST /mutation/collection/removeItems`（批量软删 ≤100）、`PUT /query/collection/listItems`（join scan_record，游标分页，复用 antique `ScanDto`）。
- 归属助手 `ownsRow(ctx, {userId, installId})`：登录时 userId 或 installId 命中；匿名仅 installId。
- **横向依赖 antique**（`ScanRecordDocument` + `ScanMapper`）——单向。

### 任务：文档 + `CollectionService`（组合 `CRUDService` + itemRepo）+ 4 端点 TDD。

---

## 模块 5：feedback（反馈）

参考：`2026-07-26-collections-feedback-design.md`、源码 `modules/feedback/*`。

### 文档

- `FeedbackDocument`（`BaseAppDocument`，软删，分片 appId）：`installId`、`userId?`、`scanRecordId?`、`category`（预设枚举）、`note?` + 时间戳。

### 端点

- `POST /customer/core/mutation/feedback/submit`：`{ scanRecordId?, category, note? }` → 落库 → `{ id }`。

### 任务：文档 + `FeedbackService`（组合 `CRUDService`）+ 1 端点 TDD。

---

## auth（暂缓，单独立项）

参考 `2026-07-20-auth-design.md`。EdDSA JWT + JWKS + Google/Apple SSO + 设备密钥交换 + refresh token 轮换（副本集事务）。等前述模块完成后单独出规格。

---

## 通用注意（沿用地基经验）

- 请求 DTO 必填字段：可空 + 默认 null + `@field:` 校验（Jackson 3 / Konvert）。
- 时间入参若需按时间过滤：`Instant`（DTO 用 epoch ms，映射转换）。
- 分片集合唯一索引必须以分片键 `appId` 为前缀。
- 集成测试用 Testcontainers Mongo 副本集（事务）。
- Konvert `@Konverter interface XxxMapper { toDto(...) }`，`Konverter.get()` 取用。
- 每模块结束跑 `./gradlew :core-api:test` + commit。
