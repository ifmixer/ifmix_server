# CrudRepoOps 实例级配置

> 日期: 2026-08-18

## 目标

将 `CrudRepoOps` 从无状态工具 bean（每个方法都传 table/idField/appIdField/type/deletedAtField）改为实例级配置（Repo 初始化时绑定一次，方法只传 ctx + 业务参数）。

## 当前

```kotlin
@Repository
class TodoRepository(private val crud: CrudRepoOps) {
    fun findById(ctx: SvcCtx, appId: UUID, id: UUID) =
        crud.findById(ctx, CORE_TODO, CORE_TODO.APP_ID, CORE_TODO.ID, appId, id, Todo::class.java, CORE_TODO.DELETED_AT)
}
```

## 目标

```kotlin
@Repository
class TodoRepository(factory: CrudRepoOpsFactory) {
    private val crud = factory.create(
        table = CORE_TODO,
        idField = CORE_TODO.ID,
        appIdField = CORE_TODO.APP_ID,
        type = Todo::class.java,
        deletedAtField = CORE_TODO.DELETED_AT,
    )

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID) = crud.findById(ctx, appId, id)
    fun insert(ctx: SvcCtx, todo: Todo) = crud.insert(ctx, todo)
    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID) = crud.deleteById(ctx, appId, id)
}
```

## 设计

### CrudRepoOpsFactory

```kotlin
@Component
class CrudRepoOpsFactory {
    fun <T : Any> create(
        table: Table<*>,
        idField: TableField<*, *>,
        appIdField: TableField<*, *>? = null,
        type: Class<T>,
        deletedAtField: TableField<*, *>? = null,
    ): CrudRepoOps<T> = CrudRepoOps(table, idField, appIdField, type, deletedAtField)
}
```

### CrudRepoOps<T>（实例级）

```kotlin
class CrudRepoOps<T : Any>(
    private val table: Table<*>,
    private val idField: TableField<*, *>,
    private val appIdField: TableField<*, *>?,
    private val type: Class<T>,
    private val deletedAtField: TableField<*, *>?,
) {
    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): T?
    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<T>
    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<T>
    fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean
    fun insert(ctx: SvcCtx, model: T)
    fun batchInsert(ctx: SvcCtx, models: List<T>)
    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean
    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int

    // 不需要 appId 的版本（某些表没有 appId）
    fun findById(ctx: SvcCtx, id: UUID): T?
}
```

对于没有 `appIdField` 的表（如 `AuthTenant`），`appIdField = null`，提供不带 appId 参数的方法重载。

### 保留原有的无状态方法

对于特殊查询（自定义 condition、partialUpdate、JSONB 处理），repo 仍然直接用 `ctx.dsl` 手写。`CrudRepoOps` 只覆盖通用 CRUD。

## 改动文件

| 文件 | 改动 |
|------|------|
| `infra/jooq/CrudRepoOps.kt` | 改为泛型实例类 `CrudRepoOps<T>`，方法去掉 table/id/app/type/deleted 参数 |
| `infra/jooq/CrudRepoOpsFactory.kt` | 新建，`@Component` 工厂 |
| 所有 `modules/*/repo/*.kt` | 注入改为 `factory: CrudRepoOpsFactory`，初始化 `private val crud = factory.create(...)`，方法调用简化 |

## 涉及的 Repo（全部）

- `modules/todo/repo/TodoRepository.kt`
- `modules/todo/repo/TodoItemRepository.kt`
- `modules/ai/repo/AgnesKeyRepository.kt`
- `modules/ai/repo/ScanRecordRepository.kt`
- `modules/ai/repo/ScanCollectionRepository.kt`
- `modules/ai/repo/ScanCollectionItemRepository.kt`
- `modules/auth/repo/AppUserRepository.kt`
- `modules/auth/repo/AuthIdentityRepository.kt`
- `modules/auth/repo/AuthProviderIdentityRepository.kt`
- `modules/auth/repo/AuthTenantRepository.kt`
- `modules/auth/repo/AuthDeviceSecretRepository.kt`
- `modules/auth/repo/AppRefreshTokenRepository.kt`
- `modules/auth/repo/UserInstallBindingRepository.kt`
- `modules/payment/repo/SubscriptionRepository.kt`
- `modules/payment/repo/StoreNotificationRepository.kt`
- `modules/storage/repo/UploadRecordRepository.kt`
- `modules/cms/repo/FeedbackRepository.kt`
- `modules/app/repo/AppConfigRepository.kt`
- `modules/app/repo/AppInfoRepository.kt`

## 步骤

```
1. 创建 CrudRepoOpsFactory
2. 改 CrudRepoOps 为泛型实例类（保留旧的无状态方法做 @Deprecated 过渡，或直接删）
3. 逐个改 repo（注入 factory，初始化 crud，简化方法调用）
4. 编译通过
```
