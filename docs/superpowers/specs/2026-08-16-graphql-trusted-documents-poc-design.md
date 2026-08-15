# GraphQL + Trusted Documents POC 设计规格

## 目标

用 Todo + TodoItem 演示：
1. GraphQL（Netflix DGS 12）替代 Swagger REST
2. 数据聚合：独立 service 各管单表，GraphQL 层通过 DataLoader 做关联
3. Trusted Documents（Allowlist）：生产环境只允许预注册的 query 执行
4. 多 BFF 隔离：admin 和 customer 独立 endpoint、独立 schema
5. 声明式权限：`@RequirePermission` directive
6. 监控：per-operation metrics（调用次数、成功/失败、latency）

## 技术选型

| 项 | 选择 | 理由 |
|----|------|------|
| GraphQL 框架 | Netflix DGS 12 | Spring Boot 4 MVC ✅，DataLoader ✅，APQ ✅，Micrometer ✅，生产级成熟 |
| Schema 方式 | Schema-first（`.graphqls`）+ 手写 Kotlin type class | 手写 type class 对 Konvert 友好 |
| 类型转换 | Konvert（Document → Type） | 编译期生成，避免手写 mapper |
| 一致性校验 | Gradle task 编译时对比 schema ↔ type class | 不一致则编译失败 |
| 权限 | 自定义 `@RequirePermission` DGS directive | 声明式 |
| 监控 | DGS Micrometer 插件 + 自定义 Instrumentation | per-operation 粒度 |
| Trusted Documents | 自定义实现，接口抽象预留 Redis | POC 用静态 JSON |
| 现有数据层 | 不动 | MongoTemplate + @Transactional + virtual threads 保持不变 |

### 为什么不选 Expedia graphql-kotlin-spring-server

- 只有 WebFlux 版本
- 切 WebFlux 需要把整个数据层从 `MongoTemplate` 换成 `ReactiveMongoTemplate`
- `@Transactional`（ThreadLocal 模式）在 WebFlux 下不可用
- 改动量远超 POC 范围

### 为什么不选 Apollo Kotlin Execution

- 0.1.2 experimental，9 stars
- 没有 DataLoader
- 没有 Instrumentation
- Spring 集成也是 WebFlux

## 架构总览

```
客户端
  │  POST /customer/graphql 或 /admin/graphql
  │  body: { "extensions": { "persistedQuery": { "sha256Hash": "..." } }, "variables": {...} }
  ↓
┌─────────────────────────────────────────────────┐
│  TrustedDocumentFilter (Spring HandlerInterceptor)│
│  按路径判定 bff → 查对应 allowlist               │
│  命中 → 注入 query body → 放行                   │
│  未命中 + enabled=true → 403                     │
│  未命中 + enabled=false → 放行（开发模式）        │
└─────────────────────────────────────────────────┘
  ↓
┌─────────────────────────────────────────────────┐
│  DgsContextBuilder                               │
│  header → RequestContext(appId, installId, userId)│
│  X-Role header → mock 角色 → permissions 集合    │
│  permissions 存入 ctx，请求内不重复查询            │
└─────────────────────────────────────────────────┘
  ↓
┌─────────────────────────────────────────────────┐
│  @RequirePermission directive                    │
│  SchemaDirectiveWiring → ctx.permissions.contains │
│  不满足 → GraphQLException(403)                   │
└─────────────────────────────────────────────────┘
  ↓
┌──────────────────┐     ┌──────────────────────┐
│  Query resolvers │     │  Mutation resolvers  │
│  (customer/admin)│     │  (customer/admin)    │
└────────┬─────────┘     └──────────────────────┘
         │
         ↓ Todo.items field
┌──────────────────────────────────────┐
│  TodoItemDataLoader                  │
│  批量 findByTodoIds → 按 todoId 分组  │
└──────────────────────────────────────┘
  ↓
┌──────────────┐     ┌──────────────────┐
│ TodoService  │     │ TodoItemService  │
│ (todos 集合)  │     │ (todo_items 集合) │
└──────────────┘     └──────────────────┘
```

## 包结构

```
core-api/src/main/kotlin/com/ifmix/api/core/
├── graphql/
│   ├── common/
│   │   ├── type/           # TodoType, TodoItemType（手写 Kotlin class）
│   │   ├── dataloader/     # TodoItemDataLoader
│   │   ├── directive/      # @RequirePermission annotation + SchemaDirectiveWiring
│   │   ├── context/        # DgsContextBuilder（构造 RequestContext + permissions）
│   │   ├── monitor/        # OperationMetricsInstrumentation
│   │   └── trusted/        # TrustedDocumentFilter + PersistedQueryStore 接口
│   │
│   ├── customer/
│   │   ├── query/          # CustomerTodoQuery (@DgsQuery)
│   │   ├── mutation/       # CustomerTodoMutation (@DgsMutation)
│   │   └── config/         # CustomerGraphQLConfig（/customer/graphql）
│   │
│   └── admin/
│       ├── query/          # AdminTodoQuery（含 batch）
│       ├── mutation/       # AdminTodoMutation（含 batch）
│       └── config/         # AdminGraphQLConfig（/admin/graphql）
│
├── modules/
│   └── todo/
│       ├── document/       # TodoDocument, TodoItemDocument
│       ├── service/        # TodoService, TodoItemService
│       ├── repo/           # TodoRepository, TodoItemRepository
│       └── mapper/         # Konvert 映射（Document → Type）

src/main/resources/
├── schema/
│   ├── customer/           # customer.graphqls
│   └── admin/              # admin.graphqls
├── graphql/
│   └── persisted-queries/
│       ├── customer.json   # customer allowlist
│       └── admin.json      # admin allowlist
```

## Schema 文件（`.graphqls`）

### customer.graphqls

```graphql
type Query {
    todo(id: ID!): Todo
    todos(cursor: String, limit: Int): TodoConnection
}

type Mutation {
    createTodo(input: CreateTodoInput!): Todo!
    updateTodo(id: ID!, input: UpdateTodoInput!): Todo!
    deleteTodo(id: ID!): Boolean!
    createTodoItem(todoId: ID!, input: CreateTodoItemInput!): TodoItem!
    updateTodoItem(id: ID!, input: UpdateTodoItemInput!): TodoItem!
    deleteTodoItem(id: ID!): Boolean!
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

scalar DateTime
```

### admin.graphqls

```graphql
type Query {
    todo(id: ID!): Todo
    todos(cursor: String, limit: Int, userId: String): TodoConnection
}

type Mutation {
    updateTodo(id: ID!, input: UpdateTodoInput!): Todo!
    batchDeleteTodos(ids: [ID!]!): OperationResult!
    batchUpdateTodos(patches: [BatchUpdateTodoInput!]!): OperationResult!
    updateTodoItem(id: ID!, input: UpdateTodoItemInput!): TodoItem!
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

input UpdateTodoInput {
    title: String
    done: Boolean
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

scalar DateTime
```

## 数据模型

### TodoDocument（todos 集合）

| 字段 | 类型 | 说明 |
|------|------|------|
| _id | ObjectId | 主键 |
| appId | String | 租户隔离 |
| title | String | 标题 |
| done | Boolean | 是否完成 |
| userId | String? | 拥有者 |
| installId | String? | 设备标识 |
| createdAt | Instant | 创建时间 |
| updatedAt | Instant | 更新时间 |

### TodoItemDocument（todo_items 集合）

| 字段 | 类型 | 说明 |
|------|------|------|
| _id | ObjectId | 主键 |
| appId | String | 租户隔离 |
| todoId | String | 外键，关联 Todo |
| content | String | 内容 |
| done | Boolean | 是否完成 |
| createdAt | Instant | 创建时间 |
| updatedAt | Instant | 更新时间 |

索引：`todo_items` 集合建 `{ appId: 1, todoId: 1 }` 复合索引。

## Schema ↔ Type Class 一致性校验

Gradle task 在编译时执行：

1. 解析 `.graphqls` 文件，提取所有 type 的字段名和类型
2. 通过反射/KSP 扫描对应的 Kotlin type class
3. 对比字段是否一一对应（名称 + nullable + 列表）
4. 不一致 → 编译失败，报错指明哪个 type 的哪个字段不匹配

这确保 schema 和 type class 始终同步，改了一处忘改另一处会立即被拦住。

## Trusted Documents 机制

### 配置

```yaml
graphql:
  trusted-documents:
    enabled: true              # false = 开发模式，允许任意 query
    allowlist-path: classpath:graphql/persisted-queries/
```

### Allowlist 文件

```
src/main/resources/graphql/persisted-queries/
├── customer.json
└── admin.json
```

格式：

```json
{
  "sha256hashAAA": {
    "name": "GetTodo",
    "query": "query GetTodo($id: ID!) { todo(id: $id) { id title done items { id content done } } }"
  }
}
```

### 接口抽象

```kotlin
interface PersistedQueryStore {
    fun get(hash: String, bff: String): PersistedQueryEntry?
}

data class PersistedQueryEntry(val name: String, val query: String)

// POC 实现：从 classpath JSON 加载
class ClasspathPersistedQueryStore : PersistedQueryStore

// 将来扩展：Redis 实现，无需改调用方
```

### 安全要点

- 校验以 hash 为准，客户端传的 `operationName` 不可信，忽略
- 监控/日志用 allowlist 里的 `name`（服务端定义，不可篡改）
- 生产环境 `enabled=true` 时关闭 introspection 和 playground

## DataLoader 聚合

### 场景

客户端查 N 个 Todo，每个 Todo 下挂 items：

```graphql
query GetTodos {
  todos { items { id title done items { id content done } } }
}
```

### 执行流程

1. `CustomerTodoQuery.todos()` → `TodoService.findByCursor()` → 返回 N 个 Todo
2. DGS 遍历每个 Todo 的 `items` field resolver
3. DataLoader 收集所有 todoIds，一次批量调用
4. `TodoItemService.findByTodoIds(ids)` → 一次查询
5. 按 todoId 分组回填

### DGS DataLoader 实现方式

```kotlin
@DgsDataLoader(name = "todoItems")
class TodoItemDataLoader(private val todoItemService: TodoItemService) :
    MappedBatchLoader<String, List<TodoItemType>> {

    override fun load(keys: Set<String>): CompletionStage<Map<String, List<TodoItemType>>> {
        return CompletableFuture.supplyAsync {
            todoItemService.findByTodoIds(keys.toList())
                .map { it.toTodoItemType() }
                .groupBy { it.todoId }
        }
    }
}
```

DataLoader 作用域：per-request（DGS 默认行为，每个请求独立实例）。

## 权限模型

### Directive

```kotlin
@RequirePermission("todo:read")
@DgsQuery
fun todos(): TodoConnection { ... }
```

### 权限集合（POC mock）

```kotlin
val ROLE_PERMISSIONS = mapOf(
    "customer" to setOf("todo:read", "todo:write"),
    "admin" to setOf("todo:read", "todo:write", "todo:batch", "todo:admin")
)
```

POC 阶段通过 `X-Role: customer|admin` header 判定角色，映射到 permissions。
将来替换为 Redis 查询真实权限，directive 和 resolver 代码不动。

### 请求级缓存

permissions 在 DgsContextBuilder 中查一次，存入 RequestContext。同一请求内多个 resolver 不重复查询。

## Admin vs Customer API 差异

| 操作 | Customer | Admin | Permission |
|------|----------|-------|-----------|
| todo(id) | ✅ 归属校验 | ✅ 无归属限制 | todo:read |
| todos(cursor) | ✅ 只看自己的 | ✅ 可看所有人，支持 userId 过滤 | todo:read |
| createTodo(input) | ✅ | ❌ | todo:write |
| updateTodo(id, input) | ✅ 归属校验 | ✅ 无归属限制 | todo:write |
| deleteTodo(id) | ✅ 归属校验 | ❌ | todo:write |
| batchDeleteTodos(ids) | ❌ | ✅ | todo:batch |
| batchUpdateTodos(patches) | ❌ | ✅ | todo:batch |
| createTodoItem(todoId, input) | ✅ | ❌ | todo:write |
| updateTodoItem(id, input) | ✅ | ✅ | todo:write |
| deleteTodoItem(id) | ✅ | ❌ | todo:write |
| batchDeleteTodoItems(ids) | ❌ | ✅ | todo:batch |

## 监控

### Metrics

| Metric | Tags | 说明 |
|--------|------|------|
| `graphql.operation.count` | `bff`, `operation`, `result` | 调用次数 |
| `graphql.operation.duration` | `bff`, `operation` | 耗时（Timer: p50/p95/p99） |
| `graphql.dataloader.batch` | `loader` | DataLoader 批次大小和耗时 |

### 实现

- DGS 自带 `graphql-dgs-spring-boot-micrometer` 模块，自动暴露基础 metrics
- 加一个自定义 `Instrumentation` bean 记录 per-operation 粒度
- operation name 从 allowlist entry 的 `name` 字段取（可信）
- 注入 Micrometer `MeterRegistry`，自动暴露到 `/actuator/metrics`

### 查看方式

```
GET /actuator/metrics/graphql.operation.count?tag=operation:GetTodo&tag=bff:customer
GET /actuator/metrics/graphql.operation.duration?tag=operation:CreateTodo&tag=result:success
```

## 多 endpoint 实现方式

DGS 默认只暴露一个 `/graphql` endpoint。多 endpoint 方案：

- 注册两个 `DgsGraphQLRouter`（DGS 的内部类），分别绑定 `/customer/graphql` 和 `/admin/graphql`
- 每个 endpoint 使用不同的 schema 文件目录（`schema/customer/` vs `schema/admin/`）
- 通过 Spring Configuration 分别构建两套 `GraphQLSchema` + `DgsQueryExecutor`

如果 DGS 不原生支持多 schema 实例，备选方案：手动创建两个 `@RestController`，各自持有独立的 `GraphQL` 实例（底层 graphql-java），DGS 的 `@DgsQuery` 注解改为手动注册 data fetcher。

## 不做的事情（YAGNI）

- 不做 Subscription
- 不做 Federation
- 不做 Relay cursor 分页（复用现有 CursorQueryInput）
- 不做真实 auth（mock X-Role header）
- 不做 Redis 版 PersistedQueryStore（只预留接口）
- 不删现有 REST controller（共存，POC 验证后再决定）
- 不切 WebFlux（保持 MVC + virtual threads + MongoTemplate）
