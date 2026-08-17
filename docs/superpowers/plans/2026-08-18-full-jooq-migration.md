# 全模块迁移实施计划 (Jimmer → jOOQ + Domain Model + DGS)

> 日期: 2026-08-18
> 前置: docs/superpowers/specs/2026-08-18-jooq-migration-design.md (架构设计)
> 状态: Todo 模块已完成示范（TodoJooqRepository + TodoJooqService + TodoFetcher）

## 模块清单与依赖关系

```
auth ←── scan (AI 扫描需认证)
  ↑        ↑
  │        └── collection (收藏依赖 scan)
  │
  ├── iap (订阅依赖用户)
  ├── storage (上传记录)
  ├── feedback (反馈关联 scan)
  └── app (配置，独立)

todo (已完成，独立)
ai (内部服务，不直接暴露 GraphQL)
```

## 迁移顺序（按依赖 + 复杂度排）

| Wave | 模块 | 复杂度 | 说明 |
|------|------|--------|------|
| 0 | todo | ✅ 已完成 | 示范模块 |
| 1 | feedback | 低 | 单表，1 个 mutation，无关联 |
| 1 | storage | 低 | 单表，2 个 mutation，无查询 |
| 1 | app | 低 | 只读配置，无 mutation |
| 2 | scan | 中 | 核心业务，AI 调用，有 update + cursor 分页 |
| 2 | collection | 中 | 关联 scan，DataLoader 场景 |
| 3 | auth | 高 | 7 个 repo，复杂业务逻辑，token 管理 |
| 3 | iap | 高 | 外部商店集成，webhook |
| 4 | ai | 中 | 内部服务改造（AgnesKey repo） |
| 5 | cleanup | - | 删除所有 Jimmer 代码 |

---

## Wave 1: 简单模块（feedback / storage / app）

### 1.1 Feedback

**Entity → Model:**
| Jimmer | Domain Model |
|--------|-------------|
| `entity/feedback/Feedback.kt` | `model/Feedback.kt` |

**需要的文件:**
- `model/Feedback.kt` — data class
- `modules/feedback/repo/FeedbackJooqRepository.kt` — insert + findByCursor
- `modules/feedback/service/FeedbackJooqService.kt` — submit
- `bff/graphql/customer/FeedbackFetcher.kt` — mutation_submitFeedback

**GraphQL schema:** `schema/customer/feedback.graphqls` (已有)

**DGS typeMapping 新增:** 无（Feedback 不是 GraphQL output type，只返回 `SubmitFeedbackPayload { id }`)

---

### 1.2 Storage

**Entity → Model:**
| Jimmer | Domain Model |
|--------|-------------|
| `entity/storage/UploadRecord.kt` | `model/UploadRecord.kt` |

**需要的文件:**
- `model/UploadRecord.kt` — data class
- `modules/storage/repo/StorageJooqRepository.kt` — insert upload record
- Service 不需要单独建（逻辑在 ScanService 中合并，或独立 StorageService）
- `bff/graphql/customer/StorageFetcher.kt` — mutation_presignUpload, mutation_presignDownload

**注意:** presign 逻辑依赖 S3 ObjectStorage（`infra/storage/`），不变。

---

### 1.3 App (AppConfig)

**Entity → Model:**
| Jimmer | Domain Model |
|--------|-------------|
| `entity/appconfig/AppInfo.kt` | `model/AppInfo.kt` |
| `entity/appconfig/AppConfigRevision.kt` | `model/AppConfigRevision.kt` |

**需要的文件:**
- `model/AppInfo.kt`, `model/AppConfigRevision.kt`
- `modules/app/repo/AppConfigJooqRepository.kt` — findByAppId, findActiveRevision
- `modules/app/service/AppConfigJooqService.kt`
- 保留 REST `bff/app/AppConfigController.kt`（或迁移到 GraphQL 看需要）

---

## Wave 2: 核心业务（scan / collection）

### 2.1 Scan

**Entity → Model:**
| Jimmer | Domain Model |
|--------|-------------|
| `entity/scan/ScanRecord.kt` | `model/ScanRecord.kt` |
| `entity/scan/ImageRef.kt` | `model/ImageRef.kt` (嵌入类型，保留 data class) |

**需要的文件:**
- `model/ScanRecord.kt`, `model/ImageRef.kt`
- `modules/scan/repo/ScanJooqRepository.kt` — CRUD + cursor 分页 + status 更新
- `modules/scan/service/ScanJooqService.kt` — newScan(调用 AI) + findById + findByCursor + update + delete
- `bff/graphql/customer/ScanFetcher.kt` — query + mutation

**特殊处理:**
- `result` 字段是 JSONB `Map<String, Any?>`，jOOQ 用 `JSONB` 类型 + Jackson 反序列化
- `images` 字段是 JSONB `List<ImageRef>`，同上
- AI 调用（ScanRunner）不改，只改数据层
- Storage presign 逻辑可能移到 StorageFetcher

**DGS typeMapping:** `"ScanRecord" to "com.ifmix.api.core.model.ScanRecord"`

---

### 2.2 Collection

**Entity → Model:**
| Jimmer | Domain Model |
|--------|-------------|
| `entity/scan/ScanCollection.kt` | `model/ScanCollection.kt` |
| `entity/scan/ScanCollectionItem.kt` | `model/ScanCollectionItem.kt` |

**需要的文件:**
- `model/ScanCollection.kt`, `model/ScanCollectionItem.kt`
- `modules/scan/repo/CollectionJooqRepository.kt` — getDefault + addItem + removeItems + findItemsByCursor
- `modules/scan/service/CollectionJooqService.kt`
- `bff/graphql/customer/CollectionFetcher.kt`
- `bff/graphql/customer/CollectionItemDataLoader.kt` — ScanCollectionItem.scanRecord 关联

**DataLoader:**
- `ScanCollectionItem.scanRecord` → 需要 DataLoader 批量加载 ScanRecord（跨表）

**DGS typeMapping:**
- `"ScanCollection" to "com.ifmix.api.core.model.ScanCollection"`
- `"ScanCollectionItem" to "com.ifmix.api.core.model.ScanCollectionItem"`

---

## Wave 3: 复杂模块（auth / iap）

### 3.1 Auth

**Entity → Model (7 个):**
| Jimmer | Domain Model |
|--------|-------------|
| `entity/auth/AppUser.kt` | `model/auth/AppUser.kt` |
| `entity/auth/AuthIdentity.kt` | `model/auth/AuthIdentity.kt` |
| `entity/auth/AuthProviderIdentity.kt` | `model/auth/AuthProviderIdentity.kt` |
| `entity/auth/AuthDeviceSecret.kt` | `model/auth/AuthDeviceSecret.kt` |
| `entity/auth/AppRefreshToken.kt` | `model/auth/AppRefreshToken.kt` |
| `entity/auth/AuthTenant.kt` | `model/auth/AuthTenant.kt` |
| `entity/auth/UserInstallBinding.kt` | `model/auth/UserInstallBinding.kt` |

**需要的文件:**
- `model/auth/*.kt` (7 个 data class)
- `modules/auth/repo/AuthJooqRepository.kt` — 合并 7 个旧 repo 为一个（或按职责拆 2-3 个）
- `modules/auth/service/AuthJooqService.kt` — 社交登录/匿名/refresh/exchange/logout/deleteAccount
- `bff/graphql/customer/AuthFetcher.kt` — query_me + 所有 auth mutation

**特殊处理:**
- `AuthService` 是最复杂的（13KB），包含 token 签发、社交登录验证、设备密钥管理
- Token 签发逻辑（`AuthJwtService`）不改，只改数据层
- `ProviderVerifier` / `WechatVerifier` 不改（外部验证逻辑）
- `MergeOnLoginListener`（匿名记录迁移）需要引用 ScanJooqRepository

---

### 3.2 IAP

**Entity → Model:**
| Jimmer | Domain Model |
|--------|-------------|
| `entity/iap/Subscription.kt` | `model/Subscription.kt` |
| `entity/iap/StoreNotification.kt` | `model/StoreNotification.kt` |

**需要的文件:**
- `model/Subscription.kt`, `model/StoreNotification.kt`
- `modules/iap/repo/IapJooqRepository.kt`
- `modules/iap/service/IapJooqService.kt` — verifyPurchase + webhook 处理
- `bff/graphql/customer/IapFetcher.kt` — mutation_verifyPurchase
- `bff/webhooks/WebhookController.kt` — 保留 REST，改注入新 service

**特殊处理:**
- `PurchaseVerifier` / `NotificationDecoder` 不改（外部接口逻辑）
- Webhook 保留 REST endpoint（Apple/Google 回调格式固定）

---

## Wave 4: AI 模块

### 4.1 AI (AgnesKey)

**Entity → Model:**
| Jimmer | Domain Model |
|--------|-------------|
| `entity/ai/AgnesKey.kt` | `model/AgnesKey.kt` |

**需要的文件:**
- `model/AgnesKey.kt`
- `modules/ai/repo/AgnesKeyJooqRepository.kt`
- `AgnesKeyStore` 改注入新 repo

**不改的:**
- `SpringAiScanRunner`, `ScanPrompt`, `AiConfig`, `AgnesChatClientFactory` — 只改数据源

---

## Wave 5: Cleanup — 删除 Jimmer

全部模块迁移完毕 + E2E 测试通过后：

**删除文件/目录:**
```
entity/                          # 整个目录（Jimmer interfaces）
src/main/dto/                    # 整个目录（Jimmer DTO files）
infra/jimmer/                    # 整个目录
infra/graphql/FetcherBuilder.kt  # 不再需要
infra/repo/                      # BaseCrudRepository (Jimmer 版)
infra/service/                   # BaseCrudService (Jimmer 版)
modules/*/repo/*Repository.kt   # 旧 Jimmer repo (非 Jooq 后缀的)
modules/*/service/*Service.kt   # 旧 Jimmer service (非 Jooq 后缀的)
modules/todo/TodoConfig.kt      # Jimmer 条件加载
modules/scan/ScanConfig.kt      # 同上
modules/feedback/FeedbackConfig.kt
modules/*/dto/                   # 旧手写 DTO
bff/customer/                    # 旧 REST controller（如果还有残留）
```

**删除依赖（build.gradle.kts）:**
```kotlin
// 删除
implementation("org.babyfish.jimmer:jimmer-spring-boot-starter:$jimmerVersion")
implementation("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")
// 删除 KSP plugin
id("com.google.devtools.ksp")
// 删除 KSP args
ksp { ... }
```

**重命名（去掉 Jooq 后缀）:**
```
TodoJooqRepository.kt → TodoRepository.kt
TodoJooqService.kt → TodoService.kt
... 所有模块同理
```

**更新文档:**
- `ARCHITECTURE.md` — 技术栈改 jOOQ，删除 Jimmer 相关描述
- `AGENTS.md` — 更新代码约定

---

## 每个模块的标准迁移步骤（checklist）

对每个模块 `X` 重复以下步骤：

- [ ] 1. 创建 `model/X.kt` (data class)
- [ ] 2. 创建 `modules/x/repo/XJooqRepository.kt` (继承 BaseCrudRepo + 模块特有方法)
- [ ] 3. 创建 `modules/x/service/XJooqService.kt` (注入新 repo + CacheAside)
- [ ] 4. 创建 `bff/graphql/customer/XFetcher.kt` (DGS DataFetcher + DataLoader)
- [ ] 5. 更新 DGS typeMapping (如果模块有 GraphQL output type)
- [ ] 6. `./gradlew :core-api:generateJava` (重新生成 DGS types)
- [ ] 7. `./gradlew :core-api:compileKotlin` (编译通过)
- [ ] 8. 写 E2E 测试验证

---

## 并行策略

```
Wave 1 (feedback + storage + app) — 3 个可并行
    ↓ (完成后)
Wave 2 (scan + collection) — 2 个可并行
    ↓ (完成后)  
Wave 3 (auth + iap) — 2 个可并行
    ↓ (完成后)
Wave 4 (ai) — 1 个
    ↓ (完成后)
Wave 5 (cleanup) — 一次性删除
```

每个 Wave 内的模块可以用 subagent 并行实施。Wave 之间有依赖需要串行。

---

## 预估工作量

| Wave | 模块 | 新增文件 | 预估时间 |
|------|------|----------|----------|
| 1 | feedback | 3 | 10 min |
| 1 | storage | 3 | 10 min |
| 1 | app | 4 | 15 min |
| 2 | scan | 5 | 30 min |
| 2 | collection | 5 | 25 min |
| 3 | auth | 10+ | 45 min |
| 3 | iap | 5 | 30 min |
| 4 | ai | 2 | 10 min |
| 5 | cleanup | 删除 | 15 min |
| — | E2E 测试 | — | 30 min |

**总计约 3.5 小时**（实际用 subagent 并行可缩短到 ~2 小时）

---

## 风险控制

1. **并存策略**: 新文件用 `Jooq` 后缀，旧代码不删，直到整体验证通过
2. **GraphQL schema 不变**: 客户端零影响
3. **DB schema 不变**: Flyway migrations 不需要改
4. **逐模块验证**: 每个 Wave 完成后跑编译 + 相关 E2E
5. **回退**: git branch，随时可以 revert
