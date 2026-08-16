# 计划：GraphQL Operation 命名改为 `${module}_${action}` 格式

## 目标

所有 GraphQL field name（schema 里 Query/Mutation 的字段名）统一为 `module_action` 格式，便于：
- 按模块快速定位
- 与 permission（`"todo:write"`）、x-op-id、metrics 标签对齐
- 客户端 codegen 生成可预测的常量

---

## 命名规则

格式：`{module}_{action}`，全部 camelCase 内部但模块与动作之间用下划线分隔。

- module：业务模块名（todo, todoItem, scan, storage, collection, feedback, iap, appConfig）
- action：动词或动词+修饰（get, list, create, update, delete, batchDelete, batchUpdate...）

---

## 改名映射

### Customer Query

| 现有名 | 新名 | 说明 |
|--------|------|------|
| `todo(id)` | `todo_get` | 单个 |
| `todos(cursor, limit)` | `todo_list` | 列表 |
| `scanRecord(id)` | `scan_get` | 单个 |
| `scanRecords(cursor, limit)` | `scan_list` | 列表 |
| `defaultCollection` | `collection_getDefault` | |
| `collectionItems(...)` | `collectionItem_list` | |
| `currentAppConfig` | `appConfig_getCurrent` | |

### Customer Mutation

| 现有名 | 新名 |
|--------|------|
| `createTodo` | `todo_create` |
| `updateTodo` | `todo_update` |
| `deleteTodo` | `todo_delete` |
| `createTodoItem` | `todoItem_create` |
| `updateTodoItem` | `todoItem_update` |
| `deleteTodoItem` | `todoItem_delete` |
| `newScan` | `scan_create` |
| `updateScan` | `scan_update` |
| `deleteScan` | `scan_delete` |
| `presignUpload` | `storage_presignUpload` |
| `presignDownload` | `storage_presignDownload` |
| `addCollectionItem` | `collectionItem_add` |
| `removeCollectionItems` | `collectionItem_remove` |
| `submitFeedback` | `feedback_submit` |
| `verifyPurchase` | `iap_verifyPurchase` |

### Admin Mutation

| 现有名 | 新名 |
|--------|------|
| `batchDeleteTodos` | `todo_batchDelete` |
| `batchUpdateTodos` | `todo_batchUpdate` |
| `batchDeleteTodoItems` | `todoItem_batchDelete` |
| `createAppConfigRevision` | `appConfig_createRevision` |
| `toggleAppConfigRevision` | `appConfig_toggleRevision` |

---

## 影响范围

### 1. Schema 文件（3 个）

- `schema/customer.graphqls` — Query + Mutation 字段名改
- `schema/admin.graphqls` — extend Mutation 字段名改
- `schema/common.graphqls` — type/input 不变

### 2. DGS Fetcher（6 个）

DGS 默认按方法名匹配 schema 字段。方法名改了或加 `@DgsQuery(field = "todo_get")` / `@DgsMutation(field = "todo_create")`：

| 文件 | 涉及 |
|------|------|
| `CustomerTodoFetcher.kt` | 5 个 query/mutation |
| `CustomerScanFetcher.kt` | 5 个 |
| `CustomerCollectionFetcher.kt` | 4 个 |
| `CustomerIapFetcher.kt` | 1 个 |
| `CustomerFeedbackFetcher.kt` | 1 个 |
| `AdminTodoFetcher.kt` | 3 个 |
| `AdminAppConfigFetcher.kt` | 3 个 |

**建议**：Kotlin 方法名保持可读（如 `fun getTodo()`），用注解指定 schema field：
```kotlin
@DgsQuery(field = "todo_get")
fun getTodo(@InputArgument id: String): Todo { ... }

@DgsMutation(field = "todo_create")
fun createTodo(@InputArgument input: CreateTodoInput): Todo { ... }
```

### 3. Persisted Query JSON（2 个）

客户端的 operation name（query/mutation 文档里的名字）也要对齐：

**customer.json**：
```json
{
  "hash1": {
    "name": "todo_get",
    "query": "query todo_get($id: ID!) { todo_get(id: $id) { ... } }"
  },
  "hash2": {
    "name": "todo_list",
    "query": "query todo_list($cursor: String, $limit: Int) { todo_list(cursor: $cursor, limit: $limit) { ... } }"
  },
  "hash3": {
    "name": "todo_create",
    "query": "mutation todo_create($input: CreateTodoInput!) { todo_create(input: $input) { ... } }"
  }
}
```

**admin.json**：
```json
{
  "hash1": {
    "name": "todo_list",
    "query": "query todo_list(...) { todo_list(...) { ... } }"
  },
  "hash2": {
    "name": "todo_batchDelete",
    "query": "mutation todo_batchDelete($ids: [ID!]!) { todo_batchDelete(ids: $ids) { ... } }"
  }
}
```

注意：hash 值会变（query 文本变了），如果客户端已经用 hash 做 persisted query，需要同步更新客户端。

### 4. DGS Codegen 生成类型

codegen 生成的 input 类名基于 schema 字段名。字段名改了可能影响生成的 constant 类。确认 codegen 配置 `generateConstants = true` 时生成的常量名。

### 5. 测试

- `PersistedQueryStoreTest.kt` — 更新测试数据
- 集成测试中如有 hardcode 的 query string — 更新

---

## 文件改动清单

| 文件 | 改动 |
|------|------|
| `schema/customer.graphqls` | Query/Mutation 字段名全部改 |
| `schema/admin.graphqls` | extend Mutation 字段名改 |
| `CustomerTodoFetcher.kt` | `@DgsQuery(field=...)` / `@DgsMutation(field=...)` |
| `CustomerScanFetcher.kt` | 同上 |
| `CustomerCollectionFetcher.kt` | 同上 |
| `CustomerIapFetcher.kt` | 同上 |
| `CustomerFeedbackFetcher.kt` | 同上 |
| `AdminTodoFetcher.kt` | 同上 |
| `AdminAppConfigFetcher.kt` | 同上 |
| `persisted-queries/customer.json` | name + query 文本更新 |
| `persisted-queries/admin.json` | name + query 文本更新 |
| 相关测试 | 更新 |

---

## 客户端同步

这是 **breaking change**。客户端需要同步更新：
- GraphQL query 文档里的字段名（`createTodo(...)` → `todo_create(...)`）
- operation name（`mutation CreateTodo` → `mutation todo_create`）
- persisted query hash（文本变了 hash 必然变）

**建议**：跟客户端版本发布协调，服务端先支持新旧两套（过渡期），或通过 app 强更一次性切换。

---

## 验证

- `./gradlew build`
- `./gradlew generateJava` 确认生成类型正确
- 发请求验证新旧 operation name 都能走通（如果做过渡期兼容）
