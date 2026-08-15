# 全模块 MongoDB + GraphQL 迁移 — 总计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 从 main 分支（PG + Jimmer）提取所有业务逻辑，用 MongoDB + Netflix DGS 12 GraphQL 重新实现。Service 层单表操作，GraphQL 层做数据聚合。UUID → ObjectId。

**架构：** 单 DGS schema，双 endpoint（/customer/graphql, /admin/graphql），DataLoader 批量聚合，Trusted Documents allowlist，per-operation 监控。

**技术栈：** Spring Boot 4.1 MVC + Netflix DGS 12 + MongoDB + Micrometer + Konvert

---

## 子计划总览

| # | 子计划 | 依赖 | 状态 |
|---|--------|------|------|
| 1 | [todo + todoItem](#子计划-1todo--todoitem) | 无（GraphQL 基础设施已在 POC 中搭建） | 🔜 |
| 2 | scan (antique) + storage | 子计划 1 完成 | ⏳ |
| 3 | collection | 子计划 2 完成（依赖 scan） | ⏳ |
| 4 | feedback | 子计划 2 完成（依赖 scan） | ⏳ |
| 5 | iap | 无 | ⏳ |
| 6 | appconfig | 无 | ⏳ |

每个子计划独立产出可工作、可测试的 GraphQL API。后续子计划在前置完成后再细化。

---

## 数据模型总览（从 main 分支提取，UUID → ObjectId）

### Todo 模块
- `todos` 集合：id, appId, installId, userId, title, done, meta(Map)
- `todo_items` 集合：id, appId, todoId(外键), content, done

### Scan 模块
- `scan_records` 集合：id, appId, images(List<ImageRef>), result(Map), status, clientIp, lang, country, currency, userDisplayName, userNotes, collected

### Collection 模块
- `scan_collections` 集合：id, appId, installId, userId, isDefault
- `scan_collection_items` 集合：id, appId, collectionId(外键), scanRecordId(外键)

### Feedback 模块
- `feedbacks` 集合：id, appId, installId, userId, scanRecordId, category, comment（追加式，不软删）

### IAP 模块
- `subscriptions` 集合：id, appId, subscriptionPxid, originalTransactionId, productId, platform, active, subStatus, expiryDate, purchaseToken, rawResponse(Map)
- `store_notifications` 集合：id, appId, platform, subscriptionPxid, purchaseToken, notificationType, rawPayload, processed, processedAt

### AppConfig 模块
- `app_config_revisions` 集合：id, appId, authTenantId, appleBundleId, androidPackageName, content(ConfigContent), revisionNumber, enabled, slug, note
- `app_infos` 集合：id, name, description, slug（全局表，不按 appId 分片）

### Storage 模块
- `upload_records` 集合：id, appId, installId, userId, objectKey, contentType, category, clientIp（追加式）

---

## 子计划 1：todo + todoItem

**目标：** 将现有内嵌 items 的 TodoDocument 拆成独立的 `todos` + `todo_items` 两个集合，GraphQL 层通过 DataLoader 聚合。完整实现 main 分支 TodoService 的所有业务操作。

**前置条件：** GraphQL 基础设施（DGS、context builder、directive、TrustedDocumentFilter、Instrumentation）已在 POC 中搭建完成。

---

### 任务 1.1：重构 TodoDocument（去掉内嵌 items，加 meta 字段）

**文件：**
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDocument.kt`

- [ ] **步骤 1：修改 TodoDocument，去掉 items 列表，加 meta**

```kotlin
package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/** todos 集合。items 拆为独立集合 todo_items，通过 todoId 关联。 */
@Document(collection = "todos")
@CompoundIndex(name = "todos_app_id_idx", def = "{'appId': 1, '_id': 1}")
class TodoDocument : BaseAppDocument() {
    lateinit var title: String
    var done: Boolean = false
    /** JSONB 元数据 */
    var meta: Map<String, Any?>? = null
    /** 拥有者 userId（登录用户），可为 null（匿名用户时不填）。 */
    var userId: String? = null
    /** 拥有者 installId（匿名或登录均填）。 */
    var installId: String? = null
}
```

- [ ] **步骤 2：删除旧的 TodoItem.kt（内嵌版本）**

删除文件：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoItem.kt`

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`

注意：此步骤会导致 TodoService 编译失败（引用了旧的 items 字段），下个任务修复。

- [ ] **步骤 4：Commit**

```bash
git add -A
git commit -m "refactor(todo): remove embedded items from TodoDocument, add meta field"
```

---

### 任务 1.2：创建独立 TodoItemDocument + TodoItemRepository

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/document/TodoItemDocument.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/repo/TodoItemRepository.kt`

- [ ] **步骤 1：创建 TodoItemDocument**

```kotlin
package com.ifmix.api.core.modules.todo.document

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/** todo_items 集合。独立于 todos，通过 todoId 外键关联。 */
@Document(collection = "todo_items")
@CompoundIndex(name = "todo_items_app_todo_idx", def = "{'appId': 1, 'todoId': 1}")
class TodoItemDocument : BaseAppDocument() {
    /** 关联的 Todo id */
    lateinit var todoId: String
    lateinit var content: String
    var done: Boolean = false
}
```

- [ ] **步骤 2：创建 TodoItemRepository**

```kotlin
package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

/**
 * TodoItem 仓储。提供批量按 todoId 查询（DataLoader 专用）和单条 CRUD。
 */
class TodoItemRepository(private val mongo: MongoTemplate) {

    fun insertOne(ctx: RequestContext, doc: TodoItemDocument): String {
        doc.appId = ctx.appId
        mongo.insert(doc)
        return doc.id
    }

    fun findById(ctx: RequestContext, id: String): TodoItemDocument? {
        val query = Query(
            Criteria.where("_id").`is`(id)
                .and("appId").`is`(ctx.appId)
                .and("deletedAt").`is`(null)
        )
        return mongo.findOne(query, TodoItemDocument::class.java)
    }

    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemDocument> {
        if (todoIds.isEmpty()) return emptyList()
        val query = Query(
            Criteria.where("appId").`is`(ctx.appId)
                .and("todoId").`in`(todoIds)
                .and("deletedAt").`is`(null)
        )
        return mongo.find(query, TodoItemDocument::class.java)
    }

    fun findByTodoId(ctx: RequestContext, todoId: String): List<TodoItemDocument> {
        val query = Query(
            Criteria.where("appId").`is`(ctx.appId)
                .and("todoId").`is`(todoId)
                .and("deletedAt").`is`(null)
        )
        return mongo.find(query, TodoItemDocument::class.java)
    }

    fun updateById(ctx: RequestContext, id: String, update: org.springframework.data.mongodb.core.query.Update): Boolean {
        val query = Query(
            Criteria.where("_id").`is`(id)
                .and("appId").`is`(ctx.appId)
                .and("deletedAt").`is`(null)
        )
        update.set("updatedAt", java.time.Instant.now())
        return mongo.updateFirst(query, update, TodoItemDocument::class.java).modifiedCount > 0
    }

    fun softDeleteById(ctx: RequestContext, id: String): Boolean {
        val query = Query(
            Criteria.where("_id").`is`(id)
                .and("appId").`is`(ctx.appId)
                .and("deletedAt").`is`(null)
        )
        val update = org.springframework.data.mongodb.core.query.Update()
            .set("deletedAt", java.time.Instant.now())
        return mongo.updateFirst(query, update, TodoItemDocument::class.java).modifiedCount > 0
    }

    fun softDeleteByIds(ctx: RequestContext, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val query = Query(
            Criteria.where("_id").`in`(ids)
                .and("appId").`is`(ctx.appId)
                .and("deletedAt").`is`(null)
        )
        val update = org.springframework.data.mongodb.core.query.Update()
            .set("deletedAt", java.time.Instant.now())
        return mongo.updateMulti(query, update, TodoItemDocument::class.java).modifiedCount.toInt()
    }

    fun softDeleteByTodoId(ctx: RequestContext, todoId: String): Int {
        val query = Query(
            Criteria.where("todoId").`is`(todoId)
                .and("appId").`is`(ctx.appId)
                .and("deletedAt").`is`(null)
        )
        val update = org.springframework.data.mongodb.core.query.Update()
            .set("deletedAt", java.time.Instant.now())
        return mongo.updateMulti(query, update, TodoItemDocument::class.java).modifiedCount.toInt()
    }
}
```

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/document/
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/repo/
git commit -m "feat(todo): add independent TodoItemDocument and TodoItemRepository"
```

---

### 任务 1.3：创建 TodoItemService

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/service/TodoItemService.kt`

- [ ] **步骤 1：创建 TodoItemService**

```kotlin
package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

/**
 * TodoItem 业务服务。单表操作，不知道 Todo 的存在。
 */
class TodoItemService(private val repo: TodoItemRepository) {

    fun create(ctx: RequestContext, todoId: String, content: String, done: Boolean = false): String {
        val doc = TodoItemDocument().apply {
            this.todoId = todoId
            this.content = content
            this.done = done
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        return repo.insertOne(ctx, doc)
    }

    fun getById(ctx: RequestContext, id: String): TodoItemDocument =
        repo.findById(ctx, id) ?: throw com.ifmix.api.core.common.http.ApiError(
            com.ifmix.api.core.common.http.ErrorCode.NOT_FOUND, "todo item not found"
        )

    fun findById(ctx: RequestContext, id: String): TodoItemDocument? = repo.findById(ctx, id)

    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemDocument> =
        repo.findByTodoIds(ctx, todoIds)

    fun findByTodoId(ctx: RequestContext, todoId: String): List<TodoItemDocument> =
        repo.findByTodoId(ctx, todoId)

    fun update(ctx: RequestContext, id: String, content: String?, done: Boolean?): Boolean {
        val update = Update()
        content?.let { update.set("content", it) }
        done?.let { update.set("done", it) }
        if (update.updateObject.isEmpty()) return true
        return repo.updateById(ctx, id, update)
    }

    fun deleteById(ctx: RequestContext, id: String): Boolean = repo.softDeleteById(ctx, id)

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int = repo.softDeleteByIds(ctx, ids)

    fun deleteByTodoId(ctx: RequestContext, todoId: String): Int = repo.softDeleteByTodoId(ctx, todoId)
}
```

- [ ] **步骤 2：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/service/
git commit -m "feat(todo): add TodoItemService"
```

---

### 任务 1.4：重构 TodoService（去掉内嵌 items 逻辑）

**文件：**
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoService.kt`

- [ ] **步骤 1：重写 TodoService，移除所有 items 内嵌操作**

```kotlin
package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.service.CRUDService
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Instant

/**
 * Todo 业务服务。只管 todos 集合，不知道 TodoItem 的存在。
 * 聚合由 GraphQL DataLoader 层处理。
 */
class TodoService(
    private val crud: CRUDService<TodoDocument>,
    private val mongo: MongoTemplate,
) {

    fun create(ctx: RequestContext, title: String, meta: Map<String, Any?>? = null): String {
        val doc = TodoDocument().apply {
            this.title = title
            this.done = false
            this.meta = meta
            this.userId = ctx.userId
            this.installId = ctx.installId
        }
        return crud.createOne(ctx, doc)
    }

    fun getById(ctx: RequestContext, id: String): TodoDocument =
        crud.getById(ctx, id)

    fun findById(ctx: RequestContext, id: String): TodoDocument? =
        crud.findById(ctx, id)

    fun findByCursor(ctx: RequestContext, input: CursorQueryInput): Page<TodoDocument> =
        crud.findByCursor(ctx, input)

    fun findByIds(ctx: RequestContext, ids: List<String>): List<TodoDocument> =
        crud.findByIds(ctx, ids)

    fun update(ctx: RequestContext, id: String, title: String?, done: Boolean?, meta: Map<String, Any?>?): Boolean {
        val patch = mutableMapOf<String, Any?>()
        title?.let { patch["title"] = it }
        done?.let { patch["done"] = it }
        meta?.let { patch["meta"] = it }
        if (patch.isEmpty()) return true
        return crud.updateById(ctx, id, patch)
    }

    fun deleteById(ctx: RequestContext, id: String): Boolean =
        crud.deleteById(ctx, id)

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int =
        crud.deleteByIds(ctx, ids)

    fun updateByIds(ctx: RequestContext, patches: List<Pair<String, Map<String, Any?>>>): Int {
        var count = 0
        for ((id, patch) in patches) {
            if (crud.updateById(ctx, id, patch)) count++
        }
        return count
    }
}
```

- [ ] **步骤 2：更新 TodoConfig（注册新 bean）**

重写 `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoConfig.kt`：

```kotlin
package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import com.ifmix.api.core.modules.todo.service.TodoItemService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate

@Configuration
class TodoConfig {

    @Bean
    fun todoRepository(clusterResolver: MongoClusterResolver): CRUDRepository<TodoDocument> =
        CRUDRepository(clusterResolver.primary(), TodoDocument::class.java)

    @Bean
    fun todoCrudService(todoRepository: CRUDRepository<TodoDocument>): CRUDService<TodoDocument> =
        CRUDService(todoRepository)

    @Bean
    fun todoService(todoCrudService: CRUDService<TodoDocument>, mongo: MongoTemplate): TodoService =
        TodoService(todoCrudService, mongo)

    @Bean
    fun todoItemRepository(mongo: MongoTemplate): TodoItemRepository =
        TodoItemRepository(mongo)

    @Bean
    fun todoItemService(todoItemRepository: TodoItemRepository): TodoItemService =
        TodoItemService(todoItemRepository)
}
```

- [ ] **步骤 3：删除旧的 TodoDtos.kt（REST 时代的 DTO，GraphQL 不需要）**

删除：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDtos.kt`
删除：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoMapper.kt`

- [ ] **步骤 4：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`

注意：`CustomerTodoController`（REST 版）会编译失败，因为依赖了旧的 DTO。下一步删掉它。

- [ ] **步骤 5：Commit**

```bash
git add -A
git commit -m "refactor(todo): rewrite TodoService for single-table, remove embedded items logic"
```

---

### 任务 1.5：删除旧 REST controller，更新 GraphQL fetcher

**文件：**
- 删除：`core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerTodoController.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/customer/CustomerTodoFetcher.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/admin/AdminTodoFetcher.kt`

- [ ] **步骤 1：删除 CustomerTodoController**

删除文件：`core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerTodoController.kt`

- [ ] **步骤 2：更新 CustomerTodoFetcher，对齐新 TodoService API**

重写 `CustomerTodoFetcher.kt`，调用新的 `TodoService.create(ctx, title, meta)` 和 `TodoItemService`。关键变化：
- `createTodo` mutation 支持带 items 创建：先 create todo，再逐个 create item
- `updateTodo` mutation 只更新 todo 本身的 title/done/meta
- 新增 `createTodoItem`、`updateTodoItem`、`deleteTodoItem` mutations
- `deleteTodo` 同时删除关联的 items（调 `todoItemService.deleteByTodoId`）

```kotlin
// createTodo 中的 items 创建逻辑：
@DgsMutation
fun createTodo(
    @InputArgument input: Map<String, Any?>,
    dfe: DgsDataFetchingEnvironment,
): TodoType {
    val ctx = getContext(dfe)
    val title = input["title"] as String
    val meta = input["meta"] as? Map<String, Any?>
    val items = input["items"] as? List<Map<String, Any?>>

    val todoId = todoService.create(ctx.requestContext, title, meta)

    // 创建关联 items
    items?.forEach { item ->
        val content = item["content"] as String
        val done = item["done"] as? Boolean ?: false
        todoItemService.create(ctx.requestContext, todoId, content, done)
    }

    return todoService.getById(ctx.requestContext, todoId).toTodoType()
}
```

- [ ] **步骤 3：更新 AdminTodoFetcher，对齐新 API**

`batchUpdateTodos` 和 `batchDeleteTodos` 使用新的 `TodoService.updateByIds` / `deleteByIds`。

- [ ] **步骤 4：更新 schema.graphqls，在 CreateTodoInput 中加 items 和 meta**

```graphql
input CreateTodoInput {
    title: String!
    meta: JSON
    items: [CreateTodoItemInput!]
}
```

（需要 `scalar JSON`，DGS extended-scalars 已内置）

- [ ] **步骤 5：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 6：Commit**

```bash
git add -A
git commit -m "feat(todo): migrate to GraphQL, remove REST controller"
```

---

### 任务 1.6：更新 GraphQL Type 和 Mapper

**文件：**
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/type/TodoType.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/mapper/TodoTypeMapper.kt`

- [ ] **步骤 1：TodoType 加 meta 字段**

```kotlin
package com.ifmix.api.core.graphql.common.type

import java.time.Instant

data class TodoType(
    val id: String,
    val title: String,
    val done: Boolean,
    val meta: Map<String, Any?>?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

- [ ] **步骤 2：更新 mapper**

```kotlin
package com.ifmix.api.core.modules.todo.mapper

import com.ifmix.api.core.graphql.common.type.TodoItemType
import com.ifmix.api.core.graphql.common.type.TodoType
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.modules.todo.document.TodoItemDocument

fun TodoDocument.toTodoType(): TodoType = TodoType(
    id = this.id,
    title = this.title,
    done = this.done,
    meta = this.meta,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)

fun TodoItemDocument.toTodoItemType(): TodoItemType = TodoItemType(
    id = this.id,
    todoId = this.todoId,
    content = this.content,
    done = this.done,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)
```

- [ ] **步骤 3：更新 schema.graphqls 中 Todo type 加 meta**

```graphql
type Todo {
    id: ID!
    title: String!
    done: Boolean!
    meta: JSON
    items: [TodoItem!]!
    createdAt: DateTime!
    updatedAt: DateTime!
}
```

- [ ] **步骤 4：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`

- [ ] **步骤 5：Commit**

```bash
git add -A
git commit -m "feat(todo): add meta field to TodoType and schema"
```

---

### 任务 1.7：端到端验证

**文件：** 无新文件

- [ ] **步骤 1：启动应用**

运行：`./gradlew :core-api:bootRun`

- [ ] **步骤 2：测试 createTodo（带 items）**

```bash
curl -s --noproxy '*' -X POST http://localhost:3001/graphql \
  -H "Content-Type: application/json" \
  -H "x-app-id: 000000000000000000000001" \
  -H "x-install-id: install001" \
  -d '{
    "query": "mutation { createTodo(input: { title: \"Test Todo\", items: [{ content: \"item 1\" }, { content: \"item 2\" }] }) { id title done items { id content done } } }"
  }'
```

预期：返回创建的 todo 及其 items。

- [ ] **步骤 3：测试 todos 列表查询（DataLoader 聚合）**

```bash
curl -s --noproxy '*' -X POST http://localhost:3001/graphql \
  -H "Content-Type: application/json" \
  -H "x-app-id: 000000000000000000000001" \
  -H "x-install-id: install001" \
  -d '{
    "query": "{ todos { items { id title done items { id content done } } hasMore } }"
  }'
```

预期：返回 todo 列表，每个 todo 含 items（通过 DataLoader 批量加载）。

- [ ] **步骤 4：测试 admin batch 操作**

```bash
curl -s --noproxy '*' -X POST http://localhost:3001/graphql \
  -H "Content-Type: application/json" \
  -H "x-app-id: 000000000000000000000001" \
  -H "X-Role: admin" \
  -d '{
    "query": "mutation { batchDeleteTodos(ids: [\"<id1>\", \"<id2>\"]) { success modifiedCount } }"
  }'
```

- [ ] **步骤 5：确认 Micrometer metrics 可用**

```bash
curl -s --noproxy '*' http://localhost:3001/actuator/metrics/gql.query
```

预期：返回 metrics 数据。

- [ ] **步骤 6：Commit**

```bash
git add -A
git commit -m "feat(todo): complete todo + todoItem GraphQL migration, verified e2e"
```

---

## 后续子计划（待细化）

子计划 2-6 在子计划 1 完成并验证后逐个细化。每个子计划遵循相同模式：
1. 创建 Document class
2. 创建 Repository
3. 创建 Service（单表）
4. 创建 GraphQL Type + Mapper
5. 更新 schema.graphqls
6. 创建 DGS Fetcher（含 DataLoader 聚合）
7. 删除旧 REST Controller
8. 端到端验证
