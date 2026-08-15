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

---

## 子计划 2：scan (antique) + storage

**目标：** 实现扫描记录（ScanRecord）和文件上传（UploadRecord），GraphQL 暴露扫描 CRUD + presign URL。

**前置：** 子计划 1 完成，GraphQL 基础设施可用。

---

### 任务 2.1：创建 ScanRecordDocument

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/scan/document/ScanRecordDocument.kt`

- [ ] **步骤 1：创建 Document**

```kotlin
package com.ifmix.api.core.modules.scan.document

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

data class ImageRef(val key: String)

@Document(collection = "scan_records")
@CompoundIndex(name = "scan_records_app_idx", def = "{'appId': 1, '_id': -1}")
class ScanRecordDocument : BaseAppDocument() {
    var images: List<ImageRef> = emptyList()
    var result: Map<String, Any?>? = null
    /** 0=UNKNOWN, 100=PENDING, 110=PROCESSING, 200=COMPLETED, 300=FAILED */
    var status: Int = 0
    var clientIp: String? = null
    var lang: String? = null
    var country: String? = null
    var currency: String? = null
    var userDisplayName: String? = null
    var userNotes: String? = null
    var collected: Boolean = false
}
```

- [ ] **步骤 2：Commit**

```bash
git add -A && git commit -m "feat(scan): add ScanRecordDocument"
```

---

### 任务 2.2：创建 UploadRecordDocument

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/storage/document/UploadRecordDocument.kt`

- [ ] **步骤 1：创建 Document（追加式，无软删）**

```kotlin
package com.ifmix.api.core.modules.storage.document

import com.ifmix.api.core.common.db.BaseDocument
import com.ifmix.api.core.common.db.AppScoped
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "upload_records")
class UploadRecordDocument : BaseDocument(), AppScoped {
    override var appId: String = ""
    var installId: String? = null
    var userId: String? = null
    var objectKey: String = ""
    var contentType: String = ""
    var category: String = ""
    var clientIp: String? = null
}
```

- [ ] **步骤 2：Commit**

```bash
git add -A && git commit -m "feat(storage): add UploadRecordDocument"
```

---

### 任务 2.3：创建 ScanRecordRepository + ScanService

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/scan/repo/ScanRecordRepository.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/scan/service/ScanService.kt`

- [ ] **步骤 1：创建 ScanRecordRepository**

```kotlin
package com.ifmix.api.core.modules.scan.repo

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.scan.document.ScanRecordDocument
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

class ScanRecordRepository(private val mongo: MongoTemplate) {

    fun insert(ctx: RequestContext, doc: ScanRecordDocument): String {
        doc.appId = ctx.appId
        mongo.insert(doc)
        return doc.id
    }

    fun findById(ctx: RequestContext, id: String): ScanRecordDocument? {
        val query = Query(Criteria.where("_id").`is`(id).and("appId").`is`(ctx.appId).and("deletedAt").`is`(null))
        return mongo.findOne(query, ScanRecordDocument::class.java)
    }

    fun findByCursor(ctx: RequestContext, cursor: String?, limit: Int, collected: Boolean?): Pair<List<ScanRecordDocument>, Boolean> {
        val criteria = Criteria.where("appId").`is`(ctx.appId).and("deletedAt").`is`(null)
        collected?.let { criteria.and("collected").`is`(it) }
        cursor?.let { criteria.and("_id").lt(it) }
        val query = Query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(limit + 1)
        val results = mongo.find(query, ScanRecordDocument::class.java)
        val hasMore = results.size > limit
        return (if (hasMore) results.dropLast(1) else results) to hasMore
    }

    fun softDelete(ctx: RequestContext, id: String): Boolean {
        val query = Query(Criteria.where("_id").`is`(id).and("appId").`is`(ctx.appId).and("deletedAt").`is`(null))
        val update = Update().set("deletedAt", Instant.now())
        return mongo.updateFirst(query, update, ScanRecordDocument::class.java).modifiedCount > 0
    }

    fun update(ctx: RequestContext, id: String, update: Update): Boolean {
        val query = Query(Criteria.where("_id").`is`(id).and("appId").`is`(ctx.appId).and("deletedAt").`is`(null))
        update.set("updatedAt", Instant.now())
        return mongo.updateFirst(query, update, ScanRecordDocument::class.java).modifiedCount > 0
    }
}
```

- [ ] **步骤 2：创建 ScanService**

```kotlin
package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.scan.document.ImageRef
import com.ifmix.api.core.modules.scan.document.ScanRecordDocument
import com.ifmix.api.core.modules.scan.repo.ScanRecordRepository
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

class ScanService(private val repo: ScanRecordRepository) {

    fun create(ctx: RequestContext, images: List<ImageRef>, status: Int, result: Map<String, Any?>? = null): String {
        val doc = ScanRecordDocument().apply {
            this.images = images
            this.status = status
            this.result = result
            this.clientIp = null // 由 controller 层设置
            this.lang = ctx.lang
            this.country = ctx.country
            this.currency = ctx.currency
            this.collected = false
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        return repo.insert(ctx, doc)
    }

    fun getById(ctx: RequestContext, id: String): ScanRecordDocument =
        repo.findById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND, "scan record not found")

    fun findById(ctx: RequestContext, id: String): ScanRecordDocument? = repo.findById(ctx, id)

    fun findByCursor(ctx: RequestContext, cursor: String?, limit: Int, collected: Boolean?): Pair<List<ScanRecordDocument>, Boolean> =
        repo.findByCursor(ctx, cursor, limit, collected)

    fun delete(ctx: RequestContext, id: String): Boolean = repo.softDelete(ctx, id)

    fun updateDisplayName(ctx: RequestContext, id: String, name: String?): Boolean {
        val update = Update()
        if (name == null) update.unset("userDisplayName") else update.set("userDisplayName", name)
        return repo.update(ctx, id, update)
    }

    fun updateUserNotes(ctx: RequestContext, id: String, notes: String?): Boolean {
        val update = Update()
        if (notes == null) update.unset("userNotes") else update.set("userNotes", notes)
        return repo.update(ctx, id, update)
    }

    fun updateCollected(ctx: RequestContext, id: String, collected: Boolean): Boolean {
        val update = Update().set("collected", collected)
        return repo.update(ctx, id, update)
    }

    fun updateResult(ctx: RequestContext, id: String, result: Map<String, Any?>, status: Int): Boolean {
        val update = Update().set("result", result).set("status", status)
        return repo.update(ctx, id, update)
    }
}
```

- [ ] **步骤 3：创建 StorageService（presign 逻辑，复用现有 ObjectStorage）**

```kotlin
package com.ifmix.api.core.modules.storage.service

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.storage.ObjectStorage
import com.ifmix.api.core.modules.storage.document.UploadRecordDocument
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Duration
import java.time.Instant

class StorageService(
    private val objectStorage: ObjectStorage,
    private val mongo: MongoTemplate,
) {

    fun presignUpload(ctx: RequestContext, objectKey: String, contentType: String, duration: Duration): String =
        objectStorage.presignUpload(objectKey, contentType, duration)

    fun presignDownload(ctx: RequestContext, objectKey: String, duration: Duration): String =
        objectStorage.presignDownload(objectKey, duration)

    fun getPublicUrl(objectKey: String): String = objectStorage.getPublicUrl(objectKey)

    fun recordUpload(ctx: RequestContext, objectKey: String, contentType: String, category: String) {
        val doc = UploadRecordDocument().apply {
            this.appId = ctx.appId
            this.installId = ctx.installId
            this.userId = ctx.userId
            this.objectKey = objectKey
            this.contentType = contentType
            this.category = category
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        mongo.insert(doc)
    }
}
```

- [ ] **步骤 4：创建 ScanConfig（注册 bean）**

```kotlin
package com.ifmix.api.core.modules.scan

import com.ifmix.api.core.modules.scan.repo.ScanRecordRepository
import com.ifmix.api.core.modules.scan.service.ScanService
import com.ifmix.api.core.modules.storage.service.StorageService
import com.ifmix.api.core.common.storage.ObjectStorage
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate

@Configuration
class ScanConfig {
    @Bean
    fun scanRecordRepository(mongo: MongoTemplate) = ScanRecordRepository(mongo)

    @Bean
    fun scanService(repo: ScanRecordRepository) = ScanService(repo)

    @Bean
    fun storageService(objectStorage: ObjectStorage, mongo: MongoTemplate) = StorageService(objectStorage, mongo)
}
```

- [ ] **步骤 5：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`

- [ ] **步骤 6：Commit**

```bash
git add -A && git commit -m "feat(scan): add ScanService, StorageService with repositories"
```

---

### 任务 2.4：GraphQL schema + type + fetcher（scan + storage）

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/type/ScanTypes.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/scan/mapper/ScanMapper.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/customer/CustomerScanFetcher.kt`
- 修改：`core-api/src/main/resources/schema/schema.graphqls`

- [ ] **步骤 1：创建 ScanTypes**

```kotlin
package com.ifmix.api.core.graphql.common.type

import java.time.Instant

data class ScanRecordType(
    val id: String,
    val images: List<ImageRefType>,
    val result: Map<String, Any?>?,
    val status: Int,
    val userDisplayName: String?,
    val userNotes: String?,
    val collected: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ImageRefType(val key: String)

data class ScanConnection(
    val items: List<ScanRecordType>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class PresignUploadResult(
    val mediaId: String,
    val uploadUrl: String,
    val imageKey: String,
    val downloadUrl: String,
)

data class PresignDownloadResult(val downloadUrl: String)
```

- [ ] **步骤 2：创建 ScanMapper**

```kotlin
package com.ifmix.api.core.modules.scan.mapper

import com.ifmix.api.core.graphql.common.type.ImageRefType
import com.ifmix.api.core.graphql.common.type.ScanRecordType
import com.ifmix.api.core.modules.scan.document.ScanRecordDocument

fun ScanRecordDocument.toScanRecordType() = ScanRecordType(
    id = id,
    images = images.map { ImageRefType(key = it.key) },
    result = result,
    status = status,
    userDisplayName = userDisplayName,
    userNotes = userNotes,
    collected = collected,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
```

- [ ] **步骤 3：更新 schema.graphqls，追加 scan + storage 类型和操作**

在 schema.graphqls 中追加：

```graphql
# === Scan ===
type ScanRecord {
    id: ID!
    images: [ImageRef!]!
    result: JSON
    status: Int!
    userDisplayName: String
    userNotes: String
    collected: Boolean!
    createdAt: DateTime!
    updatedAt: DateTime!
}

type ImageRef { key: String! }

type ScanConnection {
    items: [ScanRecord!]!
    nextCursor: String
    hasMore: Boolean!
}

type PresignUploadResult {
    mediaId: ID!
    uploadUrl: String!
    imageKey: String!
    downloadUrl: String!
}

type PresignDownloadResult { downloadUrl: String! }

input NewScanImageInput { imageKey: String!, mediaType: String }
input UpdateScanInput { id: ID!, name: String, userNotes: String, collected: Boolean }
input PresignUploadInput { category: String!, contentType: String! }
input PresignDownloadInput { imageKey: String!, durationSeconds: Int }
```

在 Query 中追加：

```graphql
    scanRecord(id: ID!): ScanRecord
    scanRecords(cursor: String, limit: Int, collected: Boolean): ScanConnection!
```

在 Mutation 中追加：

```graphql
    newScan(images: [NewScanImageInput!]!): ScanRecord! @requirePermission(permission: "scan:write")
    updateScan(input: UpdateScanInput!): Boolean! @requirePermission(permission: "scan:write")
    deleteScan(id: ID!): Boolean! @requirePermission(permission: "scan:write")
    presignUpload(input: PresignUploadInput!): PresignUploadResult! @requirePermission(permission: "storage:write")
    presignDownload(input: PresignDownloadInput!): PresignDownloadResult! @requirePermission(permission: "storage:read")
```

更新 ROLE_PERMISSIONS：

```kotlin
val ROLE_PERMISSIONS = mapOf(
    "customer" to setOf("todo:read", "todo:write", "scan:read", "scan:write", "storage:read", "storage:write", "collection:read", "collection:write", "feedback:write", "iap:write"),
    "admin" to setOf("todo:read", "todo:write", "todo:batch", "todo:admin", "scan:read", "scan:write", "storage:read", "storage:write", "collection:read", "collection:write", "feedback:write", "iap:write", "appconfig:write"),
)
```

- [ ] **步骤 4：创建 CustomerScanFetcher**

```kotlin
package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.common.type.*
import com.ifmix.api.core.modules.scan.document.ImageRef
import com.ifmix.api.core.modules.scan.mapper.toScanRecordType
import com.ifmix.api.core.modules.scan.service.ScanService
import com.ifmix.api.core.modules.storage.service.StorageService
import com.netflix.graphql.dgs.*
import com.netflix.graphql.dgs.context.DgsContext
import org.bson.types.ObjectId
import java.time.Duration

@DgsComponent
class CustomerScanFetcher(
    private val scanService: ScanService,
    private val storageService: StorageService,
) {

    @DgsQuery
    fun scanRecord(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): ScanRecordType? {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        return scanService.findById(ctx.requestContext, id)?.toScanRecordType()
    }

    @DgsQuery
    fun scanRecords(
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        @InputArgument collected: Boolean?,
        dfe: DgsDataFetchingEnvironment,
    ): ScanConnection {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val (items, hasMore) = scanService.findByCursor(ctx.requestContext, cursor, effectiveLimit, collected)
        return ScanConnection(
            items = items.map { it.toScanRecordType() },
            nextCursor = if (items.isNotEmpty()) items.last().id else null,
            hasMore = hasMore,
        )
    }

    @DgsMutation
    fun newScan(@InputArgument images: List<Map<String, Any>>, dfe: DgsDataFetchingEnvironment): ScanRecordType {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val imageRefs = images.map { ImageRef(key = it["imageKey"] as String) }
        // ponytail: AI scan runner 逻辑沿用现有 AntiqueService 的 scanRunner 调用
        // 此处简化为直接创建 PENDING 状态记录，实际实现时接入 ScanRunner
        val id = scanService.create(ctx.requestContext, imageRefs, 100) // PENDING
        return scanService.getById(ctx.requestContext, id).toScanRecordType()
    }

    @DgsMutation
    fun updateScan(@InputArgument input: Map<String, Any?>, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val id = input["id"] as String
        var updated = false
        if (input.containsKey("name")) {
            updated = scanService.updateDisplayName(ctx.requestContext, id, input["name"] as? String) || updated
        }
        if (input.containsKey("userNotes")) {
            updated = scanService.updateUserNotes(ctx.requestContext, id, input["userNotes"] as? String) || updated
        }
        if (input.containsKey("collected")) {
            updated = scanService.updateCollected(ctx.requestContext, id, input["collected"] as Boolean) || updated
        }
        return updated
    }

    @DgsMutation
    fun deleteScan(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        return scanService.delete(ctx.requestContext, id)
    }

    @DgsMutation
    fun presignUpload(@InputArgument input: Map<String, String>, dfe: DgsDataFetchingEnvironment): PresignUploadResult {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val category = input["category"]!!
        val contentType = input["contentType"]!!
        val ext = contentType.substringAfter("/")
        val mediaId = ObjectId().toHexString()
        val objectKey = "app/${ctx.requestContext.appId}/$category/install/${ctx.requestContext.installId}/$mediaId.$ext"

        val uploadUrl = storageService.presignUpload(ctx.requestContext, objectKey, contentType, Duration.ofSeconds(300))
        val downloadUrl = storageService.getPublicUrl(objectKey)
        storageService.recordUpload(ctx.requestContext, objectKey, contentType, category)

        return PresignUploadResult(mediaId = mediaId, uploadUrl = uploadUrl, imageKey = objectKey, downloadUrl = downloadUrl)
    }

    @DgsMutation
    fun presignDownload(@InputArgument input: Map<String, Any?>, dfe: DgsDataFetchingEnvironment): PresignDownloadResult {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val imageKey = input["imageKey"] as String
        val duration = (input["durationSeconds"] as? Int)?.toLong() ?: 3600L
        val url = storageService.presignDownload(ctx.requestContext, imageKey, Duration.ofSeconds(duration))
        return PresignDownloadResult(downloadUrl = url)
    }
}
```

- [ ] **步骤 5：删除旧 REST controllers**

删除：
- `core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerAntiqueController.kt`
- `core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerStorageController.kt`

- [ ] **步骤 6：验证编译 + Commit**

```bash
./gradlew :core-api:compileKotlin
git add -A && git commit -m "feat(scan): add scan + storage GraphQL fetcher, remove REST controllers"
```

---

## 子计划 3：collection（收藏夹）

**目标：** 实现 ScanCollection + ScanCollectionItem，GraphQL 暴露收藏操作，DataLoader 聚合 collection → items → scanRecord。

**前置：** 子计划 2 完成（依赖 ScanRecordDocument）。

---

### 任务 3.1：创建 Collection Documents

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/collection/document/ScanCollectionDocument.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/collection/document/ScanCollectionItemDocument.kt`

- [ ] **步骤 1：ScanCollectionDocument**

```kotlin
package com.ifmix.api.core.modules.collection.document

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "scan_collections")
class ScanCollectionDocument : BaseAppDocument() {
    var installId: String? = null
    var userId: String? = null
    var isDefault: Boolean = false
}
```

- [ ] **步骤 2：ScanCollectionItemDocument**

```kotlin
package com.ifmix.api.core.modules.collection.document

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "scan_collection_items")
@CompoundIndex(name = "collection_items_idx", def = "{'appId': 1, 'collectionId': 1, 'scanRecordId': 1}", unique = true)
class ScanCollectionItemDocument : BaseAppDocument() {
    lateinit var collectionId: String
    lateinit var scanRecordId: String
}
```

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "feat(collection): add ScanCollection and ScanCollectionItem documents"
```

---

### 任务 3.2：创建 CollectionService

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/collection/repo/CollectionRepository.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/collection/service/CollectionService.kt`

- [ ] **步骤 1：CollectionRepository（含 findDefault、insertIfAbsent、softDeleteByScanIds）**

```kotlin
package com.ifmix.api.core.modules.collection.repo

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.collection.document.ScanCollectionDocument
import com.ifmix.api.core.modules.collection.document.ScanCollectionItemDocument
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

class CollectionRepository(private val mongo: MongoTemplate) {

    fun findDefault(ctx: RequestContext): ScanCollectionDocument? {
        val criteria = Criteria.where("appId").`is`(ctx.appId)
            .and("isDefault").`is`(true)
            .and("deletedAt").`is`(null)
        // 按 installId 或 userId 过滤归属
        val ownerCriteria = mutableListOf<Criteria>()
        ctx.installId?.let { ownerCriteria.add(Criteria.where("installId").`is`(it)) }
        ctx.userId?.let { ownerCriteria.add(Criteria.where("userId").`is`(it)) }
        if (ownerCriteria.isNotEmpty()) criteria.orOperator(*ownerCriteria.toTypedArray())
        return mongo.findOne(Query(criteria), ScanCollectionDocument::class.java)
    }

    fun createCollection(ctx: RequestContext, isDefault: Boolean): ScanCollectionDocument {
        val doc = ScanCollectionDocument().apply {
            appId = ctx.appId
            installId = ctx.installId
            userId = ctx.userId
            this.isDefault = isDefault
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        mongo.insert(doc)
        return doc
    }

    fun insertItemIfAbsent(ctx: RequestContext, collectionId: String, scanRecordId: String): String {
        val existing = mongo.findOne(
            Query(Criteria.where("appId").`is`(ctx.appId)
                .and("collectionId").`is`(collectionId)
                .and("scanRecordId").`is`(scanRecordId)
                .and("deletedAt").`is`(null)),
            ScanCollectionItemDocument::class.java
        )
        if (existing != null) return existing.id

        val doc = ScanCollectionItemDocument().apply {
            appId = ctx.appId
            this.collectionId = collectionId
            this.scanRecordId = scanRecordId
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }
        mongo.insert(doc)
        return doc.id
    }

    fun softDeleteItemsByScanIds(ctx: RequestContext, collectionId: String, scanRecordIds: List<String>): Int {
        val query = Query(Criteria.where("appId").`is`(ctx.appId)
            .and("collectionId").`is`(collectionId)
            .and("scanRecordId").`in`(scanRecordIds)
            .and("deletedAt").`is`(null))
        val update = Update().set("deletedAt", Instant.now())
        return mongo.updateMulti(query, update, ScanCollectionItemDocument::class.java).modifiedCount.toInt()
    }

    fun findItemsByCursor(ctx: RequestContext, collectionId: String, cursor: String?, limit: Int): Pair<List<ScanCollectionItemDocument>, Boolean> {
        val criteria = Criteria.where("appId").`is`(ctx.appId)
            .and("collectionId").`is`(collectionId)
            .and("deletedAt").`is`(null)
        cursor?.let { criteria.and("_id").lt(it) }
        val query = Query(criteria).with(Sort.by(Sort.Direction.DESC, "_id")).limit(limit + 1)
        val results = mongo.find(query, ScanCollectionItemDocument::class.java)
        val hasMore = results.size > limit
        return (if (hasMore) results.dropLast(1) else results) to hasMore
    }
}
```

- [ ] **步骤 2：CollectionService**

```kotlin
package com.ifmix.api.core.modules.collection.service

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.collection.document.ScanCollectionDocument
import com.ifmix.api.core.modules.collection.document.ScanCollectionItemDocument
import com.ifmix.api.core.modules.collection.repo.CollectionRepository

class CollectionService(private val repo: CollectionRepository) {

    fun getOrCreateDefault(ctx: RequestContext): ScanCollectionDocument =
        repo.findDefault(ctx) ?: repo.createCollection(ctx, isDefault = true)

    fun addItem(ctx: RequestContext, collectionId: String?, scanRecordId: String): String {
        val cid = collectionId ?: getOrCreateDefault(ctx).id
        return repo.insertItemIfAbsent(ctx, cid, scanRecordId)
    }

    fun removeItems(ctx: RequestContext, collectionId: String?, scanRecordIds: List<String>): Int {
        val cid = collectionId ?: getOrCreateDefault(ctx).id
        return repo.softDeleteItemsByScanIds(ctx, cid, scanRecordIds)
    }

    fun findItemsByCursor(ctx: RequestContext, collectionId: String?, cursor: String?, limit: Int): Pair<List<ScanCollectionItemDocument>, Boolean> {
        val cid = collectionId ?: getOrCreateDefault(ctx).id
        return repo.findItemsByCursor(ctx, cid, cursor, limit)
    }
}
```

- [ ] **步骤 3：CollectionConfig + GraphQL schema + fetcher**

同模式创建 Config bean 注册、GraphQL type、schema 定义、DGS fetcher。

Schema 追加：

```graphql
type ScanCollection { id: ID!, isDefault: Boolean!, createdAt: DateTime! }
type ScanCollectionItemType { id: ID!, collectionId: ID!, scanRecordId: ID!, scanRecord: ScanRecord, createdAt: DateTime! }
type CollectionItemConnection { items: [ScanCollectionItemType!]!, nextCursor: String, hasMore: Boolean! }

# Query
    defaultCollection: ScanCollection!
    collectionItems(collectionId: ID, cursor: String, limit: Int): CollectionItemConnection!

# Mutation
    addCollectionItem(collectionId: ID, scanRecordId: ID!): ID! @requirePermission(permission: "collection:write")
    removeCollectionItems(collectionId: ID, scanRecordIds: [ID!]!): Int! @requirePermission(permission: "collection:write")
```

DataLoader：`ScanCollectionItem.scanRecord` 通过 DataLoader 批量加载 ScanRecordDocument。

- [ ] **步骤 4：删除旧 REST controller**

删除：`core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerCollectionController.kt`

- [ ] **步骤 5：验证编译 + Commit**

```bash
./gradlew :core-api:compileKotlin
git add -A && git commit -m "feat(collection): add collection GraphQL API with DataLoader"
```

---

## 子计划 4：feedback

**目标：** 实现 Feedback 追加式写入。简单 mutation，无复杂聚合。

**前置：** 子计划 2 完成（可选关联 scanRecordId）。

---

### 任务 4.1：创建 FeedbackDocument + Service + GraphQL

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/document/FeedbackDocument.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/service/FeedbackService.kt`（重写）
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/customer/CustomerFeedbackFetcher.kt`

- [ ] **步骤 1：FeedbackDocument（追加式，无软删）**

```kotlin
package com.ifmix.api.core.modules.feedback.document

import com.ifmix.api.core.common.db.BaseDocument
import com.ifmix.api.core.common.db.AppScoped
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "feedbacks")
class FeedbackDocument : BaseDocument(), AppScoped {
    override var appId: String = ""
    lateinit var installId: String
    var userId: String? = null
    var scanRecordId: String? = null
    /** 0=UNKNOWN, 100=LIKED, 200=PRICE_TOO_HIGH, 210=PRICE_TOO_LOW, 220=PRICE_MISSING, 300=WRONG_IDENTIFICATION, 400=FEATURE_REQUEST, 410=MORE_RECOMMENDATIONS */
    var category: Int = 0
    var comment: String? = null
}
```

- [ ] **步骤 2：FeedbackService**

```kotlin
package com.ifmix.api.core.modules.feedback.service

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.feedback.document.FeedbackDocument
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Instant

class FeedbackService(private val mongo: MongoTemplate) {

    fun submit(ctx: RequestContext, category: Int, comment: String?, scanRecordId: String?): String {
        val doc = FeedbackDocument().apply {
            this.appId = ctx.appId
            this.installId = ctx.installId ?: ""
            this.userId = ctx.userId
            this.scanRecordId = scanRecordId
            this.category = category
            this.comment = comment
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        mongo.insert(doc)
        return doc.id
    }
}
```

- [ ] **步骤 3：GraphQL schema + fetcher**

Schema 追加：

```graphql
input SubmitFeedbackInput { category: Int!, comment: String, scanRecordId: ID }

# Mutation
    submitFeedback(input: SubmitFeedbackInput!): ID! @requirePermission(permission: "feedback:write")
```

Fetcher：

```kotlin
@DgsComponent
class CustomerFeedbackFetcher(private val feedbackService: FeedbackService) {
    @DgsMutation
    fun submitFeedback(@InputArgument input: Map<String, Any?>, dfe: DgsDataFetchingEnvironment): String {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        return feedbackService.submit(
            ctx.requestContext,
            category = (input["category"] as Number).toInt(),
            comment = input["comment"] as? String,
            scanRecordId = input["scanRecordId"] as? String,
        )
    }
}
```

- [ ] **步骤 4：删除旧 REST controller + 旧 service/document 文件**

删除：`core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerFeedbackController.kt`

- [ ] **步骤 5：验证编译 + Commit**

```bash
./gradlew :core-api:compileKotlin
git add -A && git commit -m "feat(feedback): add feedback GraphQL mutation"
```

---

## 子计划 5：iap

**目标：** 实现 IAP 购买验证（verifyPurchase mutation）。Subscription + StoreNotification 两个集合。

**前置：** 无（独立模块）。

---

### 任务 5.1：创建 IAP Documents

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/iap/document/SubscriptionDocument.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/iap/document/StoreNotificationDocument.kt`

- [ ] **步骤 1：SubscriptionDocument**

```kotlin
package com.ifmix.api.core.modules.iap.document

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "subscriptions")
@CompoundIndex(name = "sub_pxid_idx", def = "{'appId': 1, 'subscriptionPxid': 1}", unique = true)
class SubscriptionDocument : BaseAppDocument() {
    lateinit var subscriptionPxid: String
    var originalTransactionId: String? = null
    var productId: String? = null
    /** 0=UNKNOWN, 100=APPLE, 200=GOOGLE */
    var platform: Int = 0
    var active: Boolean = false
    var subStatus: String? = null
    var expiryDate: Instant? = null
    var purchaseToken: String? = null
    var rawResponse: Map<String, Any?>? = null
}
```

- [ ] **步骤 2：StoreNotificationDocument**

```kotlin
package com.ifmix.api.core.modules.iap.document

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "store_notifications")
class StoreNotificationDocument : BaseAppDocument() {
    var platform: String? = null
    var subscriptionPxid: String? = null
    var purchaseToken: String? = null
    var notificationType: String? = null
    var rawPayload: String? = null
    var processed: Boolean = false
    var processedAt: Instant? = null
}
```

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "feat(iap): add Subscription and StoreNotification documents"
```

---

### 任务 5.2：创建 IapService + GraphQL

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/iap/repo/SubscriptionRepository.kt`
- 重写：`core-api/src/main/kotlin/com/ifmix/api/core/modules/iap/service/IapService.kt`（MongoDB 版）
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/customer/CustomerIapFetcher.kt`

- [ ] **步骤 1：SubscriptionRepository**

```kotlin
package com.ifmix.api.core.modules.iap.repo

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.iap.document.SubscriptionDocument
import com.ifmix.api.core.modules.iap.document.StoreNotificationDocument
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

class SubscriptionRepository(private val mongo: MongoTemplate) {

    fun findActiveByPxid(ctx: RequestContext, pxid: String): SubscriptionDocument? {
        val query = Query(Criteria.where("appId").`is`(ctx.appId)
            .and("subscriptionPxid").`is`(pxid)
            .and("active").`is`(true)
            .and("deletedAt").`is`(null))
        return mongo.findOne(query, SubscriptionDocument::class.java)
    }

    fun findByPxid(ctx: RequestContext, pxid: String): SubscriptionDocument? {
        val query = Query(Criteria.where("appId").`is`(ctx.appId)
            .and("subscriptionPxid").`is`(pxid)
            .and("deletedAt").`is`(null))
        return mongo.findOne(query, SubscriptionDocument::class.java)
    }

    fun upsert(ctx: RequestContext, doc: SubscriptionDocument) {
        doc.appId = ctx.appId
        mongo.save(doc)
    }

    fun existsNotificationByPlatformAndToken(platform: String, token: String): Boolean {
        val query = Query(Criteria.where("platform").`is`(platform).and("subscriptionPxid").`is`(token))
        return mongo.exists(query, StoreNotificationDocument::class.java)
    }

    fun saveNotification(ctx: RequestContext, doc: StoreNotificationDocument) {
        doc.appId = ctx.appId
        mongo.insert(doc)
    }
}
```

- [ ] **步骤 2：重写 IapService（逻辑不变，改用 MongoDB repo）**

沿用 main 分支的 `verifyPurchase` 和 `handleNotification` 逻辑，但调用 MongoDB repository 替代 Jimmer。UUID → ObjectId。核心流程：
1. 验证购买凭证（调用 PurchaseVerifier）
2. 查/创建 SubscriptionDocument
3. 返回 VerifyRes

- [ ] **步骤 3：GraphQL schema + fetcher**

Schema 追加：

```graphql
type VerifyPurchaseResult {
    expiresAt: Long
    state: String!
    productId: String!
    tier: String!
}

input VerifyPurchaseInput { platform: Int!, signedTransaction: String, purchaseToken: String, productId: String! }

# Mutation
    verifyPurchase(input: VerifyPurchaseInput!): VerifyPurchaseResult! @requirePermission(permission: "iap:write")
```

- [ ] **步骤 4：删除旧 REST controller**

删除：`core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerIapController.kt`

- [ ] **步骤 5：验证编译 + Commit**

```bash
./gradlew :core-api:compileKotlin
git add -A && git commit -m "feat(iap): add IAP GraphQL mutation with MongoDB"
```

---

## 子计划 6：appconfig

**目标：** 实现 AppConfigRevision 的版本管理（admin 操作）。

**前置：** 无（独立模块）。

---

### 任务 6.1：创建 AppConfig Documents

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/appconfig/document/AppConfigRevisionDocument.kt`

- [ ] **步骤 1：AppConfigRevisionDocument**

```kotlin
package com.ifmix.api.core.modules.appconfig.document

import com.ifmix.api.core.common.db.BaseDocument
import com.ifmix.api.core.common.db.AppScoped
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "app_config_revisions")
@CompoundIndex(name = "config_app_rev_idx", def = "{'appId': 1, 'revisionNumber': -1}")
class AppConfigRevisionDocument : BaseDocument(), AppScoped {
    override var appId: String = ""
    var authTenantId: String? = null
    var appleBundleId: String? = null
    var androidPackageName: String? = null
    var content: Map<String, Any?> = emptyMap()
    var revisionNumber: Int = 0
    var enabled: Boolean = false
    var slug: String = ""
    var note: String = ""
}
```

- [ ] **步骤 2：Commit**

```bash
git add -A && git commit -m "feat(appconfig): add AppConfigRevisionDocument"
```

---

### 任务 6.2：创建 AppConfigService + GraphQL

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/appconfig/repo/AppConfigRepository.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/appconfig/service/AppConfigService.kt`（重写）
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/admin/AdminAppConfigFetcher.kt`

- [ ] **步骤 1：AppConfigRepository**

```kotlin
package com.ifmix.api.core.modules.appconfig.repo

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.appconfig.document.AppConfigRevisionDocument
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update

class AppConfigRepository(private val mongo: MongoTemplate) {

    fun findCurrentRevision(ctx: RequestContext): AppConfigRevisionDocument? {
        val query = Query(Criteria.where("appId").`is`(ctx.appId).and("enabled").`is`(true))
        return mongo.findOne(query, AppConfigRevisionDocument::class.java)
    }

    fun disableAllEnabled(ctx: RequestContext) {
        val query = Query(Criteria.where("appId").`is`(ctx.appId).and("enabled").`is`(true))
        val update = Update().set("enabled", false)
        mongo.updateMulti(query, update, AppConfigRevisionDocument::class.java)
    }

    fun insert(ctx: RequestContext, doc: AppConfigRevisionDocument): String {
        doc.appId = ctx.appId
        mongo.insert(doc)
        return doc.id
    }

    fun findById(ctx: RequestContext, id: String): AppConfigRevisionDocument? {
        val query = Query(Criteria.where("_id").`is`(id).and("appId").`is`(ctx.appId))
        return mongo.findOne(query, AppConfigRevisionDocument::class.java)
    }

    fun updateEnabled(ctx: RequestContext, id: String, enabled: Boolean) {
        val query = Query(Criteria.where("_id").`is`(id).and("appId").`is`(ctx.appId))
        val update = Update().set("enabled", enabled)
        mongo.updateFirst(query, update, AppConfigRevisionDocument::class.java)
    }
}
```

- [ ] **步骤 2：AppConfigService**

```kotlin
package com.ifmix.api.core.modules.appconfig.service

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.appconfig.document.AppConfigRevisionDocument
import com.ifmix.api.core.modules.appconfig.repo.AppConfigRepository
import java.time.Instant

class AppConfigService(private val repo: AppConfigRepository) {

    fun createRevision(ctx: RequestContext, content: Map<String, Any?>, revisionNumber: Int,
                       enabled: Boolean, slug: String, note: String,
                       authTenantId: String?, appleBundleId: String?, androidPackageName: String?): AppConfigRevisionDocument {
        if (enabled) repo.disableAllEnabled(ctx)

        val doc = AppConfigRevisionDocument().apply {
            this.content = content
            this.revisionNumber = revisionNumber
            this.enabled = enabled
            this.slug = slug
            this.note = note
            this.authTenantId = authTenantId
            this.appleBundleId = appleBundleId
            this.androidPackageName = androidPackageName
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        repo.insert(ctx, doc)
        return doc
    }

    fun toggleRevision(ctx: RequestContext, id: String, enabled: Boolean): AppConfigRevisionDocument {
        repo.findById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND, "revision not found")
        if (enabled) repo.disableAllEnabled(ctx)
        repo.updateEnabled(ctx, id, enabled)
        return repo.findById(ctx, id)!!
    }

    fun getCurrentRevision(ctx: RequestContext): AppConfigRevisionDocument? = repo.findCurrentRevision(ctx)
}
```

- [ ] **步骤 3：GraphQL schema + admin fetcher**

Schema 追加：

```graphql
type AppConfigRevision {
    id: ID!
    appId: ID!
    content: JSON
    revisionNumber: Int!
    enabled: Boolean!
    slug: String!
    note: String!
    createdAt: DateTime!
}

input CreateAppConfigRevisionInput {
    content: JSON!
    revisionNumber: Int!
    enabled: Boolean!
    slug: String!
    note: String!
    authTenantId: ID
    appleBundleId: String
    androidPackageName: String
}

# Query (admin)
    currentAppConfig: AppConfigRevision

# Mutation (admin)
    createAppConfigRevision(input: CreateAppConfigRevisionInput!): AppConfigRevision! @requirePermission(permission: "appconfig:write")
    toggleAppConfigRevision(id: ID!, enabled: Boolean!): AppConfigRevision! @requirePermission(permission: "appconfig:write")
```

- [ ] **步骤 4：验证编译 + Commit**

```bash
./gradlew :core-api:compileKotlin
git add -A && git commit -m "feat(appconfig): add appconfig GraphQL admin API"
```

---

## 最终验证

- [ ] 所有模块编译通过：`./gradlew :core-api:compileKotlin`
- [ ] 应用可启动：`./gradlew :core-api:bootRun`
- [ ] GraphiQL 可用：`http://localhost:3001/graphiql`
- [ ] Schema 包含所有模块的 type/query/mutation
- [ ] 旧 REST controller 全部删除（保留 webhook + wellknown + auth）
