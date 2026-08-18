# SvcCtxFactory 设计

> 日期: 2026-08-18

## 目标

统一 SvcCtx 的构建方式，通过 `SvcCtxFactory` 提供按场景的工厂方法（forApp、forAuthTenant 等），替代各 FacadeService 里散落的 `private fun svc()` 和 `SvcCtx.from()`。

## 组件

### ClusterRouter — 集群路由（接口）

```kotlin
interface ClusterRouter {
    /** 按 appId 路由 */
    fun forApp(appId: UUID): DSLContext
    /** 按 auth tenant 路由 */
    fun forTenant(tenantId: UUID): DSLContext
}

// 当前实现（单集群）
@Component
class DefaultClusterRouter : ClusterRouter {
    override fun forApp(appId: UUID) = SvcCtx.DEFAULT.dsl
    override fun forTenant(tenantId: UUID) = SvcCtx.DEFAULT.dsl
}
```

将来多集群时替换为从 `ClusterRegistry` 查找对应 DataSource 的 DSLContext。

### SvcCtxFactory — 工厂

```kotlin
@Component
class SvcCtxFactory(private val router: ClusterRouter) {

    /** 按 appId 路由（大多数模块用这个） */
    fun forApp(opCtx: OperationContext): SvcCtx = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: router.forApp(opCtx.mustGetAppId()),
        inTransaction = opCtx.inGlobalTx,
    )

    /** 按 auth tenant 路由 */
    fun forAuthTenant(opCtx: OperationContext, tenantId: UUID): SvcCtx = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: router.forTenant(tenantId),
        inTransaction = opCtx.inGlobalTx,
    )

    /** 默认（不需要路由参数，直接用 DEFAULT） */
    fun default(opCtx: OperationContext): SvcCtx = SvcCtx(
        op = opCtx,
        dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl,
        inTransaction = opCtx.inGlobalTx,
    )
}
```

**核心逻辑**：如果 `opCtx.globalTxDsl` 已存在（DataFetcher 开了全局事务），优先用它；否则走 router。

### 文件位置

```
infra/
├── db/
│   ├── SvcCtx.kt
│   ├── SvcCtxFactory.kt       # ← 新增
│   └── ClusterRouter.kt       # ← 新增（接口 + 默认实现）
```

## FacadeService 使用

```kotlin
@Service
class TodoFacadeService(
    private val svcCtxFactory: SvcCtxFactory,
    private val tx: TxRunner,
    private val todoInternal: TodoInternalService,
    ...
) {
    fun createTodo(opCtx: OperationContext, input: CreateTodoInput): UUID =
        tx.withTx(svcCtxFactory.forApp(opCtx)) { sc ->
            todoInternal.create(sc, input)
        }

    fun findById(opCtx: OperationContext, id: UUID): Todo? =
        ops.findById(svcCtxFactory.forApp(opCtx), id, repo::findById)
}

@Service
class AuthFacadeService(
    private val svcCtxFactory: SvcCtxFactory,
    private val tx: TxRunner,
    ...
) {
    fun login(opCtx: OperationContext, provider: String, req: ProviderLoginReq): LoginRes {
        val tenantId = resolveTenant(opCtx)
        return tx.withTx(svcCtxFactory.forAuthTenant(opCtx, tenantId)) { sc ->
            authInternal.login(sc, provider, req)
        }
    }

    private fun resolveTenant(opCtx: OperationContext): UUID {
        // 从 appConfig 查 tenantId
        ...
    }
}
```

## 替换清单

删除所有 FacadeService 里的 `private fun svc(opCtx)` / `private fun svcCtx(opCtx)` 方法，改为注入 `SvcCtxFactory` 并调用对应方法。

| 模块 | 当前 | 改为 |
|------|------|------|
| todo | `private fun svc(ctx) = SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl)` | `svcCtxFactory.forApp(ctx)` |
| scan/ai | 同上 | `svcCtxFactory.forApp(ctx)` |
| scanCollection | 同上 | `svcCtxFactory.forApp(ctx)` |
| storage | 同上 | `svcCtxFactory.forApp(ctx)` |
| cms | 同上 | `svcCtxFactory.forApp(ctx)` |
| app | 同上 | `svcCtxFactory.forApp(ctx)` |
| payment | 同上 | `svcCtxFactory.forApp(ctx)` |
| auth | 同上 | `svcCtxFactory.forAuthTenant(ctx, tenantId)` |

同时删除 `SvcCtx.from()` companion object 方法（如果有的话）。

## CrudServiceOps 适配

`CrudServiceOps` 当前接收 `SvcCtx` 参数。不变 — FacadeService 构建好 SvcCtx 后传入。

## 执行步骤

```
1. 创建 ClusterRouter 接口 + DefaultClusterRouter 实现
2. 创建 SvcCtxFactory
3. 各 FacadeService 注入 SvcCtxFactory，替换 private fun svc()
4. 删除 SvcCtx.from()（如果有）
5. 编译通过
```


---

## 目录结构调整

FacadeService 放 `service/` 根目录，Internal Service 放 `service/internal/`：

```
modules/todo/
├── service/
│   ├── TodoFacadeService.kt              # 对外入口
│   └── internal/
│       ├── TodoInternalService.kt        # Todo 实现
│       └── TodoItemInternalService.kt    # TodoItem 实现
└── repo/
    ├── TodoRepository.kt
    └── TodoItemRepository.kt
```

Package:
- `com.ifmix.api.core.modules.todo.service.TodoFacadeService`
- `com.ifmix.api.core.modules.todo.service.internal.TodoInternalService`

所有模块统一此结构。执行时和步骤 3（替换 svc() 为 svcCtxFactory）一起做。
