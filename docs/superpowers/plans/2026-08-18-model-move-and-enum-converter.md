# Model 迁移到 modules + Enum 全链路自动转换

> 日期: 2026-08-18

## 任务 1: model/ 按模块组织子目录

保持 `model/` 在顶层（将来可独立为 `core-domain` module），但按模块分子目录：

```
model/
├── todo/
│   ├── Todo.kt
│   └── TodoItem.kt
├── scan/
│   ├── ScanRecord.kt
│   ├── ScanCollection.kt
│   └── ScanCollectionItem.kt
├── auth/
│   ├── AppUser.kt
│   ├── AuthIdentity.kt
│   ├── AuthProviderIdentity.kt
│   ├── AuthDeviceSecret.kt
│   ├── AppRefreshToken.kt
│   ├── AuthTenant.kt
│   └── UserInstallBinding.kt
├── iap/
│   ├── Subscription.kt
│   └── StoreNotification.kt
├── feedback/
│   └── Feedback.kt
├── storage/
│   └── UploadRecord.kt
├── ai/
│   └── AgnesKey.kt
├── app/
│   ├── AppInfo.kt
│   └── AppConfigRevision.kt
└── enums/
    ├── Platform.kt
    ├── ScanStatus.kt
    ├── Tier.kt
    ├── FeedbackCategory.kt
    └── SubscriptionState.kt
```

package 对应：`com.ifmix.api.core.model.todo.Todo`、`com.ifmix.api.core.model.enums.Platform` 等。

## 任务 1.1: bff/graphql/ 按模块组织目录

当前 DataFetcher 全部平铺在 `bff/graphql/customer/` 下，改为按模块分子目录：

```
bff/graphql/customer/
├── todo/
│   ├── TodoFetcher.kt
│   └── TodoItemsDataLoader.kt
├── scan/
│   ├── ScanFetcher.kt
│   └── ScanCollectionItemDataLoader.kt
├── collection/
│   └── CollectionFetcher.kt
├── auth/
│   └── AuthFetcher.kt
├── iap/
│   └── IapFetcher.kt
├── storage/
│   └── StorageFetcher.kt
└── feedback/
    └── FeedbackFetcher.kt
```

package 对应：`com.ifmix.api.core.bff.graphql.customer.todo.TodoFetcher` 等。

步骤（任务 1 + 1.1 合并执行）：
1. 创建 model 子目录 + 移动文件 + 改 package
2. 创建 fetcher 子目录 + 移动文件 + 改 package
3. 全局更新 import 路径
4. DGS typeMapping 更新包路径
5. 编译通过

---

## 任务 2: 枚举设计 — GraphQL 全 Int，内部 enum 辅助

### 设计原则

- **GraphQL input/output 全部用 Int** — 零兼容问题，灰度/多版本/透传安全
- **Schema 注释写清含义** — 自文档
- **Kotlin 内部用 enum** — 代码可读，`when` 穷举，但只在 service 内部用
- **Model 字段类型 Int** — 保留原始值不丢

### GraphQL Schema

```graphql
type ScanRecord {
    "扫描状态。100=PENDING, 110=PROCESSING, 200=COMPLETED, 300=FAILED"
    status: Int!
    "平台。100=APPLE, 200=GOOGLE"
    platform: Int
}

input UpdateScanSetInput {
    "扫描状态。100=PENDING, 110=PROCESSING, 200=COMPLETED, 300=FAILED"
    status: Int
}
```

### Kotlin 内部 enum（辅助，不暴露到 GraphQL）

```kotlin
// model/enums/ScanStatus.kt
enum class ScanStatus(val code: Int) {
    PENDING(10),
    PROCESSING(20),
    COMPLETED(30),
    FAILED(40);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): ScanStatus? = byCode[code]
    }
}

// model/enums/Platform.kt
enum class Platform(val code: Int) {
    APPLE(10),
    GOOGLE(20);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): Platform? = byCode[code]
    }
}
```

### 用法

```kotlin
// Service 内部 — 可读
if (ScanStatus.fromCode(record.status) == ScanStatus.COMPLETED) { ... }

// 或直接比较 code
if (record.status == ScanStatus.COMPLETED.code) { ... }

// Model — int
data class ScanRecord(val status: Int, ...)

// Fetcher — 透传，不转换
// GraphQL 直接返回 model 的 int 字段
```

### 不需要的东西

- ❌ GraphQL enum 定义（status/platform/tier 等业务枚举）
- ❌ DGS typeMapping for enum
- ❌ jOOQ enum Converter / forcedType
- ❌ Input enum → int 转换函数
- ❌ UNKNOWN 兜底

### 需要做的

1. GraphQL schema：所有业务枚举字段改为 `Int!`，加文档注释
2. 创建 `model/enums/` 下的 Kotlin enum（纯内部辅助）
3. Model 的 status/platform/tier 等字段类型为 `Int`
4. DGS `generateJava` 重新生成（enum 类型消失，变成 Int）
5. Fetcher 层去掉 enum 转换代码（直接透传 int）
6. 编译通过

### 涉及的枚举

| 枚举 | GraphQL | Model 字段 | Kotlin enum（内部） |
|------|---------|-----------|-------------------|
| ScanStatus | `Int!` | `status: Int` | `ScanStatus` |
| Platform | `Int!` | `platform: Int` | `Platform` |
| Tier | `Int!` | `tier: Int` | `Tier` |
| FeedbackCategory | `Int!` | `category: Int` | `FeedbackCategory` |
| SubscriptionState | `Int!` | `state: Int` | `SubscriptionState` |

---

## 执行顺序

```
1. model/ 按模块建子目录 + 移动文件 + 改 package + 更新 import
2. 创建 model/enums/ 常量对象 (ScanStatuses, Platforms, Tiers, etc.)
3. 创建 toCode() 扩展函数 (input enum → int)
4. GraphQL schema: output enum → Int! + 文档注释
5. GraphQL schema: input 保留 enum (加 Input 后缀)
6. DGS typeMapping 更新 model 包路径（output type → model data class）
7. ./gradlew :core-api:generateJava
8. 编译通过
```

---

## 架构约束更新

追加到 ARCHITECTURE.md：

- **枚举 Input 用 GraphQL enum，Output 用 Int** — input 约束合法值，output int 透传不丢数据
- **领域层（model）枚举字段类型为 Int** — 保留原始值，支持灰度/多版本并行
- **服务端内部用常量对象** — `ScanStatuses.COMPLETED`，不用 magic number
- **Input enum → int 通过 `toCode()` 扩展函数转换** — DGS codegen 生成 enum，手写 toCode
- **不需要 jOOQ enum Converter** — DB SMALLINT → model Int 天然对齐
- **不需要 UNKNOWN 兜底** — int 不丢信息
- **model/ 保持顶层按模块分子目录** — 将来可拆为独立 core-domain module
