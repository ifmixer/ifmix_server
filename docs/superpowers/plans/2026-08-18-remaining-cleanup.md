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

`feedback.graphqls` 里 `category` 还用 GraphQL enum，需要改成 `Int!` + schema 注释。

检查所有 `.graphqls` 里是否还有其他 GraphQL enum 用作 output/input type，全改 Int。删除对应的 enum 定义。

## 3. 枚举常量改为嵌套 object

当前 `model/enums/` 下是独立 enum class。按约定改为 model class 的嵌套 object 常量。跨模块共享的放 `model/shared/`。

## 4. RepoContext 从 OperationContext 剥离

**目标**: Service 自己决定用哪个集群，DataFetcher 不再构建 repoCtx。

### 改动

**OperationContext** — 去掉 `repoCtx` 字段：
```kotlin
data class OperationContext(
    val appId: UUID?, val installId: UUID?, val userId: UUID?,
    // ... 其他请求信息
    val isMutation: Boolean, val readCache: Boolean,
    // 不再有 repoCtx
)
```

**OperationContextProvider** — 不再构建 repoCtx。

**TxRunner** — 改为接收 `RepoContext` 而非从 `OperationContext` 取：
```kotlin
fun <R> withTx(repoCtx: RepoContext, propagation: TxPropagation = REQUIRED, body: (RepoContext) -> R): R
```

**CrudServiceOps** — factory 接收 `repoCtxProvider: () -> RepoContext`：
```kotlin
factory.create(type, "prefix", { it.id }, repoCtxProvider = { RepoContext.DEFAULT })
```
内部用 `repoCtxProvider()` 替代 `ctx.repoCtx`。

**每个 Service** — 加一个获取 repoCtx 的方式：
```kotlin
@Service
class TodoService(...) {
    // 当前单集群，直接用 DEFAULT
    // 将来: 根据 ctx.appId 从 ClusterRouter 路由
    private val repoCtx get() = RepoContext.DEFAULT

    fun createTodo(ctx: OperationContext, input: ...) = tx.withTx(repoCtx) { rc ->
        repo.insert(rc, todo)
        // rc 是事务内的 RepoContext
    }
}
```

注意 `withTx` 的 lambda 参数现在是 `RepoContext`（不是 `OperationContext`），所以 lambda 内访问 `ctx.appId` 等业务字段需从外部闭包捕获。

**StorageFetcher** — 也需要 repoCtx（它直接调 repo）。改为注入 service 而非直接调 repo，或用 `RepoContext.DEFAULT`。

### 注意事项

- **AuthService 最复杂** — 它的 `withTx` lambda 里同时用 `ctx`（业务字段）和 `ctx.repoCtx`（数据访问）。改后 lambda 参数是 `rc: RepoContext`，业务字段通过闭包从外层 `ctx` 取。
- **MergeOnLoginListener** — 也引用 `ctx.repoCtx`，需要改。
- 改完后全部 `ctx.repoCtx` 引用应该消失。

### 步骤
1. 改 `OperationContext` 去掉 `repoCtx`
2. 改 `OperationContextProvider` 去掉 repoCtx 构建
3. 改 `TxRunner` 签名
4. 改 `CrudServiceOps` + factory
5. 逐个改 service（TodoService → ScanService → AuthService → IapService → 其他）
6. 改 StorageFetcher
7. 编译通过

---

## 执行顺序

1 → 2 → 3 → 4，串行

