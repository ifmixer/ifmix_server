# 计划：查询/更新改为 Spring Data MongoDB Kotlin Type-Safe 扩展

## 前置条件

- Phase 1-3 已完成（ObjectId 迁移、lateinit、DGS codegen）
- `BaseDocument.id` 已经是 `ObjectId`
- `RequestContext.appId` 已经是 `ObjectId`

---

## 做法

所有 `Criteria.where("field").is(value)` 改为 KProperty 引用：
```kotlin
import org.springframework.data.mongodb.core.query.*

// Filter
TodoItemDocument::appId isEqualTo ctx.appId
TodoItemDocument::deletedAt isEqualTo null
BaseDocument::id isEqualTo id

// 嵌套字段
Book::author / Author::name regex "^H"

// Update
update(TodoItemDocument::updatedAt, Instant.now())
    .set(TodoItemDocument::content, "new")
    .set(TodoItemDocument::done, true)
```

---

## 文件清单（17 个）

### 通用层

| 文件 | query 处数 | update 处数 |
|------|-----------|------------|
| `common/db/CRUDRepository.kt` | 10 | 1 |
| `common/db/Ownership.kt` | 2 | 0 |

### 模块层

| 文件 | query 处数 | update 处数 |
|------|-----------|------------|
| `modules/todo/repo/TodoItemRepository.kt` | 6 | 3 |
| `modules/todo/service/TodoItemService.kt` | 0 | 1（构建 Update） |
| `modules/appconfig/AppConfigRepo.kt` | 7 | 4 |
| `modules/appconfig/AppInfoRepo.kt` | 1 | 0 |
| `modules/collection/CollectionItemRepository.kt` | 4 | 1 |
| `modules/collection/CollectionRepository.kt` | 3 | 0 |
| `modules/collection/CollectionMembershipImpl.kt` | 1 | 0 |
| `modules/auth/AppRefreshTokenRepo.kt` | 4 | 3 |
| `modules/auth/AuthDeviceSecretRepo.kt` | 3 | 2 |
| `modules/auth/UserInstallBindingRepo.kt` | 2 | 0 |
| `modules/auth/AuthProviderIdentityRepo.kt` | 2 | 1 |
| `modules/auth/AppUserRepo.kt` | 1 | 0 |
| `modules/auth/MergeOnLoginListener.kt` | 1 | 1 |
| `modules/antique/AntiqueService.kt` | 3 | 1 |
| `modules/iap/SubscriptionRepo.kt` | 3 | 0 |
| `common/ai/AgnesKeyRepo.kt` | 1 | 0 |

---

## 转换规则

### Query

| 改前 | 改后 |
|------|------|
| `Criteria.where("_id").is(id)` | `BaseDocument::id isEqualTo id` |
| `Criteria.where("appId").is(ctx.appId)` | `BaseAppDocument::appId isEqualTo ctx.appId` |
| `Criteria.where("deletedAt").is(null)` | `BaseAppDocument::deletedAt isEqualTo null` |
| `Criteria.where("todoId").is(todoId)` | `TodoItemDocument::todoId isEqualTo todoId` |
| `Criteria.where("todoId").in(todoIds)` | `TodoItemDocument::todoId inValues todoIds` |
| `Criteria.where("_id").in(ids)` | `BaseDocument::id inValues ids` |
| `Criteria.where("_id").lt(cursorId)` | `BaseDocument::id lt cursorId` |
| `Criteria.where("_id").gt(cursorId)` | `BaseDocument::id gt cursorId` |
| `.and("field").is(value)` 链式 | 拆为 `Criteria().andOperator(...)` 多条件 |

### Update

| 改前 | 改后 |
|------|------|
| `Update().set("content", it)` | `update(Doc::content, it)` |
| `.set("done", it)` 链式 | `.set(Doc::done, it)` |
| `.set("updatedAt", now)` | `.set(BaseDocument::updatedAt, now)` |
| `.set("deletedAt", now)` | `.set(BaseAppDocument::deletedAt, now)` |

### 注意

- `Criteria.where("a").is(x).and("b").is(y)` 链式写法改为 `Criteria().andOperator(A::a isEqualTo x, A::b isEqualTo y)`
- CRUDRepository 是泛型 `T : BaseDocument`，用基类属性引用 `BaseDocument::id`、`BaseAppDocument::appId`
- 确认 `BaseDocument::id isEqualTo objectId` 会正确映射到 `_id`（Spring Data 对 `@Id` 字段的 Kotlin 扩展应自动处理）

---

## 示例：TodoItemRepository 改造

改前：
```kotlin
fun findById(ctx: RequestContext, id: ObjectId): TodoItemDocument? {
    val query = Query(
        Criteria.where("_id").`is`(id)
            .and("appId").`is`(ctx.appId)
            .and("deletedAt").`is`(null)
    )
    return mongo.findOne(query, TodoItemDocument::class.java)
}

fun softDeleteById(ctx: RequestContext, id: ObjectId): Boolean {
    val query = Query(
        Criteria.where("_id").`is`(id)
            .and("appId").`is`(ctx.appId)
            .and("deletedAt").`is`(null)
    )
    val update = Update().set("deletedAt", Instant.now())
    return mongo.updateFirst(query, update, TodoItemDocument::class.java).modifiedCount > 0
}
```

改后：
```kotlin
fun findById(ctx: RequestContext, id: ObjectId): TodoItemDocument? {
    val query = Query(
        Criteria().andOperator(
            TodoItemDocument::id isEqualTo id,
            TodoItemDocument::appId isEqualTo ctx.appId,
            TodoItemDocument::deletedAt isEqualTo null,
        )
    )
    return mongo.findOne(query, TodoItemDocument::class.java)
}

fun softDeleteById(ctx: RequestContext, id: ObjectId): Boolean {
    val query = Query(
        Criteria().andOperator(
            TodoItemDocument::id isEqualTo id,
            TodoItemDocument::appId isEqualTo ctx.appId,
            TodoItemDocument::deletedAt isEqualTo null,
        )
    )
    val upd = update(BaseAppDocument::deletedAt, Instant.now())
        .set(BaseDocument::updatedAt, Instant.now())
    return mongo.updateFirst(query, upd, TodoItemDocument::class.java).modifiedCount > 0
}
```

---

## 验证

- `./gradlew build`（编译 + 单元测试）
- 集成测试（testcontainers MongoDB）确认 CRUD 正常
- 确认 `::id isEqualTo` 生成的查询是 `{ _id: ObjectId(...) }` 而不是 `{ id: ... }`
