# Jimmer 架构对齐计划 — 命名重构 + 事务边界修正

> 执行者：Claude Code  
> 日期：2026-08-19  
> 前提：Round 2 修复已完成，编译通过  
> 参考：`/Users/jason/ai/myprojects/ifmix-sever-jooq/docs/ARCHITECTURE.md`

---

## 变更概览

| 维度 | 当前（Jimmer 版） | 目标 |
|------|-------------------|------|
| 对外入口 | `XxxModuleService` in `service/` | **`XxxFacade`** in module root |
| 业务处理器 | `XxxEntityService` in `service/internal/` | **`XxxHandler`** in `handler/` |
| 目录 | `modules/xxx/service/{internal/}` | `modules/xxx/{XxxFacade.kt, handler/}` |
| 事务范围 | Facade 方法整体包 `tx.withTx` | **最小边界**：只包写操作，读操作在事务外 |
| infra/repo | 继承 `BaseAppCrudRepository` | **组合**: 注入 `CrudRepoOps`（后续 PR，本计划可选） |

---

## 一、命名重构

### 1.1 规则

| 旧命名 | 新命名 | 位置 |
|--------|--------|------|
| `XxxModuleService.kt` | `XxxFacade.kt` | `modules/xxx/XxxFacade.kt` |
| `XxxEntityService.kt` | `XxxHandler.kt` | `modules/xxx/handler/XxxHandler.kt` |
| `XxxItemEntityService.kt` | `XxxItemHandler.kt` | `modules/xxx/handler/XxxItemHandler.kt` |
| 注解 `@Service` | `@Service` (不变) | Facade |
| 注解 `@Component` | `@Component` (不变) | Handler |

### 1.2 每个模块的文件变动

#### auth
```
modules/auth/
├── AuthFacade.kt                    ← service/AuthModuleService.kt
├── handler/
│   └── AuthHandler.kt              ← service/internal/AuthEntityService.kt
├── repo/                            (不动)
├── AuthConfig.kt                    (不动)
├── AuthLoggedInEvent.kt             (不动)
├── MergeOnLoginListener.kt          (不动)
├── ProviderVerifier.kt              (不动)
└── WechatVerifier.kt                (不动)
```

#### ai (scan)
```
modules/ai/
├── AiFacade.kt                      ← service/AiModuleService.kt
├── ScanCollectionFacade.kt          ← service/ScanCollectionModuleService.kt
├── handler/
│   ├── ScanHandler.kt              ← service/internal/ScanEntityService.kt
│   └── ScanCollectionHandler.kt    ← service/internal/ScanCollectionEntityService.kt
├── repo/                            (不动)
├── service/                         ← 保留非 facade/handler 的 infra 类
│   ├── AgnesKeyStore.kt             (不动)
│   ├── AgnesChatClientFactory.kt    (不动)
│   ├── SpringAiScanRunner.kt       (不动)
│   ├── ScanPrompt.kt               (不动)
│   └── AiConfig.kt                 (不动)
└── ScanRunner.kt                    (不动)
```

#### payment (iap)
```
modules/payment/
├── PaymentFacade.kt                 ← service/PaymentModuleService.kt
├── handler/
│   ├── PaymentHandler.kt          ← service/internal/PaymentEntityService.kt
│   └── PaymentWebhookHandler.kt   ← service/internal/PaymentWebhookHandler.kt
├── repo/                            (不动)
├── Entitlement.kt                   (不动)
├── IapConfig.kt                     (不动)
├── PurchaseVerifier.kt              (不动)
└── NotificationDecoder.kt           (不动)
```

#### cms (feedback)
```
modules/cms/
├── CmsFacade.kt                     ← service/CmsModuleService.kt
├── handler/
│   └── FeedbackHandler.kt         ← service/internal/CmsEntityService.kt
└── repo/                            (不动)
```

#### storage
```
modules/storage/
├── StorageFacade.kt                 ← service/StorageModuleService.kt
├── handler/
│   └── StorageHandler.kt          ← service/internal/StorageEntityService.kt
└── repo/                            (不动)
```

#### app
```
modules/app/
├── AppConfigFacade.kt               ← service/AppConfigModuleService.kt
├── handler/
│   └── AppConfigHandler.kt        ← service/internal/AppConfigEntityService.kt
└── repo/                            (不动)
```

#### demo (todo)
```
modules/demo/
├── DemoFacade.kt                    ← (新建，参考 ARCHITECTURE.md)
├── handler/
│   ├── TodoHandler.kt             ← (新建)
│   └── TodoItemHandler.kt         ← (新建)
└── repo/                            (不动)
```

### 1.3 操作步骤

对每个模块：
1. 创建 `handler/` 目录
2. `mv service/internal/XxxEntityService.kt → handler/XxxHandler.kt`
3. `mv service/XxxModuleService.kt → XxxFacade.kt`（移到模块根目录）
4. 文件内修改：
   - 改 `package` 声明
   - 改 class 名（`XxxModuleService` → `XxxFacade`，`XxxEntityService` → `XxxHandler`）
5. 更新所有引用方（DataFetcher 的 import + 注入名）
6. 删除空的 `service/` 和 `service/internal/` 目录

**不要用 find-and-replace**，逐个模块操作，每改一个模块编译验证一次。

---

## 二、事务边界最小化

### 2.1 当前问题

当前 Facade（原 ModuleService）中，mutation 整个 body 包在事务里：
```kotlin
// ❌ 当前: 整个方法在事务中（包括后续读）
fun updateScan(opCtx: OperationContext, input: UpdateScanInput): Boolean =
    tx.withTx(svcCtxFactory.forApp(opCtx)) { sc -> entityService.updateScan(sc, input) }
```

DataFetcher "写后读"场景：
```kotlin
// ❌ 当前: findById 也在事务内（因为 Facade 调用链）
@DgsMutation
fun updateScan(dfe, input): UpdateScanPayload {
    val success = scanService.updateScan(ctx, input)    // 开事务
    val record = scanService.findById(ctx, input.id)    // 另一个调用，但可能读从库
    return UpdateScanPayload(success, record)
}
```

### 2.2 目标模式

**原则：事务只包写操作。读操作在事务外执行，可走从库/缓存。**

```kotlin
// ✅ Facade 方法签名不变，但写操作返回最小结果（ID 或 Boolean）
@Service
class AiFacade(...) {
    /** 写操作 — 有事务 */
    fun updateScan(opCtx: OperationContext, input: UpdateScanInput): Boolean =
        tx.withTx(svcCtxFactory.forApp(opCtx)) { sc -> scanHandler.updateScan(sc, input) }

    /** 读操作 — 无事务 */
    fun findById(opCtx: OperationContext, id: UUID): ScanRecord? =
        scanHandler.findById(svcCtxFactory.forApp(opCtx), id)
}
```

```kotlin
// ✅ DataFetcher: 写和读分开调用，事务只在写那一步
@DgsMutation(field = "mutation_ai_updateScan")
fun updateScan(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateScanInput): UpdateScanPayload {
    val ctx = ctxProvider.fromDfe(dfe)
    // Step 1: 写 (有事务，通过 Facade 内部 tx.withTx)
    val success = scanFacade.updateScan(ctx, input)
    // Step 2: 读 (无事务，走从库/缓存)
    val record = if (success && dfe.selectionSet.fields.any { it.name == "scanRecord" }) {
        scanFacade.findById(ctx, input.id)
    } else null
    return UpdateScanPayload(success = success, scanRecord = record)
}
```

### 2.3 需要检查/修改的 Facade 方法

扫描所有 Facade，对每个方法判断：

| 模式 | 是否需要事务 | 处理 |
|------|-------------|------|
| 纯读（findById, findByCursor） | ❌ 不需要 | 直接调 handler，不包 tx |
| 纯写（create, update, delete） | ✅ 需要 | `tx.withTx { handler.xxx() }` |
| 写后读（create 返回完整对象） | ⚠️ 拆分 | Facade 写方法只返回 ID；DataFetcher 分两步调 |
| 复杂编排（多步写） | ✅ 需要 | Facade 包一个事务，内调多个 handler |

**具体场景**：

- `AiFacade.newScan` — 当前 `tx.withTx` 包了 AI 调用 + DB 写入。AI 调用是外部 HTTP 不该在事务里。改为：先 AI 调用（无事务），拿到结果后再 `tx.withTx` 只写 DB。
- `AuthFacade.loginWithIdToken` — 包含：验证 token + 查/写 DB + 签 JWT。验证和签 JWT 不需要在事务里。收窄为只包 DB 写部分。
- `PaymentFacade.verifyIapPurchase` — 包含外部验证 + DB upsert。外部验证不应在事务里。

### 2.4 反模式列表（CC 对照检查）

```kotlin
// ❌ 反模式 1: 外部 HTTP 调用在事务内
tx.withTx(sc) { 
    val result = externalApi.call()  // 网络 IO 占住事务连接
    repo.save(sc, entity)
}

// ✅ 正确: 先做 IO，再开事务写入
val result = externalApi.call()
tx.withTx(sc) { repo.save(sc, entity) }

// ❌ 反模式 2: 读操作包在事务内
tx.withTx(sc) {
    handler.create(sc, input)
    handler.findById(sc, id)  // 这行不需要在事务里
}

// ✅ 正确: 写返回 ID，读在事务外
val id = tx.withTx(sc) { handler.create(sc, input) }
val entity = handler.findById(sc, id)

// ❌ 反模式 3: Facade 查询方法包了事务
fun findById(opCtx, id) = tx.withTx(svc(opCtx)) { handler.findById(it, id) }

// ✅ 正确: 查询不需要事务
fun findById(opCtx, id) = handler.findById(svcCtxFactory.forApp(opCtx), id)
```

---

## 三、目录结构最终目标

```
core-api/src/main/kotlin/com/ifmix/api/core/
├── CoreApplication.kt
├── bff/
│   ├── graphql/customer/       # DGS DataFetcher
│   ├── webhooks/               # REST webhook
│   └── wellknown/              # JWKS
├── entity/                      # Jimmer interface entity (@Entity + @MappedSuperclass)
│   ├── ai/                      # ScanRecord, ScanCollection, ScanCollectionItem, AgnesKey
│   ├── auth/                    # AppUser, AuthIdentity, AuthProviderIdentity, etc.
│   ├── iap/                     # Subscription, StoreNotification
│   ├── todo/                    # Todo, TodoItem  (或 demo/)
│   ├── app/                     # AppConfigRevision, AppInfo
│   ├── feedback/                # Feedback
│   ├── storage/                 # UploadRecord
│   ├── shared/                  # Platforms, Tiers (跨模块枚举常量)
│   ├── scan/                    # ImageRef (嵌入值对象)
│   ├── AppScopedProps.kt        # @MappedSuperclass
│   ├── CreatedAtProps.kt
│   ├── MutableProps.kt
│   └── SoftDeletableProps.kt
├── modules/
│   ├── auth/
│   │   ├── AuthFacade.kt        # @Service 对外入口
│   │   ├── handler/
│   │   │   └── AuthHandler.kt   # @Component 业务逻辑
│   │   ├── repo/                # @Repository
│   │   ├── AuthConfig.kt
│   │   ├── AuthLoggedInEvent.kt
│   │   ├── MergeOnLoginListener.kt
│   │   ├── ProviderVerifier.kt
│   │   └── WechatVerifier.kt
│   ├── ai/
│   │   ├── AiFacade.kt
│   │   ├── ScanCollectionFacade.kt
│   │   ├── handler/
│   │   │   ├── ScanHandler.kt
│   │   │   └── ScanCollectionHandler.kt
│   │   ├── repo/
│   │   ├── service/             # AI infra (非 facade/handler)
│   │   │   ├── AgnesKeyStore.kt
│   │   │   ├── AgnesChatClientFactory.kt
│   │   │   ├── SpringAiScanRunner.kt
│   │   │   ├── ScanPrompt.kt
│   │   │   └── AiConfig.kt
│   │   └── ScanRunner.kt
│   ├── payment/
│   │   ├── PaymentFacade.kt
│   │   ├── handler/
│   │   │   ├── PaymentHandler.kt
│   │   │   └── PaymentWebhookHandler.kt
│   │   ├── repo/
│   │   ├── Entitlement.kt
│   │   ├── IapConfig.kt
│   │   ├── PurchaseVerifier.kt
│   │   └── NotificationDecoder.kt
│   ├── cms/
│   │   ├── CmsFacade.kt
│   │   ├── handler/
│   │   │   └── FeedbackHandler.kt
│   │   └── repo/
│   ├── storage/
│   │   ├── StorageFacade.kt
│   │   ├── handler/
│   │   │   └── StorageHandler.kt
│   │   └── repo/
│   ├── app/
│   │   ├── AppConfigFacade.kt
│   │   ├── handler/
│   │   │   └── AppConfigHandler.kt
│   │   └── repo/
│   └── demo/
│       ├── DemoFacade.kt
│       ├── handler/
│       │   ├── TodoHandler.kt
│       │   └── TodoItemHandler.kt
│       └── repo/
├── dto/                         # 跨模块 DTO
├── infra/
│   ├── jimmer/                  # ClusterRegistry, ReadWriteRouting, JimmerConfig, Interceptor
│   ├── repo/                    # CrudRepoOps, CrudRepoOpsFactory (组合)
│   ├── tx/                      # TxRunner, GlobalTxRunner
│   ├── service/                 # CrudServiceOps (缓存层)
│   ├── db/                      # SvcCtx, SvcCtxFactory, ClusterRouter, UuidV7, Ownership
│   ├── graphql/                 # OperationContextProvider, scalars, ExceptionHandler
│   ├── http/                    # ApiError, ErrorCode, RequestContext, Interceptors
│   ├── auth/                    # JWT, AuthInterceptor, Hashing
│   ├── redis/                   # CacheAside, RedisConfig
│   ├── ratelimit/               # RateLimiter
│   ├── storage/                 # ObjectStorage + S3
│   └── config/                  # WebConfig, JacksonConfig
└── resources/
    ├── schema/                  # GraphQL schema
    ├── db/migration/            # Flyway
    ├── prompts/                 # AI prompts
    └── application.yml
```

---

## 四、其他对齐事项

### 4.1 ARCHITECTURE.md 需要全面重写

当前 `docs/ARCHITECTURE.md` 还描述 jOOQ 时代。需要更新：
- 技术栈表：jOOQ → Jimmer + KSP
- 分层图：CrudOps (jOOQ) → CrudRepoOps (Jimmer)
- 术语：ModuleService → Facade，EntityService → Handler
- 事务管理段：加入最小边界原则
- 目录结构：更新为上述目标结构
- Demo 代码：用 Jimmer DSL 重写

### 4.2 AGENTS.md / CLAUDE.md 需要同步更新

- 分层规则表中的术语
- 目录结构说明
- 代码约定中的 Service 分层约定

### 4.3 `infra/jooq/` 目录应删除或重命名

当前 `infra/jimmer/` 是正确的。确认没有残留的 `infra/jooq/` 目录。

### 4.4 `CrudServiceOps` 保持不变

`CrudServiceOps` 的职责是缓存决策（readCache → CacheAside），与底层数据访问层无关。Facade 仍然使用它。

---

## 五、执行顺序

### Phase 1: 命名重构（机械操作，无逻辑变更）

1. 逐个模块执行 1.3 的步骤（auth → ai → payment → cms → storage → app → demo）
2. 每改完一个模块，`./gradlew :core-api:compileKotlin` 验证
3. 全部改完后全量编译确认

### Phase 2: 事务边界收窄

4. 扫描所有 Facade 方法，对照 2.4 反模式列表修正
5. 重点修复：
   - `AiFacade.newScan` — AI 调用移出事务
   - `AuthFacade.loginWithIdToken/loginWithCode` — token 验证、JWT 签发移出事务
   - `PaymentFacade.verifyIapPurchase` — 外部验证移出事务
6. DataFetcher 中"写后读"场景：确认读调用不在事务内

### Phase 3: 文档更新

7. 重写 `docs/ARCHITECTURE.md`
8. 更新 `AGENTS.md` 分层约定

### Phase 4: 可选 — repo 组合化

9. 创建 `infra/repo/CrudRepoOps.kt` + `CrudRepoOpsFactory.kt`
10. 逐个 Repo 从继承改为组合
11. 删除 `BaseCrudRepository.kt` / `BaseAppCrudRepository.kt`

---

## 六、验收标准

1. `./gradlew :core-api:compileKotlin` 零错误
2. 所有模块遵循 `XxxFacade` + `handler/XxxHandler` 命名
3. 无 Facade 方法在事务内包含外部 IO
4. 无纯读 Facade 方法包 `tx.withTx`
5. DataFetcher 的"写后读"分两步调用（写在事务内，读在事务外）
6. `docs/ARCHITECTURE.md` 反映新结构
