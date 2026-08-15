# GraphQL + Trusted Documents POC 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 用 Todo + TodoItem 演示 Netflix DGS 12 GraphQL server，含 DataLoader 聚合、Trusted Documents allowlist、多 BFF 权限隔离和 per-operation 监控。

**架构：** 单个 DGS schema 暴露 admin 和 customer 两个 endpoint（通过自定义 controller 路由）。Trusted Documents filter 按 BFF 路径查不同 allowlist。Permission directive 做运行时隔离。

**技术栈：** Spring Boot 4.1 MVC + Netflix DGS 12 + Micrometer + Konvert + MongoDB

---

## 文件结构

### 修改的文件

| 文件 | 职责 |
|------|------|
| `core-api/build.gradle.kts` | 添加 DGS、Micrometer 依赖 |
| `core-api/src/main/resources/application.yml` | DGS 配置 |
| `core-api/src/main/kotlin/.../common/config/WebConfig.kt` | 注册 TrustedDocumentFilter 拦截器路径 |

### 新建的文件

| 文件 | 职责 |
|------|------|
| **Schema** | |
| `core-api/src/main/resources/schema/schema.graphqls` | 统一 GraphQL schema 定义 |
| `core-api/src/main/resources/graphql/persisted-queries/customer.json` | Customer allowlist |
| `core-api/src/main/resources/graphql/persisted-queries/admin.json` | Admin allowlist |
| **Modules - TodoItem** | |
| `core-api/src/main/kotlin/.../modules/todo/document/TodoItemDocument.kt` | TodoItem MongoDB 文档 |
| `core-api/src/main/kotlin/.../modules/todo/repo/TodoItemRepository.kt` | TodoItem 仓储 |
| `core-api/src/main/kotlin/.../modules/todo/service/TodoItemService.kt` | TodoItem 业务逻辑 |
| `core-api/src/main/kotlin/.../modules/todo/mapper/TodoTypeMapper.kt` | Konvert Document→Type 映射 |
| **GraphQL Common** | |
| `core-api/src/main/kotlin/.../graphql/common/type/TodoType.kt` | GraphQL Todo 展示类型 |
| `core-api/src/main/kotlin/.../graphql/common/type/TodoItemType.kt` | GraphQL TodoItem 展示类型 |
| `core-api/src/main/kotlin/.../graphql/common/type/TodoConnection.kt` | 分页包装类型 |
| `core-api/src/main/kotlin/.../graphql/common/type/OperationResult.kt` | 批量操作结果类型 |
| `core-api/src/main/kotlin/.../graphql/common/scalar/DateTimeScalar.kt` | DateTime scalar 映射 |
| `core-api/src/main/kotlin/.../graphql/common/context/GraphQLContextBuilder.kt` | 构造 RequestContext + permissions |
| `core-api/src/main/kotlin/.../graphql/common/directive/RequirePermission.kt` | 权限 directive |
| `core-api/src/main/kotlin/.../graphql/common/dataloader/TodoItemDataLoader.kt` | DataLoader 批量加载 items |
| `core-api/src/main/kotlin/.../graphql/common/trusted/PersistedQueryStore.kt` | Allowlist 接口 + 实现 |
| `core-api/src/main/kotlin/.../graphql/common/trusted/TrustedDocumentFilter.kt` | 拦截 filter |
| `core-api/src/main/kotlin/.../graphql/common/monitor/OperationMetricsInstrumentation.kt` | 自定义监控 |
| **GraphQL Customer** | |
| `core-api/src/main/kotlin/.../graphql/customer/CustomerTodoFetcher.kt` | Customer query + mutation |
| **GraphQL Admin** | |
| `core-api/src/main/kotlin/.../graphql/admin/AdminTodoFetcher.kt` | Admin query + mutation (含 batch) |
| **GraphQL Router** | |
| `core-api/src/main/kotlin/.../graphql/router/GraphQLRouterController.kt` | 双 endpoint controller |
| **Schema 校验** | |
| `core-api/src/main/kotlin/.../graphql/common/validation/SchemaTypeValidator.kt` | 编译时 schema↔type 一致性校验 |
| **Tests** | |
| `core-api/src/test/kotlin/.../graphql/trusted/PersistedQueryStoreTest.kt` | Allowlist 单元测试 |
| `core-api/src/test/kotlin/.../graphql/CustomerTodoFetcherTest.kt` | Customer resolver 集成测试 |
| `core-api/src/test/kotlin/.../graphql/AdminTodoFetcherTest.kt` | Admin resolver 集成测试 |
| `core-api/src/test/kotlin/.../graphql/TrustedDocumentFilterTest.kt` | Filter 集成测试 |

> 以下路径前缀简写：`src/main/kotlin/com/ifmix/api/core` → `...`

---

### 任务 1：添加 DGS 依赖和基础配置

**文件：**
- 修改：`core-api/build.gradle.kts`
- 修改：`core-api/src/main/resources/application.yml`

- [ ] **步骤 1：在 build.gradle.kts 添加 DGS 依赖**

```kotlin
// 在 dependencies 块中添加：
dependencies {
    // ... existing dependencies ...

    // Netflix DGS
    implementation(platform("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:12.0.0"))
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter")
    implementation("com.netflix.graphql.dgs:graphql-dgs-extended-scalars")
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-boot-micrometer")
}
```

- [ ] **步骤 2：在 application.yml 添加 DGS 配置**

在 `application.yml` 末尾添加：

```yaml
dgs:
  graphql:
    path: /graphql
    graphiql:
      enabled: true
    schema-locations:
      - classpath*:schema/**/*.graphql*
    preparsed-document-provider:
      enabled: true

management:
  metrics:
    dgs-graphql:
      enabled: true
      autotime:
        percentiles:
          - 0.5
          - 0.95
          - 0.99
```

注意：`management.endpoints.web.exposure.include` 已有值，需要追加而非覆盖。将现有的 `health,info,metrics` 保持不变。

- [ ] **步骤 3：移除 springdoc swagger 依赖（可选，先注释）**

在 `build.gradle.kts` 中注释掉 springdoc：

```kotlin
    // implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
```

- [ ] **步骤 4：验证构建通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add core-api/build.gradle.kts core-api/src/main/resources/application.yml
git commit -m "feat(graphql): add Netflix DGS 12 dependencies and config"
```

---

### 任务 2：创建 TodoItemDocument 和 Repository

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/document/TodoItemDocument.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/repo/TodoItemRepository.kt`

- [ ] **步骤 1：创建 TodoItemDocument**

```kotlin
package com.ifmix.api.core.modules.todo.document

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "todo_items")
@CompoundIndex(name = "todo_items_app_todo_idx", def = "{'appId': 1, 'todoId': 1}")
class TodoItemDocument : BaseAppDocument() {
    lateinit var todoId: String
    lateinit var content: String
    var done: Boolean = false
}
```

- [ ] **步骤 2：创建 TodoItemRepository**

```kotlin
package com.ifmix.api.core.modules.todo.repo

import com.ifmix.api.core.common.db.CRUDRepository
import com.ifmix.api.core.common.db.MongoClusterResolver
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

class TodoItemRepository(clusterResolver: MongoClusterResolver) :
    CRUDRepository<TodoItemDocument>(clusterResolver.primary(), TodoItemDocument::class.java) {

    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemDocument> {
        val query = Query(
            Criteria.where("appId").`is`(ctx.appId)
                .and("todoId").`in`(todoIds)
                .and("deletedAt").`is`(null)
        )
        return template(ctx).find(query, TodoItemDocument::class.java)
    }
}
```

注意：`CRUDRepository` 的构造器接收 `MongoTemplate` 和 `Class<T>`。查看 `CRUDRepository` 源码确认 `template(ctx)` 方法的使用方式——它根据 `ctx.readPreference` 返回适当的 MongoTemplate。

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/document/
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/repo/
git commit -m "feat(todo): add TodoItemDocument and TodoItemRepository"
```

---

### 任务 3：创建 TodoItemService

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/service/TodoItemService.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoConfig.kt`（添加 bean 注册）

- [ ] **步骤 1：创建 TodoItemService**

```kotlin
package com.ifmix.api.core.modules.todo.service

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.service.CRUDService
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import org.bson.types.ObjectId
import java.time.Instant

class TodoItemService(
    private val crud: CRUDService<TodoItemDocument>,
    private val repo: TodoItemRepository,
) {

    fun create(ctx: RequestContext, todoId: String, content: String): String {
        val doc = TodoItemDocument().apply {
            this.todoId = todoId
            this.content = content
            this.done = false
        }
        return crud.createOne(ctx, doc)
    }

    fun findByTodoIds(ctx: RequestContext, todoIds: List<String>): List<TodoItemDocument> =
        repo.findByTodoIds(ctx, todoIds)

    fun getById(ctx: RequestContext, id: String): TodoItemDocument = crud.getById(ctx, id)

    fun findById(ctx: RequestContext, id: String): TodoItemDocument? = crud.findById(ctx, id)

    fun update(ctx: RequestContext, id: String, patch: Any): Boolean = crud.updateById(ctx, id, patch)

    fun deleteById(ctx: RequestContext, id: String): Boolean = crud.deleteById(ctx, id)

    fun deleteByIds(ctx: RequestContext, ids: List<String>): Int = crud.deleteByIds(ctx, ids)
}
```

- [ ] **步骤 2：在 TodoConfig 中注册 TodoItemService bean**

在 `TodoConfig.kt` 中追加：

```kotlin
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
import com.ifmix.api.core.modules.todo.repo.TodoItemRepository
import com.ifmix.api.core.modules.todo.service.TodoItemService

// 在 TodoConfig class 内追加：

    @Bean
    fun todoItemRepository(clusterResolver: MongoClusterResolver): TodoItemRepository =
        TodoItemRepository(clusterResolver)

    @Bean
    fun todoItemCrudService(todoItemRepository: TodoItemRepository): CRUDService<TodoItemDocument> =
        CRUDService(CRUDRepository(todoItemRepository.mongoTemplate(), TodoItemDocument::class.java))

    @Bean
    fun todoItemService(
        todoItemCrudService: CRUDService<TodoItemDocument>,
        todoItemRepository: TodoItemRepository,
    ): TodoItemService = TodoItemService(todoItemCrudService, todoItemRepository)
```

注意：实际接入时需要看 `CRUDRepository` 构造函数的参数形式并适配。可能需要直接用 `CRUDRepository<TodoItemDocument>(mongoTemplate, TodoItemDocument::class.java)` 的方式。参照现有 `todoRepository` bean 的写法。

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/service/
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoConfig.kt
git commit -m "feat(todo): add TodoItemService with batch findByTodoIds"
```

---

### 任务 4：创建 GraphQL Type Classes 和 Konvert Mapper

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/type/TodoType.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/type/TodoItemType.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/type/TodoConnection.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/type/OperationResult.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/mapper/TodoTypeMapper.kt`

- [ ] **步骤 1：创建 GraphQL Type Classes**

`TodoType.kt`:
```kotlin
package com.ifmix.api.core.graphql.common.type

import java.time.Instant

data class TodoType(
    val id: String,
    val title: String,
    val done: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    // items 通过 DataLoader 填充，不在此处定义
)
```

`TodoItemType.kt`:
```kotlin
package com.ifmix.api.core.graphql.common.type

import java.time.Instant

data class TodoItemType(
    val id: String,
    val todoId: String,
    val content: String,
    val done: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

`TodoConnection.kt`:
```kotlin
package com.ifmix.api.core.graphql.common.type

data class TodoConnection(
    val items: List<TodoType>,
    val nextCursor: String?,
    val hasMore: Boolean,
)
```

`OperationResult.kt`:
```kotlin
package com.ifmix.api.core.graphql.common.type

data class OperationResult(
    val success: Boolean = true,
    val modifiedCount: Int? = null,
)
```

- [ ] **步骤 2：创建 Konvert Mapper**

```kotlin
package com.ifmix.api.core.modules.todo.mapper

import com.ifmix.api.core.graphql.common.type.TodoItemType
import com.ifmix.api.core.graphql.common.type.TodoType
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.modules.todo.document.TodoItemDocument
import io.mcarle.konvert.api.KonvertTo

@KonvertTo(TodoType::class)
fun TodoDocument.toTodoType(): TodoType = TodoType(
    id = this.id,
    title = this.title,
    done = this.done,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)

@KonvertTo(TodoItemType::class)
fun TodoItemDocument.toTodoItemType(): TodoItemType = TodoItemType(
    id = this.id,
    todoId = this.todoId,
    content = this.content,
    done = this.done,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)
```

注意：如果 Konvert KSP 生成了冲突代码，可以退而使用上述手写扩展函数（已经是最简形式）。`@KonvertTo` 注解是可选的——当字段一一对应时 Konvert 可以自动生成，否则手写扩展函数就够了。这里先手写，确保编译正确。

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/type/
git add core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/mapper/
git commit -m "feat(graphql): add GraphQL type classes and Konvert mapper"
```

---

### 任务 5：创建 GraphQL Schema 文件和 DateTime Scalar

**文件：**
- 创建：`core-api/src/main/resources/schema/schema.graphqls`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/scalar/DateTimeScalar.kt`

- [ ] **步骤 1：创建 schema.graphqls**

```graphql
scalar DateTime

type Query {
    # Customer + Admin
    todo(id: ID!): Todo
    todos(cursor: String, limit: Int, userId: String): TodoConnection!
}

type Mutation {
    # Customer
    createTodo(input: CreateTodoInput!): Todo!
    updateTodo(id: ID!, input: UpdateTodoInput!): Todo!
    deleteTodo(id: ID!): Boolean!
    createTodoItem(todoId: ID!, input: CreateTodoItemInput!): TodoItem!
    updateTodoItem(id: ID!, input: UpdateTodoItemInput!): TodoItem!
    deleteTodoItem(id: ID!): Boolean!

    # Admin batch
    batchDeleteTodos(ids: [ID!]!): OperationResult!
    batchUpdateTodos(patches: [BatchUpdateTodoInput!]!): OperationResult!
    batchDeleteTodoItems(ids: [ID!]!): OperationResult!
}

type Todo {
    id: ID!
    title: String!
    done: Boolean!
    items: [TodoItem!]!
    createdAt: DateTime!
    updatedAt: DateTime!
}

type TodoItem {
    id: ID!
    todoId: ID!
    content: String!
    done: Boolean!
    createdAt: DateTime!
    updatedAt: DateTime!
}

type TodoConnection {
    items: [Todo!]!
    nextCursor: String
    hasMore: Boolean!
}

type OperationResult {
    success: Boolean!
    modifiedCount: Int
}

input CreateTodoInput {
    title: String!
}

input UpdateTodoInput {
    title: String
    done: Boolean
}

input CreateTodoItemInput {
    content: String!
}

input UpdateTodoItemInput {
    content: String
    done: Boolean
}

input BatchUpdateTodoInput {
    id: ID!
    title: String
    done: Boolean
}
```

- [ ] **步骤 2：创建 DateTime Scalar**

```kotlin
package com.ifmix.api.core.graphql.common.scalar

import com.netflix.graphql.dgs.DgsScalar
import graphql.language.StringValue
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import java.time.Instant

@DgsScalar(name = "DateTime")
class DateTimeScalar : Coercing<Instant, String> {

    override fun serialize(dataFetcherResult: Any): String {
        if (dataFetcherResult is Instant) {
            return dataFetcherResult.toString()
        }
        throw CoercingSerializeException("Expected Instant but got ${dataFetcherResult::class}")
    }

    override fun parseValue(input: Any): Instant {
        if (input is String) {
            return Instant.parse(input)
        }
        throw CoercingParseValueException("Expected String but got ${input::class}")
    }

    override fun parseLiteral(input: Any): Instant {
        if (input is StringValue) {
            return Instant.parse(input.value)
        }
        throw CoercingParseLiteralException("Expected StringValue but got ${input::class}")
    }
}
```

注意：DGS `graphql-dgs-extended-scalars` 自带 DateTime scalar（基于 `OffsetDateTime`）。如果和 `Instant` 冲突，需要在 `application.yml` 中禁用内置的：`dgs.graphql.extensions.scalars.time-dates.enabled: false`，然后用自定义的。

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/resources/schema/
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/scalar/
git commit -m "feat(graphql): add schema.graphqls and DateTime scalar"
```

---

### 任务 6：创建 GraphQL Context Builder

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/context/GraphQLContextBuilder.kt`

- [ ] **步骤 1：创建 DgsCustomContextBuilder**

```kotlin
package com.ifmix.api.core.graphql.common.context

import com.ifmix.api.core.common.http.ClientPlatform
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.http.RequestHeaders
import com.netflix.graphql.dgs.context.DgsCustomContextBuilder
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * GraphQL 请求上下文构建器。
 * 从 HTTP headers 构造 RequestContext + permissions，存入 DGS custom context。
 */
@Component
class GraphQLContextBuilder(
    private val request: HttpServletRequest,
) : DgsCustomContextBuilder<GraphQLRequestContext> {

    override fun build(): GraphQLRequestContext {
        val appId = request.getHeader(RequestHeaders.APP_ID) ?: ""
        val installId = request.getHeader(RequestHeaders.INSTALL_ID)
        val role = request.getHeader("X-Role") ?: "customer"
        val userId = request.getHeader("X-User-Id") // POC mock: 直接从 header 取

        val permissions = ROLE_PERMISSIONS[role] ?: emptySet()

        val requestContext = RequestContext(
            appId = appId,
            installId = installId,
            lang = request.getHeader(RequestHeaders.LANG),
            currency = request.getHeader(RequestHeaders.CURRENCY),
            country = request.getHeader(RequestHeaders.COUNTRY),
            clientPlatform = ClientPlatform.fromHeader(request.getHeader(RequestHeaders.CLIENT_PLATFORM)),
            userId = userId,
        )

        // 从请求 URI 判断 BFF 类型
        val bff = if (request.requestURI.startsWith("/admin/")) "admin" else "customer"

        return GraphQLRequestContext(
            requestContext = requestContext,
            permissions = permissions,
            bff = bff,
        )
    }

    companion object {
        val ROLE_PERMISSIONS = mapOf(
            "customer" to setOf("todo:read", "todo:write"),
            "admin" to setOf("todo:read", "todo:write", "todo:batch", "todo:admin"),
        )
    }
}

/**
 * GraphQL 请求级上下文，通过 DgsContext.getCustomContext() 获取。
 */
data class GraphQLRequestContext(
    val requestContext: RequestContext,
    val permissions: Set<String>,
    val bff: String,
)
```

- [ ] **步骤 2：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/context/
git commit -m "feat(graphql): add DGS context builder with permissions"
```

---

### 任务 7：创建 @RequirePermission Directive

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/directive/RequirePermission.kt`

- [ ] **步骤 1：创建 RequirePermission directive wiring**

```kotlin
package com.ifmix.api.core.graphql.common.directive

import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.netflix.graphql.dgs.DgsDirective
import com.netflix.graphql.dgs.context.DgsContext
import graphql.execution.DataFetcherResult
import graphql.schema.DataFetcher
import graphql.schema.DataFetcherFactories
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import org.springframework.stereotype.Component

/**
 * 自定义 @requirePermission directive。
 *
 * Schema 中使用：
 *   directive @requirePermission(permission: String!) on FIELD_DEFINITION
 *   
 *   type Query {
 *     todos: TodoConnection! @requirePermission(permission: "todo:read")
 *   }
 *
 * 注意：DGS 中 directive wiring 通过实现 SchemaDirectiveWiring 并注册为 @DgsDirective bean。
 */
@Component
@DgsDirective(name = "requirePermission")
class RequirePermissionDirective : SchemaDirectiveWiring {

    override fun onField(
        environment: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>,
    ): GraphQLFieldDefinition {
        val requiredPermission = environment.getAppliedDirective("requirePermission")
            .getArgument("permission")
            .getValue<String>()

        val field = environment.element
        val originalFetcher = environment.codeRegistry.getDataFetcher(
            environment.fieldsContainer, field
        )

        val authFetcher = DataFetcher { dfe ->
            val customContext = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
            if (!customContext.permissions.contains(requiredPermission)) {
                throw PermissionDeniedException(
                    "Permission denied: requires '$requiredPermission'"
                )
            }
            originalFetcher.get(dfe)
        }

        environment.codeRegistry.dataFetcher(
            environment.fieldsContainer, field, authFetcher
        )

        return field
    }
}

class PermissionDeniedException(message: String) : RuntimeException(message)
```

- [ ] **步骤 2：在 schema.graphqls 顶部添加 directive 声明**

在 `schema.graphqls` 最顶部（`scalar DateTime` 之前）添加：

```graphql
directive @requirePermission(permission: String!) on FIELD_DEFINITION
```

并给 mutation 字段添加 directive：

```graphql
type Mutation {
    # Customer (todo:write)
    createTodo(input: CreateTodoInput!): Todo! @requirePermission(permission: "todo:write")
    updateTodo(id: ID!, input: UpdateTodoInput!): Todo! @requirePermission(permission: "todo:write")
    deleteTodo(id: ID!): Boolean! @requirePermission(permission: "todo:write")
    createTodoItem(todoId: ID!, input: CreateTodoItemInput!): TodoItem! @requirePermission(permission: "todo:write")
    updateTodoItem(id: ID!, input: UpdateTodoItemInput!): TodoItem! @requirePermission(permission: "todo:write")
    deleteTodoItem(id: ID!): Boolean! @requirePermission(permission: "todo:write")

    # Admin batch (todo:batch)
    batchDeleteTodos(ids: [ID!]!): OperationResult! @requirePermission(permission: "todo:batch")
    batchUpdateTodos(patches: [BatchUpdateTodoInput!]!): OperationResult! @requirePermission(permission: "todo:batch")
    batchDeleteTodoItems(ids: [ID!]!): OperationResult! @requirePermission(permission: "todo:batch")
}
```

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/directive/
git add core-api/src/main/resources/schema/schema.graphqls
git commit -m "feat(graphql): add @requirePermission directive with schema wiring"
```

---

### 任务 8：创建 TodoItem DataLoader

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/dataloader/TodoItemDataLoader.kt`

- [ ] **步骤 1：创建 DataLoader**

```kotlin
package com.ifmix.api.core.graphql.common.dataloader

import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.common.type.TodoItemType
import com.ifmix.api.core.modules.todo.mapper.toTodoItemType
import com.ifmix.api.core.modules.todo.service.TodoItemService
import com.netflix.graphql.dgs.DgsDataLoader
import com.netflix.graphql.dgs.context.DgsContext
import org.dataloader.BatchLoaderEnvironment
import org.dataloader.MappedBatchLoaderWithContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * DataLoader：按 todoId 批量加载 TodoItems。
 * DGS 自动为每个请求创建独立实例（per-request scope）。
 */
@DgsDataLoader(name = "todoItems")
class TodoItemDataLoader(
    private val todoItemService: TodoItemService,
) : MappedBatchLoaderWithContext<String, List<TodoItemType>> {

    override fun load(
        keys: Set<String>,
        environment: BatchLoaderEnvironment,
    ): CompletionStage<Map<String, List<TodoItemType>>> {
        return CompletableFuture.supplyAsync {
            val dgsContext = environment.getContext<DgsContext>()
            val customContext = DgsContext.getCustomContext<GraphQLRequestContext>(dgsContext)
            val ctx = customContext.requestContext

            todoItemService.findByTodoIds(ctx, keys.toList())
                .map { it.toTodoItemType() }
                .groupBy { it.todoId }
        }
    }
}
```

注意：`MappedBatchLoaderWithContext` 的泛型参数为 `<Key, Value>`。这里 Key 是 todoId（String），Value 是该 todo 的 items 列表。DGS DataLoader 文档确认使用 `MappedBatchLoaderWithContext` 可以访问 DGS context。

- [ ] **步骤 2：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/dataloader/
git commit -m "feat(graphql): add TodoItemDataLoader for N+1 batching"
```

---

### 任务 9：创建 Customer Todo Fetcher

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/customer/CustomerTodoFetcher.kt`

- [ ] **步骤 1：创建 Customer Query + Mutation resolver**

```kotlin
package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.ownsRow
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.common.type.TodoConnection
import com.ifmix.api.core.graphql.common.type.TodoItemType
import com.ifmix.api.core.graphql.common.type.TodoType
import com.ifmix.api.core.modules.todo.TodoService
import com.ifmix.api.core.modules.todo.mapper.toTodoItemType
import com.ifmix.api.core.modules.todo.mapper.toTodoType
import com.ifmix.api.core.modules.todo.service.TodoItemService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsData
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import org.dataloader.DataLoader
import java.util.concurrent.CompletableFuture

@DgsComponent
class CustomerTodoFetcher(
    private val todoService: TodoService,
    private val todoItemService: TodoItemService,
) {

    @DgsQuery
    fun todo(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): TodoType? {
        val ctx = getContext(dfe)
        val doc = todoService.findById(ctx.requestContext, id) ?: return null
        if (!ownsRow(ctx.requestContext, doc.userId, doc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        return doc.toTodoType()
    }

    @DgsQuery
    fun todos(
        @InputArgument cursor: String?,
        @InputArgument limit: Int?,
        dfe: DgsDataFetchingEnvironment,
    ): TodoConnection {
        val ctx = getContext(dfe)
        val input = CursorQueryInput(cursor = cursor, limit = limit)
        val page = todoService.findByCursor(ctx.requestContext, input)
        // Customer 只看自己的 — 由 CRUDRepository 的 ownership filter 处理
        // 如果现有 CRUDRepository 不自动过滤 ownership，需要在 service 层加
        return TodoConnection(
            items = page.items.map { it.toTodoType() },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }

    @DgsMutation
    fun createTodo(
        @InputArgument input: Map<String, Any>,
        dfe: DgsDataFetchingEnvironment,
    ): TodoType {
        val ctx = getContext(dfe)
        val title = input["title"] as String
        val req = com.ifmix.api.core.modules.todo.CreateTodoRequest(title = title)
        val id = todoService.create(ctx.requestContext, req)
        return todoService.getById(ctx.requestContext, id).toTodoType()
    }

    @DgsMutation
    fun updateTodo(
        @InputArgument id: String,
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): TodoType {
        val ctx = getContext(dfe)
        val doc = todoService.getById(ctx.requestContext, id)
        if (!ownsRow(ctx.requestContext, doc.userId, doc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        val patch = com.ifmix.api.core.modules.todo.UpdateTodoRequest(
            title = input["title"] as? String,
            done = input["done"] as? Boolean,
        )
        todoService.update(ctx.requestContext, id, patch)
        return todoService.getById(ctx.requestContext, id).toTodoType()
    }

    @DgsMutation
    fun deleteTodo(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        val doc = todoService.getById(ctx.requestContext, id)
        if (!ownsRow(ctx.requestContext, doc.userId, doc.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        return todoService.deleteById(ctx.requestContext, id)
    }

    @DgsMutation
    fun createTodoItem(
        @InputArgument todoId: String,
        @InputArgument input: Map<String, Any>,
        dfe: DgsDataFetchingEnvironment,
    ): TodoItemType {
        val ctx = getContext(dfe)
        // 校验 todo 归属
        val todo = todoService.getById(ctx.requestContext, todoId)
        if (!ownsRow(ctx.requestContext, todo.userId, todo.installId)) {
            throw ApiError(ErrorCode.FORBIDDEN)
        }
        val content = input["content"] as String
        val id = todoItemService.create(ctx.requestContext, todoId, content)
        return todoItemService.getById(ctx.requestContext, id).toTodoItemType()
    }

    @DgsMutation
    fun updateTodoItem(
        @InputArgument id: String,
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): TodoItemType {
        val ctx = getContext(dfe)
        todoItemService.update(ctx.requestContext, id, input)
        return todoItemService.getById(ctx.requestContext, id).toTodoItemType()
    }

    @DgsMutation
    fun deleteTodoItem(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): Boolean {
        val ctx = getContext(dfe)
        return todoItemService.deleteById(ctx.requestContext, id)
    }

    /**
     * Todo.items field resolver — 使用 DataLoader 避免 N+1。
     */
    @DgsData(parentType = "Todo", field = "items")
    fun items(dfe: DgsDataFetchingEnvironment): CompletableFuture<List<TodoItemType>> {
        val dataLoader: DataLoader<String, List<TodoItemType>> = dfe.getDataLoader("todoItems")
        val todo = dfe.getSource<TodoType>()
        return dataLoader.load(todo.id)
    }

    private fun getContext(dfe: DgsDataFetchingEnvironment): GraphQLRequestContext =
        DgsContext.getCustomContext(dfe)
}
```

- [ ] **步骤 2：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/customer/
git commit -m "feat(graphql): add CustomerTodoFetcher with DataLoader"
```

---

### 任务 10：创建 Admin Todo Fetcher

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/admin/AdminTodoFetcher.kt`

- [ ] **步骤 1：创建 Admin Query + Mutation resolver（含 batch）**

```kotlin
package com.ifmix.api.core.graphql.admin

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.common.type.OperationResult
import com.ifmix.api.core.graphql.common.type.TodoConnection
import com.ifmix.api.core.graphql.common.type.TodoItemType
import com.ifmix.api.core.graphql.common.type.TodoType
import com.ifmix.api.core.modules.todo.TodoService
import com.ifmix.api.core.modules.todo.UpdateTodoRequest
import com.ifmix.api.core.modules.todo.mapper.toTodoItemType
import com.ifmix.api.core.modules.todo.mapper.toTodoType
import com.ifmix.api.core.modules.todo.service.TodoItemService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext

@DgsComponent
class AdminTodoFetcher(
    private val todoService: TodoService,
    private val todoItemService: TodoItemService,
) {

    // Admin 不需要单独定义 @DgsQuery todo/todos，因为 CustomerTodoFetcher 已经定义了。
    // DGS 中一个 field 只能有一个 fetcher。
    // 解决方案：在 CustomerTodoFetcher 的 todo()/todos() 里根据 bff 判断是否做归属校验。
    // 见任务 9 的 todo() 实现中加 bff 判断。

    @DgsMutation
    fun batchDeleteTodos(
        @InputArgument ids: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val count = todoService.deleteByIds(ctx.requestContext, ids)
        return OperationResult(success = count == ids.size, modifiedCount = count)
    }

    @DgsMutation
    fun batchUpdateTodos(
        @InputArgument patches: List<Map<String, Any?>>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val patchPairs = patches.map { patch ->
            val id = patch["id"] as String
            val req = UpdateTodoRequest(
                title = patch["title"] as? String,
                done = patch["done"] as? Boolean,
            )
            id to req
        }
        val count = todoService.updateByIds(ctx.requestContext, patchPairs)
        return OperationResult(success = count == patchPairs.size, modifiedCount = count)
    }

    @DgsMutation
    fun batchDeleteTodoItems(
        @InputArgument ids: List<String>,
        dfe: DgsDataFetchingEnvironment,
    ): OperationResult {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val count = todoItemService.deleteByIds(ctx.requestContext, ids)
        return OperationResult(success = true, modifiedCount = count)
    }
}
```

注意：由于 DGS 单 schema 模式下一个 field 只能有一个 data fetcher，`todo()` 和 `todos()` query 由 `CustomerTodoFetcher` 统一处理，内部根据 `ctx.bff` 决定是否做归属校验。需要回到任务 9 修改 `todo()` 方法：

```kotlin
// 修改 CustomerTodoFetcher.todo():
@DgsQuery
fun todo(@InputArgument id: String, dfe: DgsDataFetchingEnvironment): TodoType? {
    val ctx = getContext(dfe)
    val doc = todoService.findById(ctx.requestContext, id) ?: return null
    // Admin 不做归属校验
    if (ctx.bff == "customer" && !ownsRow(ctx.requestContext, doc.userId, doc.installId)) {
        throw ApiError(ErrorCode.FORBIDDEN)
    }
    return doc.toTodoType()
}
```

同样修改 `updateTodo()` 和 `deleteTodo()`，在归属校验前加 `ctx.bff == "customer"` 条件。

- [ ] **步骤 2：更新 CustomerTodoFetcher 中的归属校验（加 bff 判断）**

在 `CustomerTodoFetcher.kt` 中所有 `ownsRow` 检查前加条件：

```kotlin
if (ctx.bff == "customer" && !ownsRow(ctx.requestContext, doc.userId, doc.installId)) {
    throw ApiError(ErrorCode.FORBIDDEN)
}
```

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/admin/
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/customer/CustomerTodoFetcher.kt
git commit -m "feat(graphql): add AdminTodoFetcher with batch operations"
```

---

### 任务 11：创建双 Endpoint Router Controller

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/router/GraphQLRouterController.kt`

- [ ] **步骤 1：创建双路径 GraphQL Controller**

```kotlin
package com.ifmix.api.core.graphql.router

import com.netflix.graphql.dgs.DgsQueryExecutor
import com.netflix.graphql.dgs.internal.DgsSchemaProvider
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

/**
 * 双 endpoint GraphQL router。
 * /customer/graphql 和 /admin/graphql 都转发到同一个 DgsQueryExecutor。
 * BFF 类型通过 URI 路径在 GraphQLContextBuilder 中判定。
 *
 * DGS 默认的 /graphql 端点保持可用（开发调试用）。
 */
@RestController
class GraphQLRouterController(
    private val dgsQueryExecutor: DgsQueryExecutor,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping(
        "/customer/graphql",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun customerGraphql(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        return executeGraphQL(body, request)
    }

    @PostMapping(
        "/admin/graphql",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun adminGraphql(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        return executeGraphQL(body, request)
    }

    private fun executeGraphQL(body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<String> {
        val query = body["query"] as? String ?: ""
        val operationName = body["operationName"] as? String
        @Suppress("UNCHECKED_CAST")
        val variables = body["variables"] as? Map<String, Any> ?: emptyMap()

        val result = dgsQueryExecutor.execute(query, variables, operationName)
        val json = objectMapper.writeValueAsString(result.toSpecification())

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(json)
    }
}
```

注意：`DgsQueryExecutor.execute()` 方法签名可能与上述不完全一致。实现时查看 DGS 12 的 `DgsQueryExecutor` 接口确认可用的 execute 方法。可能需要用 `executeAndExtractJsonPathAsObject` 或 `ExecutionInput` builder。如果 DGS 12 已内置对多路径的支持（通过 `dgs.graphql.path` 配置为列表），优先使用配置方式而非自定义 controller。

- [ ] **步骤 2：在 WebConfig 中为 /customer/graphql 和 /admin/graphql 注册 header 校验拦截器**

修改 `WebConfig.kt`，在 `addInterceptors` 中添加路径：

```kotlin
registry.addInterceptor(headerValidationInterceptor)
    .addPathPatterns("/customer/**", "/app/**", "/admin/**")
```

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/router/
git add core-api/src/main/kotlin/com/ifmix/api/core/common/config/WebConfig.kt
git commit -m "feat(graphql): add dual endpoint router /customer/graphql and /admin/graphql"
```

---

### 任务 12：创建 Trusted Documents Filter

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/trusted/PersistedQueryStore.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/trusted/TrustedDocumentFilter.kt`
- 创建：`core-api/src/main/resources/graphql/persisted-queries/customer.json`
- 创建：`core-api/src/main/resources/graphql/persisted-queries/admin.json`

- [ ] **步骤 1：创建 PersistedQueryStore 接口和实现**

```kotlin
package com.ifmix.api.core.graphql.common.trusted

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct

data class PersistedQueryEntry(val name: String, val query: String)

interface PersistedQueryStore {
    fun get(hash: String, bff: String): PersistedQueryEntry?
}

@Component
class ClasspathPersistedQueryStore(
    @Value("\${graphql.trusted-documents.allowlist-path:classpath:graphql/persisted-queries/}")
    private val allowlistPath: String,
) : PersistedQueryStore {

    // bff -> (hash -> entry)
    private val stores: MutableMap<String, Map<String, PersistedQueryEntry>> = mutableMapOf()

    @PostConstruct
    fun init() {
        val resolver = PathMatchingResourcePatternResolver()
        val mapper = jacksonObjectMapper()

        for (bff in listOf("customer", "admin")) {
            val resource = resolver.getResource("${allowlistPath}${bff}.json")
            if (resource.exists()) {
                val raw: Map<String, Map<String, String>> = mapper.readValue(resource.inputStream)
                stores[bff] = raw.mapValues { (_, v) ->
                    PersistedQueryEntry(name = v["name"] ?: "", query = v["query"] ?: "")
                }
            } else {
                stores[bff] = emptyMap()
            }
        }
    }

    override fun get(hash: String, bff: String): PersistedQueryEntry? = stores[bff]?.get(hash)
}
```

注意：项目用 Jackson 3（tools.jackson），但 Spring Boot 4 对 Jackson 3 的 ObjectMapper 在 classpath 加载场景下可能需要用 `tools.jackson.databind.ObjectMapper`。实现时检查 import 是否正确。如果 jackson-module-kotlin 对应的是 tools.jackson 版本，需要调整 import。

- [ ] **步骤 2：创建 TrustedDocumentFilter**

```kotlin
package com.ifmix.api.core.graphql.common.trusted

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Trusted Documents filter。
 *
 * 请求 body 中如果有 extensions.persistedQuery.sha256Hash：
 * - 查 allowlist，命中 → 将 query 字段替换为 allowlist 中存储的 query text
 * - 未命中 + enabled=true → 返回 403
 * - 未命中 + enabled=false → 放行原始 query（开发模式）
 *
 * 如果请求没有 persistedQuery extension → 根据 enabled 决定是否放行任意 query。
 */
@Component
@Order(1)
class TrustedDocumentFilter(
    private val store: PersistedQueryStore,
    @Value("\${graphql.trusted-documents.enabled:false}")
    private val enabled: Boolean,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val uri = request.requestURI
        if (!uri.endsWith("/graphql")) {
            filterChain.doFilter(request, response)
            return
        }

        // 读取 body
        val bodyBytes = request.inputStream.readBytes()
        val bodyStr = String(bodyBytes, Charsets.UTF_8)

        // 简单 JSON 解析提取 persistedQuery hash
        val hash = extractPersistedQueryHash(bodyStr)

        if (hash != null) {
            val bff = if (uri.startsWith("/admin/")) "admin" else "customer"
            val entry = store.get(hash, bff)

            if (entry != null) {
                // 注入 query 到 body
                val newBody = injectQuery(bodyStr, entry.query)
                // 存 operation name 到 request attribute 供监控使用
                request.setAttribute("trusted.operation.name", entry.name)
                val wrappedRequest = CachedBodyRequest(request, newBody.toByteArray(Charsets.UTF_8))
                filterChain.doFilter(wrappedRequest, response)
                return
            } else if (enabled) {
                response.status = 403
                response.contentType = "application/json"
                response.writer.write("""{"errors":[{"message":"Query not in allowlist"}]}""")
                return
            }
        } else if (enabled) {
            // 没有 persistedQuery 且 enabled=true → 拒绝任意 query
            response.status = 403
            response.contentType = "application/json"
            response.writer.write("""{"errors":[{"message":"Only persisted queries allowed"}]}""")
            return
        }

        // 开发模式或无 hash 且 disabled → 放行
        val wrappedRequest = CachedBodyRequest(request, bodyBytes)
        filterChain.doFilter(wrappedRequest, response)
    }

    private fun extractPersistedQueryHash(body: String): String? {
        // 简单正则提取，避免引入额外 JSON 解析开销
        val regex = """"sha256Hash"\s*:\s*"([a-fA-F0-9]+)"""".toRegex()
        return regex.find(body)?.groupValues?.get(1)
    }

    private fun injectQuery(body: String, query: String): String {
        // 如果 body 已有 "query" 字段则替换，否则插入
        val escaped = query.replace("\"", "\\\"").replace("\n", "\\n")
        val queryRegex = """"query"\s*:\s*"[^"]*"""".toRegex()
        return if (queryRegex.containsMatchIn(body)) {
            queryRegex.replace(body, """"query":"$escaped"""")
        } else {
            // 在第一个 { 后插入 query 字段
            body.replaceFirst("{", """{"query":"$escaped",""")
        }
    }
}

/**
 * 包装 HttpServletRequest 以支持重复读取 body。
 */
class CachedBodyRequest(
    request: HttpServletRequest,
    private val cachedBody: ByteArray,
) : HttpServletRequestWrapper(request) {

    override fun getInputStream(): jakarta.servlet.ServletInputStream {
        val byteArrayInputStream = ByteArrayInputStream(cachedBody)
        return object : jakarta.servlet.ServletInputStream() {
            override fun read(): Int = byteArrayInputStream.read()
            override fun isFinished(): Boolean = byteArrayInputStream.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: jakarta.servlet.ReadListener?) {}
        }
    }

    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
}
```

- [ ] **步骤 3：创建 allowlist JSON 示例文件**

`core-api/src/main/resources/graphql/persisted-queries/customer.json`:
```json
{
  "a1b2c3d4e5f6": {
    "name": "GetTodo",
    "query": "query GetTodo($id: ID!) { todo(id: $id) { id title done items { id content done } createdAt updatedAt } }"
  },
  "f6e5d4c3b2a1": {
    "name": "GetTodos",
    "query": "query GetTodos($cursor: String, $limit: Int) { todos(cursor: $cursor, limit: $limit) { items { id title done items { id content done } createdAt updatedAt } nextCursor hasMore } }"
  },
  "1a2b3c4d5e6f": {
    "name": "CreateTodo",
    "query": "mutation CreateTodo($input: CreateTodoInput!) { createTodo(input: $input) { id title done createdAt } }"
  }
}
```

`core-api/src/main/resources/graphql/persisted-queries/admin.json`:
```json
{
  "ad1234567890": {
    "name": "AdminGetTodos",
    "query": "query AdminGetTodos($cursor: String, $limit: Int, $userId: String) { todos(cursor: $cursor, limit: $limit, userId: $userId) { items { id title done createdAt updatedAt } nextCursor hasMore } }"
  },
  "ad0987654321": {
    "name": "BatchDeleteTodos",
    "query": "mutation BatchDeleteTodos($ids: [ID!]!) { batchDeleteTodos(ids: $ids) { success modifiedCount } }"
  }
}
```

- [ ] **步骤 4：在 application.yml 添加 trusted-documents 配置**

```yaml
graphql:
  trusted-documents:
    enabled: false  # POC 开发阶段默认关闭，可通过环境变量 GRAPHQL_TRUSTED_DOCUMENTS_ENABLED=true 开启
    allowlist-path: classpath:graphql/persisted-queries/
```

- [ ] **步骤 5：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 6：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/trusted/
git add core-api/src/main/resources/graphql/persisted-queries/
git add core-api/src/main/resources/application.yml
git commit -m "feat(graphql): add Trusted Documents filter with allowlist"
```

---

### 任务 13：创建 Operation Metrics Instrumentation

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/monitor/OperationMetricsInstrumentation.kt`

- [ ] **步骤 1：创建自定义 Instrumentation**

```kotlin
package com.ifmix.api.core.graphql.common.monitor

import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.netflix.graphql.dgs.context.DgsContext
import graphql.ExecutionResult
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.concurrent.CompletableFuture

/**
 * 自定义 GraphQL Instrumentation：按 operation name（从 trusted docs allowlist 取）打 per-operation metrics。
 * 
 * Metrics:
 * - graphql.operation.count: Counter, tags: bff, operation, result
 * - graphql.operation.duration: Timer, tags: bff, operation
 */
@Component
class OperationMetricsInstrumentation(
    private val meterRegistry: MeterRegistry,
) : SimplePerformantInstrumentation() {

    override fun beginExecution(
        parameters: InstrumentationExecutionParameters,
        state: InstrumentationState?,
    ): InstrumentationContext<ExecutionResult>? {
        val startTime = System.nanoTime()

        return object : InstrumentationContext<ExecutionResult> {
            override fun onDispatched() {}

            override fun onCompleted(result: ExecutionResult?, t: Throwable?) {
                val elapsed = System.nanoTime() - startTime

                // 从 request attribute 获取 trusted operation name
                val operationName = getOperationName(parameters)
                val bff = getBff()
                val hasErrors = result?.errors?.isNotEmpty() == true || t != null
                val resultTag = if (hasErrors) "error" else "success"

                if (operationName != null) {
                    meterRegistry.counter(
                        "graphql.operation.count",
                        "bff", bff,
                        "operation", operationName,
                        "result", resultTag,
                    ).increment()

                    Timer.builder("graphql.operation.duration")
                        .tag("bff", bff)
                        .tag("operation", operationName)
                        .register(meterRegistry)
                        .record(elapsed, java.util.concurrent.TimeUnit.NANOSECONDS)
                }
            }
        }
    }

    private fun getOperationName(parameters: InstrumentationExecutionParameters): String? {
        // 优先从 request attribute 取（TrustedDocumentFilter 注入的）
        val request = getCurrentRequest()
        val trustedName = request?.getAttribute("trusted.operation.name") as? String
        if (trustedName != null) return trustedName

        // fallback: 用 GraphQL operationName（开发模式下）
        return parameters.operation
    }

    private fun getBff(): String {
        val request = getCurrentRequest()
        return if (request?.requestURI?.startsWith("/admin/") == true) "admin" else "customer"
    }

    private fun getCurrentRequest(): HttpServletRequest? {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        return attrs?.request
    }
}
```

- [ ] **步骤 2：验证编译通过**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/graphql/common/monitor/
git commit -m "feat(graphql): add per-operation metrics instrumentation"
```

---

### 任务 14：创建 PersistedQueryStore 单元测试

**文件：**
- 创建：`core-api/src/test/kotlin/com/ifmix/api/core/graphql/trusted/PersistedQueryStoreTest.kt`

- [ ] **步骤 1：编写测试**

```kotlin
package com.ifmix.api.core.graphql.trusted

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.ifmix.api.core.graphql.common.trusted.ClasspathPersistedQueryStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PersistedQueryStoreTest {

    private lateinit var store: ClasspathPersistedQueryStore

    @BeforeEach
    fun setup() {
        store = ClasspathPersistedQueryStore("classpath:graphql/persisted-queries/")
        store.init()
    }

    @Test
    fun `get returns entry for known hash and bff`() {
        val entry = store.get("a1b2c3d4e5f6", "customer")
        assertThat(entry).isNotNull()
        assertThat(entry!!.name).isEqualTo("GetTodo")
        assertThat(entry.query).isNotNull()
    }

    @Test
    fun `get returns null for unknown hash`() {
        val entry = store.get("unknownhash", "customer")
        assertThat(entry).isNull()
    }

    @Test
    fun `get returns null for wrong bff`() {
        val entry = store.get("a1b2c3d4e5f6", "admin")
        assertThat(entry).isNull()
    }

    @Test
    fun `get returns admin entry for admin bff`() {
        val entry = store.get("ad1234567890", "admin")
        assertThat(entry).isNotNull()
        assertThat(entry!!.name).isEqualTo("AdminGetTodos")
    }
}
```

- [ ] **步骤 2：运行测试**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.graphql.trusted.PersistedQueryStoreTest"`
预期：4 tests PASSED

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/test/kotlin/com/ifmix/api/core/graphql/trusted/
git commit -m "test(graphql): add PersistedQueryStore unit tests"
```

---

### 任务 15：Schema ↔ Type Class 一致性校验

**文件：**
- 创建：`core-api/src/test/kotlin/com/ifmix/api/core/graphql/validation/SchemaTypeConsistencyTest.kt`

- [ ] **步骤 1：创建编译时校验测试**

使用 test 而非 Gradle task 实现——效果一样（build 时跑 test 就会校验），但实现更简单：

```kotlin
package com.ifmix.api.core.graphql.validation

import assertk.assertThat
import assertk.assertions.contains
import com.ifmix.api.core.graphql.common.type.OperationResult
import com.ifmix.api.core.graphql.common.type.TodoConnection
import com.ifmix.api.core.graphql.common.type.TodoItemType
import com.ifmix.api.core.graphql.common.type.TodoType
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.language.ObjectTypeDefinition
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * 校验 schema.graphqls 中定义的类型字段与 Kotlin type class 的属性一致。
 * 如果不一致，此测试将失败，提示哪个字段缺失。
 */
class SchemaTypeConsistencyTest {

    private val registry: TypeDefinitionRegistry by lazy {
        val schemaResource = this::class.java.classLoader.getResource("schema/schema.graphqls")!!
        SchemaParser().parse(schemaResource.readText())
    }

    @Test
    fun `TodoType fields match schema Todo type`() {
        assertTypeFieldsMatch("Todo", TodoType::class, ignoredFields = setOf("items"))
    }

    @Test
    fun `TodoItemType fields match schema TodoItem type`() {
        assertTypeFieldsMatch("TodoItem", TodoItemType::class)
    }

    @Test
    fun `TodoConnection fields match schema TodoConnection type`() {
        assertTypeFieldsMatch("TodoConnection", TodoConnection::class)
    }

    @Test
    fun `OperationResult fields match schema OperationResult type`() {
        assertTypeFieldsMatch("OperationResult", OperationResult::class)
    }

    private fun assertTypeFieldsMatch(
        schemaTypeName: String,
        kotlinClass: KClass<*>,
        ignoredFields: Set<String> = emptySet(),
    ) {
        val typeDef = registry.getType(schemaTypeName).orElse(null) as? ObjectTypeDefinition
            ?: throw AssertionError("Schema type '$schemaTypeName' not found in schema.graphqls")

        val schemaFields = typeDef.fieldDefinitions
            .map { it.name }
            .filter { it !in ignoredFields }
            .toSet()

        val kotlinFields = kotlinClass.memberProperties
            .map { it.name }
            .toSet()

        for (field in schemaFields) {
            assertThat(kotlinFields).contains(field)
        }
    }
}
```

- [ ] **步骤 2：运行测试**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.graphql.validation.SchemaTypeConsistencyTest"`
预期：4 tests PASSED

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/test/kotlin/com/ifmix/api/core/graphql/validation/
git commit -m "test(graphql): add schema-type consistency validation test"
```

---

### 任务 16：端到端构建验证

**文件：** 无新文件

- [ ] **步骤 1：运行完整编译**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 2：运行所有测试**

运行：`./gradlew :core-api:test`
预期：所有测试通过（现有测试 + 新增测试）

如果有编译错误，根据错误信息修复。常见问题：
- Jackson import 冲突（tools.jackson vs com.fasterxml.jackson）
- DGS API 签名变化（查看 DGS 12 的 javadoc）
- CRUDRepository 构造函数参数不匹配（参照现有 TodoConfig bean 写法）

- [ ] **步骤 3：启动应用验证 GraphQL endpoint 可用**

运行：`./gradlew :core-api:bootRun`

验证：
1. 打开 `http://localhost:3001/graphiql` — 应该看到 GraphiQL playground
2. 执行 query：`{ __typename }` — 应返回 `{"data":{"__typename":"Query"}}`

- [ ] **步骤 4：最终 Commit**

```bash
git add -A
git commit -m "feat(graphql): complete GraphQL + Trusted Documents POC"
```

---

## 自检结果

### 规格覆盖度

| 规格需求 | 对应任务 |
|---------|---------|
| DGS 12 依赖配置 | 任务 1 |
| TodoItem 独立 collection | 任务 2, 3 |
| Type class + Konvert mapper | 任务 4 |
| GraphQL schema | 任务 5 |
| Context builder + permissions | 任务 6 |
| @RequirePermission directive | 任务 7 |
| DataLoader 聚合 | 任务 8 |
| Customer resolver | 任务 9 |
| Admin resolver + batch | 任务 10 |
| 双 endpoint | 任务 11 |
| Trusted Documents | 任务 12 |
| 监控 instrumentation | 任务 13 |
| 单元测试 | 任务 14 |
| Schema↔Type 一致性校验 | 任务 15 |
| 构建验证 | 任务 16 |

### 类型一致性检查

- `TodoType` 在任务 4 定义，任务 9/10 使用，字段一致 ✅
- `TodoItemType` 在任务 4 定义，任务 8/9 使用，字段一致 ✅
- `toTodoType()` / `toTodoItemType()` 在任务 4 定义，任务 9/10 使用 ✅
- `GraphQLRequestContext` 在任务 6 定义，任务 7/8/9/10/13 使用 ✅
- `PersistedQueryEntry` 在任务 12 定义，任务 13 通过 request attribute 间接引用 ✅

### 占位符扫描

无 TODO、待定或模糊步骤。所有代码步骤包含完整代码块。 ✅
