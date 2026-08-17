# 计划：GraphQL API 四项改进

## 概述

四个相关但可独立推进的改动：

1. **Operation 前缀**：GraphQL operation name 加 `query_` / `mutation_` 前缀
2. **Update Input 拆 set/unset**：防呆设计，防止 null 误清数据
3. **Mutation 返回对象**：create/update 返回完整对象，免二次查询
4. **使用 DGS 生成类型 + Konvert**：删掉手写的 `TodoType`/`TodoItemType`，直接用 codegen 生成的类

---

## 1. Operation Name 前缀：`query_` / `mutation_`

### 说明

客户端 operation document 的 operation name 加前缀，让静态分析和日志一眼区分读写：

```graphql
query query_todo_get($id: ID!) { todo_get(id: $id) { ... } }
mutation mutation_todo_create($input: CreateTodoInput!) { todo_create(input: $input) { ... } }
```

注意：**schema 里 Query/Mutation 的 field name 不变**（仍是 `todo_get`、`todo_create`），改的只是 persisted query JSON 里的 operation name 和客户端 document 里的名字。

### 影响文件

| 文件 | 改动 |
|------|------|
| `persisted-queries/customer.json` | 所有 `"name"` 和 `"query"` 文本加前缀 |
| `persisted-queries/admin.json` | 同上 |
| `TrustedDocumentFilter.kt` | 确认 operation name 校验逻辑兼容前缀（当前只校验 hash，不需改） |

### 示例

```json
{
  "a1b2c3d4e5f6": {
    "name": "query_todo_get",
    "query": "query query_todo_get($id: ID!) { todo_get(id: $id) { id title done items { id content done } createdAt updatedAt } }"
  },
  "c3r34t3t0d01": {
    "name": "mutation_todo_create",
    "query": "mutation mutation_todo_create($input: CreateTodoInput!) { todo_create(input: $input) { id title done createdAt } }"
  }
}
```

---

## 2. Update Input 拆 set / unset

### 设计

对所有 `UpdateXxxInput`，拆为包含 `set` 和 `unset` 的复合 input：

```graphql
input UpdateTodoInput {
    set: TodoUpdateSetInput
    unset: [String!]
}

input TodoUpdateSetInput {
    title: String
    done: Boolean
    meta: JSON
}
```

- `set`：要写入的字段（非 null 即写入）
- `unset`：要从 MongoDB 里 `$unset` 的字段名列表（等同于删除该字段值）

对于 `UpdateTodoItemInput` 同理：

```graphql
input UpdateTodoItemInput {
    set: TodoItemUpdateSetInput
    unset: [String!]
}

input TodoItemUpdateSetInput {
    content: String
    done: Boolean
}
```

### 为什么不用 `xxxUpdateUnsetInput` 对象

对于字段不多的模型，unset 只需要传字段名（`["meta", "description"]`），用 `[String!]` 更简洁。如果未来字段多了需要类型安全，可以改为 enum：

```graphql
enum TodoUnsetField { meta }
```

暂时先用 `[String!]`，代价低、足够防呆。

### 影响文件

| 文件 | 改动 |
|------|------|
| `schema/common.graphqls` | 改 `UpdateTodoInput`、`UpdateTodoItemInput`，新增 `TodoUpdateSetInput`、`TodoItemUpdateSetInput` |
| `CustomerTodoFetcher.kt` | `updateTodo` / `updateTodoItem` 解析新结构 |
| `TodoService.kt` | `update()` 增加 `unsetFields: List<String>?` 参数 |
| `TodoItemService.kt` | `update()` 增加 `unsetFields: List<String>?` 参数 |
| `CRUDRepository.kt` 或 `CRUDService.kt` | 增加 `$unset` 支持的通用方法 |
| persisted-queries JSON | 更新 query 文本 |

### Fetcher 侧代码变化示意

```kotlin
@DgsMutation(field = "todo_update")
fun updateTodo(
    @InputArgument id: String,
    @InputArgument input: UpdateTodoInput,  // DGS 生成的类型
    dfe: DgsDataFetchingEnvironment,
): Todo {
    val ctx = getContext(dfe)
    val doc = todoService.getById(ctx.requestContext, id)
    // 权限检查 ...
    todoService.update(ctx.requestContext, id, set = input.set, unset = input.unset)
    return todoService.getById(ctx.requestContext, id).toTodo()
}
```

### Service / Repo 层支持

`CRUDRepository.updateById` 增加 `unsetFields` 参数：

```kotlin
fun updateById(ctx: RequestContext, id: String, patch: Any, unsetFields: List<String>? = null): Boolean {
    // ... 现有 $set 逻辑 ...
    unsetFields?.forEach { field -> update.unset(field) }
    update.set(BaseDocument::updatedAt, Instant.now())
    return mongo.updateFirst(query, update, type).modifiedCount > 0
}
```

### unset 字段白名单验证

为防止客户端 unset 关键系统字段（`_id`, `appId`, `createdAt`），在 service 层做白名单校验：

```kotlin
private val UNSETABLE_FIELDS = setOf("meta") // TodoService
private val UNSETABLE_FIELDS = setOf<String>() // TodoItemService — 目前无可 unset 字段
```

不在白名单内的字段名忽略或抛错。

---

## 3. Mutation 返回完整对象

### 设计

现有 schema 中 `todo_create` / `todo_update` 已经返回 `Todo!`，但需要确认 **所有 mutation** 都遵循：

| mutation | 现有返回 | 改为 |
|----------|---------|------|
| `todo_create` | `Todo!` ✅ | 不变 |
| `todo_update` | `Todo!` ✅ | 不变 |
| `todo_delete` | `Boolean!` | 保持（删除不需要返回对象） |
| `todoItem_create` | `TodoItem!` ✅ | 不变 |
| `todoItem_update` | `TodoItem!` ✅ | 不变 |
| `todoItem_delete` | `Boolean!` | 保持 |
| `scan_create` | `ScanRecord!` ✅ | 不变 |
| `scan_update` | `Boolean!` | **改为 `ScanRecord!`** |
| `scan_delete` | `Boolean!` | 保持 |

需要改的只有 **`scan_update`**：改为返回 `ScanRecord!`，在 fetcher 里 update 后再查一次返回。

### Fetcher 改动

```kotlin
// CustomerScanFetcher.kt — scan_update
@DgsMutation(field = "scan_update")
fun updateScan(
    @InputArgument id: String,
    @InputArgument collected: Boolean?,
    dfe: DgsDataFetchingEnvironment,
): ScanRecord {
    val ctx = getContext(dfe)
    // update ...
    return scanService.getById(ctx, id).toScanRecord()
}
```

### Schema 改动

```graphql
# customer.graphqls
scan_update(id: ID!, collected: Boolean): ScanRecord! @requirePermission(permission: "scan:write")
```

---

## 4. 使用 DGS 生成类型 + Konvert 替代手写 Type

### 现状

- `ScanRecord`、`Collection`、`CollectionItemType` 等已经使用 DGS codegen 生成的类（from `com.ifmix.api.core.graphql.generated.types`）
- `TodoType`、`TodoItemType`、`TodoConnection`、`OperationResult` 还是手写的

### 目标

删除手写类型，全部用 DGS codegen 生成的 `com.ifmix.api.core.graphql.generated.types.Todo`、`TodoItem`、`TodoConnection` 等。

### 步骤

1. **确认 codegen 生成了对应的类**：`generateDataTypes = true` 已配置，schema 中 `type Todo { ... }` 会生成 `Todo` data class
2. **删除手写类型**：
   - `graphql/common/type/TodoType.kt` → 删
   - `graphql/common/type/TodoItemType.kt` → 删
   - `graphql/common/type/TodoConnection.kt` → 删
   - `graphql/common/type/OperationResult.kt` → 删
3. **更新 mapper**：`TodoMapper.kt` 改为映射到生成的 `Todo` / `TodoItem`
4. **尽量用 Konvert**：如果字段名对得上（除了 `id` 需要 `ObjectId.toHexString()`），用 `@Konvert` 注解生成。不过 `id` 转换已有自定义 TypeConverter（注册在 `META-INF/services`），可以直接用。

### Mapper 改动

```kotlin
// TodoMapper.kt — 改为映射到 DGS 生成的类型
package com.ifmix.api.core.modules.todo.mapper

import com.ifmix.api.core.graphql.generated.types.Todo
import com.ifmix.api.core.graphql.generated.types.TodoItem
import com.ifmix.api.core.modules.todo.TodoDocument
import com.ifmix.api.core.modules.todo.document.TodoItemDocument

fun TodoDocument.toTodo(): Todo = Todo(
    id = this.id.toHexString(),
    title = this.title,
    done = this.done,
    meta = this.meta,
    items = emptyList(), // DataLoader 填充
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)

fun TodoItemDocument.toTodoItem(): TodoItem = TodoItem(
    id = this.id.toHexString(),
    todoId = this.todoId,
    content = this.content,
    done = this.done,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
)
```

如果 Konvert 的自定义 ObjectId→String TypeConverter 能正常工作，可以进一步简化为注解驱动：

```kotlin
@Konvert(to = [Todo::class])
@KonvertFrom(TodoDocument::class)
interface TodoKonverter
```

但鉴于 `items` 字段需要特殊处理（空列表 placeholder），手写 mapper 更清晰，不强求 Konvert。**优先级是消灭手写 Type class，mapper 保持手写但映射到生成类型**。

### DataLoader 改动

`TodoItemDataLoader.kt` 中 `DataLoader<String, List<TodoItemType>>` → `DataLoader<String, List<TodoItem>>`

### Fetcher 中引用改动

全局替换：
- `TodoType` → `Todo`（from generated）
- `TodoItemType` → `TodoItem`（from generated）
- `toTodoType()` → `toTodo()`
- `toTodoItemType()` → `toTodoItem()`
- `import ...graphql.common.type.TodoType` → `import ...graphql.generated.types.Todo`

---

## 执行顺序

建议按以下顺序逐步推进（每步完成后 `./gradlew build` 验证）：

1. **第 4 步先做**（类型迁移）— 纯重构不改 API 行为，最安全
2. **第 3 步**（scan_update 返回对象）— 小改动
3. **第 2 步**（set/unset 拆分）— schema breaking change，需要最多代码
4. **第 1 步最后**（operation name 前缀）— 只改 persisted query，不影响 schema

---

## Breaking Changes 总结

| 改动 | 是否 breaking | 客户端影响 |
|------|-------------|-----------|
| operation name 前缀 | ⚠️ 是 | 客户端 operation name 要对齐 |
| set/unset Input | ⚠️ 是 | 客户端 mutation 变量结构变化 |
| scan_update 返回 ScanRecord | ⚠️ 是 | 返回类型从 Boolean 改为 object |
| 类型迁移到 codegen | ❌ 否 | GraphQL schema 不变，纯服务端重构 |

**建议**：因为前三个都是 breaking change，如果一次性做，只需要客户端升级一次。配合 app 强更或 persisted query 版本切换来处理。

---

## 验证

- `./gradlew generateJava` — 确认生成类型正确
- `./gradlew build` — 编译通过
- `./gradlew test` — 测试通过
- 手动验证 mutation 返回完整对象
- 手动验证 unset 生效（字段从 MongoDB 文档中删除）
