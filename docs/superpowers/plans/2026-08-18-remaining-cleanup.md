# 剩余整理任务

> 日期: 2026-08-18

## 1. DataFetcher 按模块分子目录

当前平铺在 `bff/graphql/customer/` 下，改为：

```
bff/graphql/customer/
├── todo/
│   ├── TodoFetcher.kt
│   └── TodoItemsDataLoader.kt
├── scan/
│   └── ScanFetcher.kt
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

步骤：创建子目录 → 移动文件 → 改 package → 编译通过

## 2. GraphQL enum → Int

`feedback.graphqls` 里 `category` 还用 GraphQL enum，需要改成 `Int!` + schema 注释：

```graphql
# 改前
category: FeedbackCategory!

# 改后
"反馈分类。100=LIKED, 200=PRICE_TOO_HIGH, 210=PRICE_TOO_LOW, 220=PRICE_MISSING, 300=WRONG_IDENTIFICATION, 400=FEATURE_REQUEST, 410=MORE_RECOMMENDATIONS"
category: Int!
```

同时检查所有 `.graphqls` 里是否还有其他 GraphQL enum 用作 output type，全改 Int。input 里的 enum 也改 Int（灰度安全）。删除对应的 `enum FeedbackCategory { ... }` 定义。

## 3. 枚举常量改为嵌套 object

当前 `model/enums/` 下是独立 enum class（有 UNKNOWN 兜底）。按约定改为 model class 的嵌套 object 常量：

```kotlin
// 改前: model/enums/ScanStatus.kt
enum class ScanStatus(override val code: Int) : CodedEnum {
    UNKNOWN(0), PENDING(100), ...
}

// 改后: model/scan/ScanRecord.kt 里
data class ScanRecord(val status: Int, ...) {
    object Status {
        const val PENDING = 100
        const val PROCESSING = 110
        const val COMPLETED = 200
        const val FAILED = 300
        fun isTerminal(code: Int) = code >= 200
    }
}
```

跨模块共享的（如 Platform, Tier）放 `model/shared/`：
```kotlin
// model/shared/Platforms.kt
object Platforms {
    const val APPLE = 100
    const val GOOGLE = 200
}

// model/shared/Tiers.kt
object Tiers {
    const val FREE = 100
    const val PRO = 200
    const val ENTERPRISE = 300
}
```

步骤：
1. 在 model class 里加嵌套 object
2. 共享的放 `model/shared/`
3. 全局替换引用（`ScanStatus.COMPLETED.code` → `ScanRecord.Status.COMPLETED`）
4. 删除 `model/enums/` 目录
5. 删除 `CodedEnum` 接口
6. 编译通过

## 4. 清理 AuthFetcher 的 exchange/refresh field 名

当前 fetcher annotation 里的 field 要和 schema 对齐：
- `mutation_auth_exchangeToken`
- `mutation_auth_refreshToken`

检查 `AuthFetcher.kt` 的 `@DgsMutation(field=...)` 是否已匹配 schema。

---

## 执行顺序

1 → 2 → 3 → 4，串行（每步都改 import，并行会冲突）
