# 剩余整理任务: RepoContext 从 OperationContext 剥离

> 日期: 2026-08-18
> 已完成: DataFetcher 分目录 / GraphQL enum→Int / 枚举常量嵌套 object

## 目标

Service 自己决定用哪个集群的 `RepoContext`，DataFetcher 不再构建 repoCtx。为将来不同服务不同路由逻辑（auth 按 tenant、scan 按 appId）预留。

## 当前状态

```kotlin
// OperationContext 持有 repoCtx
data class OperationContext(..., val repoCtx: RepoContext = RepoContext.DEFAULT)

// OperationContextProvider 构建时放入 repoCtx
return OperationContext(..., repoCtx = RepoContext.DEFAULT)

// Service 通过 ctx.repoCtx 获取
repo.findById(ctx.repoCtx, appId, id)

// TxRunner 从 OperationContext 取 repoCtx
fun <R> withTx(ctx: OperationContext, ..., body: (OperationContext) -> R): R {
    ctx.repoCtx.dsl.transactionResult { ... }
}
```

## 目标状态

```kotlin
// OperationContext 不再有 repoCtx
data class OperationContext(...) // 只有请求信息 + readCache/isMutation

// Service 自己持有获取 repoCtx 的方式
@Service
class TodoService(private val tx: TxRunner, ...) {
    // 当前单集群; 将来换成: clusterRouter.forApp(appId)
    private val repoCtx get() = RepoContext.DEFAULT

    fun createTodo(ctx: OperationContext, input: CreateTodoInput): UUID {
        val appId = ctx.mustGetAppId()
        return tx.withTx(repoCtx) { rc ->
            // rc = 事务内的 RepoContext
            repo.insert(rc, Todo(...))
            itemRepo.batchInsert(rc, items)
            id
        }
    }

    fun findById(ctx: OperationContext, id: UUID): Todo? {
        // CrudServiceOps 内部用 repoCtx (从 factory 配置的 provider 获取)
        return ops.findById(ctx, id, repo::findById)
    }
}

// TxRunner 接收 RepoContext
fun <R> withTx(repoCtx: RepoContext, propagation: TxPropagation = REQUIRED, body: (RepoContext) -> R): R

// CrudServiceOps factory 接收 repoCtxProvider
factory.create(type, "prefix", { it.id }, repoCtxProvider = { RepoContext.DEFAULT })
```

## 改动文件清单

### 基础设施 (先改)

| 文件 | 改动 |
|------|------|
| `infra/http/RequestContext.kt` | 去掉 `val repoCtx` 字段 + 去掉 `import RepoContext` |
| `infra/graphql/OperationContextProvider.kt` | 去掉 `repoCtx = RepoContext.DEFAULT` 那行 |
| `infra/jooq/TxRunner.kt` | `withTx` 改为接收 `repoCtx: RepoContext`，body 参数改为 `(RepoContext) -> R` |
| `infra/service/CrudServiceOps.kt` | factory `create` 加 `repoCtxProvider: () -> RepoContext` 参数；`CrudServiceOps` 构造加 `repoCtxProvider`；内部 `ctx.repoCtx` 改为 `repoCtxProvider()` |

### Service 层 (逐个改，注意 withTx lambda 变化)

**通用模式:**
```kotlin
// 改前
fun xxx(ctx: OperationContext, ...) = tx.withTx(ctx) { txCtx ->
    val rc = txCtx.repoCtx
    repo.doSomething(rc, ...)
    // txCtx.appId 等业务字段也能访问（因为 txCtx 是 OperationContext）
}

// 改后
fun xxx(ctx: OperationContext, ...) = tx.withTx(repoCtx) { rc ->
    repo.doSomething(rc, ...)
    // 业务字段从外部 ctx 闭包取: ctx.appId, ctx.mustGetAppId()
}
```

| 文件 | 关键点 |
|------|--------|
| `modules/todo/service/TodoService.kt` | 简单，加 `private val repoCtx get() = RepoContext.DEFAULT`，withTx body 里 `txCtx` → `rc`，业务字段从外层 `ctx` 闭包取 |
| `modules/todo/service/TodoItemService.kt` | 同上 |
| `modules/scan/service/ScanService.kt` | 同上，注意 `ctx.mustGetAppId()` 在 lambda 外取好再传入 |
| `modules/scan/service/ScanCollectionService.kt` | 没用 withTx，只有 `ctx.repoCtx` → `repoCtx` |
| `modules/feedback/service/FeedbackService.kt` | 没用 withTx，只有 `ctx.repoCtx` → `repoCtx` |
| `modules/app/service/AppConfigService.kt` | 没用 withTx，只有 `ctx.repoCtx` → `repoCtx` |
| `modules/iap/service/IapService.kt` | 有 withTx，lambda 内引用 `ctx` 业务字段较多，改时注意闭包 |
| `modules/auth/service/AuthService.kt` | **最复杂**（13K），5 个 withTx 调用，lambda 内大量引用 `ctx.appId`/`ctx.installId`/`ctx.userId`。改时先在 lambda 外 `val appId = ctx.mustGetAppId()` 等提取出来 |
| `modules/auth/MergeOnLoginListener.kt` | 引用 `e.ctx.repoCtx`，改为 `RepoContext.DEFAULT` |

### Fetcher 层

| 文件 | 改动 |
|------|------|
| `bff/graphql/customer/storage/StorageFetcher.kt` | `ctx.repoCtx` → `RepoContext.DEFAULT`（或改为通过 service 间接调用） |

### DataLoader

TodoItemsDataLoader 里的 `RepoContext.DEFAULT` 不变（已经是直接引用）。

## 执行建议

1. 先改 4 个 infra 文件（TxRunner/CrudServiceOps/OperationContext/Provider）
2. 再改简单的 service（Todo/TodoItem/Feedback/ScanCollection/AppConfig）— 只是 `ctx.repoCtx` → `repoCtx`
3. 改 ScanService 和 IapService（有 withTx）
4. 最后改 AuthService（最复杂，逐个 withTx lambda 检查）
5. 改 StorageFetcher + MergeOnLoginListener
6. 每步改完编译通过再继续

## AuthService 详细改动示例

```kotlin
// 改前 (AuthService.kt line ~126)
fun loginWithIdToken(ctx: OperationContext, provider: String, req: ProviderLoginReq): LoginRes {
    return tx.withTx(ctx) { txCtx ->
        val rc = txCtx.repoCtx
        val appId = txCtx.appId!!
        val identity = identityRepo.findOrCreate(rc, appId, ...)
        ...
    }
}

// 改后
fun loginWithIdToken(ctx: OperationContext, provider: String, req: ProviderLoginReq): LoginRes {
    val appId = ctx.mustGetAppId()
    val installId = ctx.installId
    return tx.withTx(repoCtx) { rc ->
        val identity = identityRepo.findOrCreate(rc, appId, ...)
        ...
    }
}
```

关键：lambda 外提取业务字段 → lambda 内只用 `rc`（RepoContext）和闭包捕获的业务变量。
