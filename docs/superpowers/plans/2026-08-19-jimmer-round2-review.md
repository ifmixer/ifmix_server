# Jimmer 迁移 — 第二轮 Review 修复计划

> 执行者：Claude Code
> 日期：2026-08-19 23:10
> 前提：编译只剩 1 个错误（TodoRepository.kt:41 `execute`），主体已通过

---

## 问题 1: [HIGH] demo 模块只剩 repo，service 层丢失

**现状**: `modules/demo/` 只有 `repo/` 目录（TodoRepository + TodoItemRepository），没有 service 和 DataFetcher。

**对比 jOOQ 版**: jOOQ 版里没有 demo 模块（它叫别的名字或被内联到 bff 里了），但 BFF 层有 Todo 相关的 DataFetcher。

**需要恢复的文件**（参考 ARCHITECTURE.md 中的 Service 分层约定）:

```
modules/demo/
├── repo/
│   ├── TodoRepository.kt        ✅ 已有
│   └── TodoItemRepository.kt    ✅ 已有
└── service/
    ├── DemoModuleService.kt     ❌ 缺失 — 对外入口
    └── internal/
        ├── TodoEntityService.kt      ❌ 缺失 — Todo 业务逻辑
        └── TodoItemEntityService.kt  ❌ 缺失 — TodoItem 业务逻辑
```

**同时检查 BFF 层**: `bff/graphql/customer/` 下应该有 `todo/` 或 `demo/` 的 DataFetcher。当前没有，需要补。

**实现参考**: 参照 ARCHITECTURE.md 中的 "完整 Demo — Todo 模块" 章节，适配 Jimmer。

---

## 问题 2: [HIGH] BaseRepo 功能太少 — 需要 batch 和 findByCursor

**现状** `BaseAppCrudRepository` 只有: `findById`, `findByIds`(逐个查，低效), `save`, `deleteById`, `exists`

**缺失的关键方法**:

### 2.1 `findByIds` 应该批量查询而不是 N 次单查

```kotlin
// ❌ 当前: O(N) 次数据库调用
open fun findByIds(ctx: SvcCtx, appId: UUID, ids: List<UUID>): List<E> {
    if (ids.isEmpty()) return emptyList()
    return ids.mapNotNull { id -> findById(ctx, appId, id) }
}

// ✅ 修复: 单次批量查询
open fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<E> {
    if (ids.isEmpty()) return emptyList()
    return sql.createQuery(entityType) {
        where(table.get<UUID>("appId") eq appId)
        where(table.getId<UUID>() valueIn ids)
        select(table)
    }.execute()
}
```

### 2.2 新增 `findByCursor` — 通用游标分页

```kotlin
open fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<E> {
    return sql.createQuery(entityType) {
        where(table.get<UUID>("appId") eq appId)
        cursor?.let { where(table.getId<UUID>() lt it) }
        orderBy(table.getId<UUID>().desc())
        select(table)
    }.limit(limit).execute()
}
```

### 2.3 新增 `batchSave` — 批量插入

```kotlin
open fun batchSave(ctx: SvcCtx, entities: List<E>): List<E> {
    if (entities.isEmpty()) return emptyList()
    return sql.entities.saveEntities(entities).simpleResults.map { it.modifiedEntity }
}
```

### 2.4 新增 `deleteByIds` — 批量删除

```kotlin
open fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int {
    if (ids.isEmpty()) return 0
    return sql.createDelete(entityType) {
        where(table.get<UUID>("appId") eq appId)
        where(table.getId<UUID>() valueIn ids)
    }.execute()
}
```

**BaseCrudRepository（无 appId）也需要对应的批量方法**，签名去掉 appId 参数即可。

---

## 问题 3: [HIGH] BaseRepo/BaseSvc 改为组合模式（对齐 jOOQ 版 CrudRepoOps）

**jOOQ 版的组合模式**:
```kotlin
// jOOQ: 每个 Repo 注入 CrudRepoOpsFactory，创建绑定到自己表的 ops 实例
@Repository
class FeedbackRepository(factory: CrudRepoOpsFactory) {
    private val crud = factory.create(table = ..., idField = ..., appIdField = ..., type = ...)
    fun insert(ctx, entity) = crud.insert(ctx, entity)
    fun findById(ctx, appId, id) = crud.findById(ctx, appId, id)
}
```

**为什么不直接用继承**: Jimmer 版其实用继承也可以（当前已经在用），因为 Jimmer 的 KSqlClient 是全局单例，不像 jOOQ 的 DSLContext 需要动态路由。但你说了要改组合，那方案如下：

### Jimmer 组合方案

```kotlin
// ===== infra/repo/CrudRepoOps.kt =====
/**
 * Jimmer 版通用 CRUD 操作（组合注入，替代继承）。
 * 功能对齐 jOOQ 版 CrudRepoOps。
 */
class CrudRepoOps<E : Any>(
    private val sql: KSqlClient,
    private val entityType: KClass<E>,
    private val hasAppId: Boolean = true,
) {
    fun findById(ctx: SvcCtx, id: UUID): E? =
        sql.entities.findById(entityType, id)

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): E? {
        require(hasAppId)
        return sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): List<E> {
        if (ids.isEmpty()) return emptyList()
        require(hasAppId)
        return sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
            select(table)
        }.execute()
    }

    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int): List<E> {
        require(hasAppId)
        return sql.createQuery(entityType) {
            where(table.get<UUID>("appId") eq appId)
            cursor?.let { where(table.getId<UUID>() lt it) }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit).execute()
    }

    fun save(ctx: SvcCtx, entity: E): E =
        sql.entities.save(entity).modifiedEntity

    fun batchSave(ctx: SvcCtx, entities: List<E>): List<E> {
        if (entities.isEmpty()) return emptyList()
        return sql.entities.saveEntities(entities).simpleResults.map { it.modifiedEntity }
    }

    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID): Boolean {
        require(hasAppId)
        return sql.createDelete(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() eq id)
        }.execute() > 0
    }

    fun deleteByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        require(hasAppId)
        return sql.createDelete(entityType) {
            where(table.get<UUID>("appId") eq appId)
            where(table.getId<UUID>() valueIn ids)
        }.execute()
    }

    fun exists(ctx: SvcCtx, appId: UUID, id: UUID): Boolean =
        findById(ctx, appId, id) != null
}

// ===== infra/repo/CrudRepoOpsFactory.kt =====
@Component
class CrudRepoOpsFactory(private val sql: KSqlClient) {
    fun <E : Any> create(entityType: KClass<E>, hasAppId: Boolean = true): CrudRepoOps<E> =
        CrudRepoOps(sql, entityType, hasAppId)
}
```

### Repo 使用方式（组合）

```kotlin
@Repository
class TodoRepository(factory: CrudRepoOpsFactory) {
    private val crud = factory.create(Todo::class)

    // 委托通用操作
    fun findById(ctx: SvcCtx, appId: UUID, id: UUID) = crud.findById(ctx, appId, id)
    fun findByIds(ctx: SvcCtx, appId: UUID, ids: Collection<UUID>) = crud.findByIds(ctx, appId, ids)
    fun findByCursor(ctx: SvcCtx, appId: UUID, cursor: UUID?, limit: Int) = crud.findByCursor(ctx, appId, cursor, limit)
    fun save(ctx: SvcCtx, entity: Todo) = crud.save(ctx, entity)
    fun batchSave(ctx: SvcCtx, entities: List<Todo>) = crud.batchSave(ctx, entities)
    fun deleteById(ctx: SvcCtx, appId: UUID, id: UUID) = crud.deleteById(ctx, appId, id)
    fun exists(ctx: SvcCtx, appId: UUID, id: UUID) = crud.exists(ctx, appId, id)

    // 特殊查询
    fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, title: String?, done: Boolean?) {
        ...
    }
}
```

### 迁移策略

**两种路径，你选：**

- **A（渐进式，推荐）**: 保留现有继承结构 `BaseAppCrudRepository`，只是在里面**补齐缺失方法**（findByCursor、batchSave、deleteByIds、修复 findByIds）。等编译全通过后再重构为组合。
- **B（一步到位）**: 现在就改为组合模式，删除 BaseCrudRepository / BaseAppCrudRepository，每个 Repo 注入 `CrudRepoOpsFactory` 创建 `crud` 实例。需要改所有 Repo 文件。

我建议 **方案 A**，原因：当前还有 1 个编译错误和 demo 模块缺失，先稳定再重构。

---

## 问题 4: [MEDIUM] TodoRepository.partialUpdate 用了 raw SQL hack

**现状**:
```kotlin
ctx.sql.execute("UPDATE core_todo SET ${setClauses.joinToString(", ")} WHERE app_id = ? AND id = ?", ...)
```

这不对。`KSqlClient.execute()` 不接受这种参数。Jimmer 的正确写法：

```kotlin
fun partialUpdate(ctx: SvcCtx, appId: UUID, id: UUID, title: String?, done: Boolean?) {
    if (title == null && done == null) return
    sql.createUpdate(Todo::class) {
        where(table.appId eq appId)
        where(table.id eq id)
        title?.let { set(table.title, it) }
        done?.let { set(table.done, it) }
    }.execute()
}
```

注意：需要 import KSP 生成的扩展属性 `com.ifmix.api.core.entity.demo.title` 和 `com.ifmix.api.core.entity.demo.done`。

---

## 问题 5: [MEDIUM] 最后 1 个编译错误

`TodoRepository.kt:41` — 如果按问题 4 重写 partialUpdate，这个错误自然消失。

---

## 问题 6: [LOW] DataFetcher 中的 ScanRecordsDataLoader 仍然有问题

上一轮 review 提到过，确认是否已修。如果未修，参考上份文档的方案。

---

## 执行顺序

1. 修 `BaseAppCrudRepository`：补 `findByCursor`、`batchSave`、`deleteByIds`，修 `findByIds` 为批量查询
2. 修 `BaseCrudRepository`：对应补齐（无 appId 版本）
3. 重写 `TodoRepository.partialUpdate` 用正确的 Jimmer DSL
4. 补齐 demo 模块的 service 层 + BFF DataFetcher（参考 ARCHITECTURE.md 的 Todo demo）
5. 确认 `./gradlew :core-api:compileKotlin` 零错误
6. （后续 PR）将继承改为组合模式

---

## 验收标准

1. `./gradlew :core-api:compileKotlin` 零错误
2. demo 模块有完整的 repo → service → DataFetcher 链路
3. BaseRepo 提供 findByCursor、batchSave、deleteByIds
