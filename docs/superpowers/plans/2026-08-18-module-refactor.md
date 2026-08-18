# 模块重构计划

> 日期: 2026-08-18

## 1. Internal Service 统一命名

将现有的 `XxxCommands.kt` / `XxxQueries.kt` 改为 `XxxInternalService.kt`（按 entity 拆文件）。

### 当前 → 目标

| 模块 | 当前文件 | 改为 |
|------|---------|------|
| todo | `TodoFacadeService.kt` 内含 TodoQueries/TodoCommands/TodoItemCommands | 拆出 `TodoInternalService.kt` + `TodoItemInternalService.kt` |
| scan | `ScanQueries.kt` + `ScanCommands.kt` | 合并为 `ScanInternalService.kt` |
| iap | `IapCommands.kt` + `IapWebhookHandler.kt` | 合并为 `PaymentInternalService.kt`（配合模块改名） |
| storage | `StorageCommands.kt` | 改为 `StorageInternalService.kt` |
| scanCollection | 逻辑在 FacadeService 里 | 拆出 `ScanCollectionInternalService.kt` |
| auth | 逻辑在 FacadeService 里 (417行) | 拆出 `AuthInternalService.kt` |
| app | 逻辑在 FacadeService 里 | 拆出 `AppConfigInternalService.kt` |
| feedback | 逻辑在 FacadeService 里 | 拆出 `CmsInternalService.kt`（配合模块改名） |

同时确保：
- Internal Service 只接收 `SvcCtx`，不注入 TxRunner
- 事务全部在 FacadeService 开启

---

## 2. scan 合并到 ai

scan（古物扫描）本质是 AI 模块的业务入口。当前拆开两个目录没有意义。

### 合并

```
# 当前
modules/ai/       → AgnesKeyStore, SpringAiScanRunner, ScanPrompt, ...
modules/scan/     → ScanFacadeService, ScanCommands, ScanQueries, repo/...

# 合并后
modules/ai/
├── repo/
│   ├── AgnesKeyRepository.kt
│   ├── ScanRecordRepository.kt
│   ├── ScanCollectionRepository.kt
│   └── ScanCollectionItemRepository.kt
├── service/
│   ├── AiFacadeService.kt              # 对外入口（原 ScanFacadeService + ScanCollectionFacadeService）
│   ├── ScanInternalService.kt          # scan CRUD
│   ├── ScanCollectionInternalService.kt
│   ├── AgnesKeyStore.kt                # key 管理
│   ├── SpringAiScanRunner.kt           # AI 调用
│   ├── ScanPrompt.kt
│   ├── AgnesChatClientFactory.kt
│   └── AiConfig.kt
```

GraphQL operation 命名不变（`query_scan_*`、`query_collection_*`）— operation 名和目录无关。

DGS Fetcher 保持 `bff/graphql/customer/scan/ScanFetcher.kt` 和 `collection/CollectionFetcher.kt` — fetcher 按 GraphQL module 组织，不跟 modules 目录走。

---

## 3. iap → payment

```
# 当前
modules/iap/

# 改后
modules/payment/
├── repo/
│   ├── SubscriptionRepository.kt
│   └── StoreNotificationRepository.kt
├── service/
│   ├── PaymentFacadeService.kt
│   ├── PaymentInternalService.kt        # verifyPurchase
│   └── PaymentWebhookHandler.kt         # handleNotification
├── PurchaseVerifier.kt
├── NotificationDecoder.kt
├── Entitlement.kt
└── PaymentConfig.kt                     # 原 IapConfig
```

Package: `com.ifmix.api.core.modules.payment.*`

GraphQL operation 命名保持 `mutation_iap_verifyPurchase`（不改 — 客户端已用）或改为 `mutation_payment_verifyPurchase`（如果客户端还没上线可以改）。

---

## 4. feedback → cms

```
# 当前
modules/feedback/

# 改后
modules/cms/
├── repo/
│   └── FeedbackRepository.kt
├── service/
│   ├── CmsFacadeService.kt
│   └── FeedbackInternalService.kt
```

Package: `com.ifmix.api.core.modules.cms.*`

将来 cms 模块可以扩展更多内容管理功能（公告、FAQ 等），feedback 是其第一个 entity。

GraphQL operation: `mutation_feedback_submitFeedback` 保持不变或改为 `mutation_cms_submitFeedback`。

---

## 执行顺序

```
1. Internal Service 重命名（不涉及模块移动，改名 + 改 import）
2. scan 合并到 ai（移动文件 + 改 package + 改 import）
3. iap → payment（改名 + 改 package + 改 import）
4. feedback → cms（改名 + 改 package + 改 import）
5. 每步编译通过
```

## 注意

- entity/ 目录下的 model 文件**不跟着 modules 改名**（entity 按领域概念组织，不按 module 组织）
- DGS typeMapping 里的包路径如果引用了 entity，确认不受影响
- Fetcher 目录 `bff/graphql/customer/` 按 GraphQL module 组织，不跟 modules 走
