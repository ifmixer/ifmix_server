# 计划：嵌套 Item Mutation + Todo 缓存 + RequestContext 合并

## 概述

两个改动：
1. `todo_update` 支持嵌套操作子 item（create/set/unset/delete），一次请求搞定父子联动
2. Todo 增加 Redis 缓存演示；合并 `GraphQLRequestContext` 和 `RequestContext`

---

## 1. 嵌套 Item Mutation

### 设计理念

客户端一次 `todo_update` 可以同时修改 todo 本身 + 对 items 做批量操作。items 操作是一个 `[TodoItemMutationInput!]` 数组，每个元素包含一个 `action` discriminator：

```graphql
input UpdateTodoInput {
    set: TodoUpdateSetInput
    unset: [String!]
    items: [TodoItemMutationInput!]
}

input TodoItemMutationInput {
    """操作类型"""
    action: TodoItemAction!
    """目标 item id（create 时不传）"""
    id: ID
    """create / set 时使用"""
    set: TodoItemUpdateSetInput
    """unset 时使用"""
    unset: [String!]
}

enum TodoItemAction {
    CREATE
    SET
    UNSET
    DELETE
}

input TodoItemUpdateSetInput {
    content: String
    done: Boolean
}
```

### 语义说明

| action | 含义 | 必填字段 |
|--------|------|---------|
| `CREATE` | 新建 item | `set`（content 必填） |
| `SET` | 部分更新 | `id` + `set` |
| `UNSET` | 清除字段 | `id` + `unset` |
| `DELETE` | 软删 item | `id` |

### 示例请求

```graphql
mutation mutation_todo_update($id: ID!, $input: UpdateTodoInput!) {
  todo_update(id: $id, input: $input) {
    id title done
    items { id content done }
  }
}
```

```json
{
  "id": "abc123",
  "input": {
    "set": { "title": "买菜清单" },
    "items": [
      { "action": "CREATE", "set": { "content": "西红柿", "done": false } },
      { "action": "SET", "id": "item1", "set": { "done": true } },
      { "action": "DELETE", "id": "item2" }
    ]
  }
}
```

### 扩展性

`TodoItemAction` enum 将来可以加：
- `REORDER` — 带 `position` 字段排序
- `MOVE` — 移动到另一个 todo

schema 加字段 + enum 值就行，fetcher 里 `when(action)` 加分支。

### 影响文件

| 文件 | 改动 |
|------|------|
| `schema/common.graphqls` | 新增 `TodoItemMutationInput`、`TodoItemAction` enum；`UpdateTodoInput` 加 `items` 字段 |
| `CustomerTodoFetcher.kt` | `updateTodo` 解析 `input.items` 并按 action 分发 |
| `TodoItemService.kt` | 无需改（已有 create/update/deleteById） |
| persisted-queries JSON | 更新 query 文本 |

### Fetcher 实现示意

```kotlin
@DgsMutation(field = "todo_update")
fun updateTodo(
    @InputArgument id: String,
    @InputArgument input: UpdateTodoInput, // DGS 生成
    dfe: DgsDataFetchingEnvironment,
): Todo {
    val ctx = getContext(dfe)
    val doc = todoService.getById(ctx, id)
    ownershipCheck(ctx, doc)

    // 1. 更新 todo 本身
    if (input.set != null || !input.unset.isNullOrEmpty()) {
        todoService.update(ctx, id, set = input.set, unset = input.unset)
    }

    // 2. 处理嵌套 items
    input.items?.forEach { mutation ->
        when (mutation.action) {
            TodoItemAction.CREATE -> {
                val set = mutation.set ?: throw ApiError(ErrorCode.BAD_REQUEST, "CREATE requires set")
                todoItemService.create(ctx, todoId = id, content = set.content!!, done = set.done ?: false)
            }
            TodoItemAction.SET -> {
                val itemId = mutation.id ?: throw ApiError(ErrorCode.BAD_REQUEST, "SET requires id")
                todoItemService.update(ctx, itemId, content = mutation.set?.content, done = mutation.set?.done)
            }
            TodoItemAction.UNSET -> {
                val itemId = mutation.id ?: throw ApiError(ErrorCode.BAD_REQUEST, "UNSET requires id")
                todoItemService.unsetFields(ctx, itemId, mutation.unset ?: emptyList())
            }
            TodoItemAction.DELETE -> {
                val itemId = mutation.id ?: throw ApiError(ErrorCode.BAD_REQUEST, "DELETE requires id")
                todoItemService.deleteById(ctx, itemId)
            }
        }
    }

    // 3. 返回完整对象（items 由 DataLoader 自动填充）
    return todoService.getById(ctx, id).toTodo()
}
```

### TodoItemService 新增方法

```kotlin
fun unsetFields(ctx: RequestContext, id: String, fields: List<String>): Boolean {
    val allowed = setOf<String>() // TodoItem 目前无可 unset 字段，预留
    val valid = fields.filter { it in allowed }
    if (valid.isEmpty()) return true
    val update = Update()
    valid.forEach { update.unset(it) }
    update.set(BaseDocument::updatedAt, Instant.now())
    return repo.updateById(ctx, id, update)
}
```

---

## 2. 合并 GraphQLRequestContext → RequestContext

### 现状

- `RequestContext`：通用上下文，在 controller → service → repo 间传递
- `GraphQLRequestContext`：GraphQL 专用包装，额外包含 `permissions: Set<String>` 和 `bff: String`

两层嵌套带来不必要的 `.requestContext` 解包。合并为一个 `RequestContext`。

### 新 RequestContext 设计

```kotlin
data class RequestContext(
    val appId: String,
    val operationId: String? = null,
    val installId: String? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: String? = null,
    val readPreference: ReadPreference = ReadPreference.primaryPreferred(),

    // --- 从 GraphQLRequestContext 合并过来 ---
    val bff: Bff = Bff.CUSTOMER,
    val permissions: Set<String> = emptySet(),
    val actorType: ActorType = ActorType.CUSTOMER_INSTALL,

    // --- 新增 ---
    /** 请求级缓存，避免同一请求内重复查库。key 自定义。 */
    val requestCache: MutableMap<String, Any?> = mutableMapOf(),
)

enum class Bff { CUSTOMER, ADMIN }

enum class ActorType {
    ADMIN,
    CUSTOMER_USER,
    CUSTOMER_INSTALL,
    API_KEY,
}
```

### `getActorId()` 函数

```kotlin
fun RequestContext.getActorId(): String = when (actorType) {
    ActorType.ADMIN -> userId ?: error("admin must have userId")
    ActorType.CUSTOMER_USER -> userId ?: error("customer user must have userId")
    ActorType.CUSTOMER_INSTALL -> installId ?: error("customer install must have installId")
    ActorType.API_KEY -> appId // API key 场景下 actor 就是 app 本身
}
```

### actorType 推导逻辑

在 `DgsCustomContextBuilderImpl.build()` 中：

```kotlin
val actorType = when {
    bff == Bff.ADMIN -> ActorType.ADMIN
    userId != null -> ActorType.CUSTOMER_USER
    installId != null -> ActorType.CUSTOMER_INSTALL
    else -> ActorType.CUSTOMER_INSTALL // fallback
}
```

### 影响文件

| 文件 | 改动 |
|------|------|
| `RequestContext.kt` | 合并字段，新增 `Bff`/`ActorType` enum，新增 `requestCache`，新增 `getActorId()` |
| `GraphQLContextBuilder.kt` | 删除 `GraphQLRequestContext` class，`build()` 直接返回 `RequestContext` |
| 所有 Fetcher | `DgsContext.getCustomContext<RequestContext>(dfe)` 替代 `<GraphQLRequestContext>`；删除 `.requestContext` 解包 |
| `RequirePermission.kt` | 从 `RequestContext.permissions` 取权限 |
| `TodoItemDataLoader.kt` | `DgsContext.getCustomContext<RequestContext>(environment)` |
| `CustomerAuthController.kt` / BFF controllers | 如果直接构造 RequestContext，加上新字段 |

### 删除的类

- `GraphQLRequestContext`（整个 class 删除，构建器直接产出 `RequestContext`）

---

## 3. Todo Redis 缓存演示

### 设计

用 Spring Data Redis（`RedisTemplate` / `ValueOperations`）做简单的 by-id 缓存：

- **缓存策略**：Cache-Aside（旁路缓存）
- **读**：先查 Redis，miss 则查 MongoDB，写入 Redis
- **写/删**：先写 MongoDB，再删 Redis（简单失效）
- **TTL**：5 分钟

### Cache Key 格式

```
todo:{appId}:{todoId}
```

### 实现层：`TodoCacheService`

独立 service，组合 `TodoService` + `RedisTemplate`：

```kotlin
@Service
class TodoCacheService(
    private val todoService: TodoService,
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val TTL = Duration.ofMinutes(5)

    fun getById(ctx: RequestContext, id: String): TodoDocument {
        val key = cacheKey(ctx, id)
        val cached = redisTemplate.opsForValue().get(key)
        if (cached != null) {
            return objectMapper.readValue(cached, TodoDocument::class.java)
        }
        val doc = todoService.getById(ctx, id)
        redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(doc), TTL)
        return doc
    }

    fun invalidate(ctx: RequestContext, id: String) {
        redisTemplate.delete(cacheKey(ctx, id))
    }

    private fun cacheKey(ctx: RequestContext, id: String) = "todo:${ctx.appId}:$id"
}
```

### requestCache（请求级内存缓存）

`RequestContext.requestCache` 用于同一 HTTP 请求内去重：

```kotlin
// 在 fetcher 中使用
fun getOrLoadTodo(ctx: RequestContext, id: String): TodoDocument {
    val cacheKey = "todo:$id"
    @Suppress("UNCHECKED_CAST")
    return ctx.requestCache.getOrPut(cacheKey) {
        todoCacheService.getById(ctx, id)
    } as TodoDocument
}
```

这样同一请求里多次需要同一个 todo（权限检查 + 返回），不会重复查 Redis/DB。

### RedisConfig 补充

```kotlin
@Configuration
class RedisConfig {

    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, String> {
        val template = RedisTemplate<String, String>()
        template.connectionFactory = connectionFactory
        template.keySerializer = StringRedisSerializer()
        template.valueSerializer = StringRedisSerializer()
        return template
    }
}
```

### 影响文件

| 文件 | 改动 |
|------|------|
| `RedisConfig.kt` | 配置 `RedisTemplate<String, String>` bean |
| 新文件 `modules/todo/service/TodoCacheService.kt` | Redis 缓存逻辑 |
| `TodoConfig.kt` | 注册 `TodoCacheService` bean |
| `CustomerTodoFetcher.kt` | `todo_get` 走 `TodoCacheService`；`todo_update`/`todo_delete` 后 `invalidate` |
| `RequestContext.kt` | 新增 `requestCache: MutableMap<String, Any?>` |

### 缓存失效时机

| 操作 | 失效 |
|------|------|
| `todo_update` | `invalidate(ctx, id)` |
| `todo_delete` | `invalidate(ctx, id)` |
| `todo_batchUpdate` | 逐个 `invalidate` |
| `todo_batchDelete` | 逐个 `invalidate` |

---

## 执行顺序

1. **合并 RequestContext**（纯重构，不改 API 行为）
2. **嵌套 Item Mutation**（schema + fetcher + service）
3. **Redis 缓存**（新增文件，低耦合）

---

## Schema 最终形态（Todo 部分）

```graphql
type Mutation {
    todo_create(input: CreateTodoInput!): Todo!
    todo_update(id: ID!, input: UpdateTodoInput!): Todo!
    todo_delete(id: ID!): Boolean!
}

input CreateTodoInput {
    title: String!
    meta: JSON
    items: [CreateTodoItemInput!]
}

input UpdateTodoInput {
    set: TodoUpdateSetInput
    unset: [String!]
    items: [TodoItemMutationInput!]
}

input TodoUpdateSetInput {
    title: String
    done: Boolean
    meta: JSON
}

input TodoItemMutationInput {
    action: TodoItemAction!
    id: ID
    set: TodoItemUpdateSetInput
    unset: [String!]
}

enum TodoItemAction {
    CREATE
    SET
    UNSET
    DELETE
}

input TodoItemUpdateSetInput {
    content: String
    done: Boolean
}
```

---

## 验证

- `./gradlew build` — 编译通过
- `./gradlew test` — 测试通过
- 手动验证嵌套 mutation：一次请求同时创建 + 更新 + 删除 items
- 手动验证缓存：第二次 `todo_get` 走 Redis（观察 MongoDB 日志无二次查询）
- 手动验证缓存失效：`todo_update` 后再 `todo_get` 拿到最新值
- 确认 `getActorId()` 在各 actorType 场景下返回正确 id
