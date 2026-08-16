# 计划：GraphQL Schema 拆分为 admin / customer / common

## 目标

当前所有类型和操作都在一个 `schema/schema.graphqls` 里。拆为：

```
src/main/resources/schema/
├── common.graphqls      # scalar、directive、共享 type/input
├── customer.graphqls    # customer 的 Query/Mutation
└── admin.graphqls       # admin 的 Query/Mutation
```

DGS 自动合并同目录下所有 `.graphqls` 文件，Query/Mutation 类型会被合并（type extension），无需手动拼接。

---

## 拆分规则

### common.graphqls

- scalar 定义：`DateTime`, `JSON`, `Long`
- directive：`@requirePermission`
- 共享 type：`Todo`, `TodoItem`, `TodoConnection`, `OperationResult`, `ScanRecord`, `ScanConnection`, `PresignUploadResult`, `PresignDownloadResult`, `Collection`, `CollectionItemType`, `CollectionItemConnection`, `AppConfigRevision`, `VerifyPurchaseResult`
- 共享 input：`CreateTodoInput`, `UpdateTodoInput`, `CreateTodoItemInput`, `UpdateTodoItemInput`, `NewScanInput`, `PresignUploadInput`, `PresignDownloadInput`, `AddCollectionItemInput`, `SubmitFeedbackInput`, `VerifyPurchaseInput`, `CreateAppConfigRevisionInput`

### customer.graphqls

```graphql
type Query {
    todo(id: ID!): Todo
    todos(cursor: String, limit: Int, userId: String): TodoConnection!
    scanRecord(id: ID!): ScanRecord
    scanRecords(cursor: String, limit: Int, collected: Boolean): ScanConnection!
    defaultCollection: Collection!
    collectionItems(collectionId: ID, cursor: String, limit: Int): CollectionItemConnection!
    currentAppConfig: AppConfigRevision
}

type Mutation {
    createTodo(input: CreateTodoInput!): Todo! @requirePermission(permission: "todo:write")
    updateTodo(id: ID!, input: UpdateTodoInput!): Todo! @requirePermission(permission: "todo:write")
    deleteTodo(id: ID!): Boolean! @requirePermission(permission: "todo:write")
    createTodoItem(todoId: ID!, input: CreateTodoItemInput!): TodoItem! @requirePermission(permission: "todo:write")
    updateTodoItem(id: ID!, input: UpdateTodoItemInput!): TodoItem! @requirePermission(permission: "todo:write")
    deleteTodoItem(id: ID!): Boolean! @requirePermission(permission: "todo:write")
    newScan(input: NewScanInput!): ScanRecord! @requirePermission(permission: "scan:write")
    updateScan(id: ID!, collected: Boolean): Boolean! @requirePermission(permission: "scan:write")
    deleteScan(id: ID!): Boolean! @requirePermission(permission: "scan:write")
    presignUpload(input: PresignUploadInput!): PresignUploadResult! @requirePermission(permission: "storage:write")
    presignDownload(input: PresignDownloadInput!): PresignDownloadResult! @requirePermission(permission: "storage:read")
    addCollectionItem(collectionId: ID, scanRecordId: ID!): ID! @requirePermission(permission: "collection:write")
    removeCollectionItems(collectionId: ID, scanRecordIds: [ID!]!): Int! @requirePermission(permission: "collection:write")
    submitFeedback(input: SubmitFeedbackInput!): ID! @requirePermission(permission: "feedback:write")
    verifyPurchase(input: VerifyPurchaseInput!): VerifyPurchaseResult! @requirePermission(permission: "iap:write")
}
```

### admin.graphqls

```graphql
extend type Mutation {
    batchDeleteTodos(ids: [ID!]!): OperationResult! @requirePermission(permission: "todo:batch")
    batchUpdateTodos(patches: [BatchUpdateTodoInput!]!): OperationResult! @requirePermission(permission: "todo:batch")
    batchDeleteTodoItems(ids: [ID!]!): OperationResult! @requirePermission(permission: "todo:batch")
    createAppConfigRevision(input: CreateAppConfigRevisionInput!): AppConfigRevision! @requirePermission(permission: "appconfig:write")
    toggleAppConfigRevision(id: ID!, enabled: Boolean!): AppConfigRevision! @requirePermission(permission: "appconfig:write")
}
```

注意：admin 用 `extend type Mutation`，DGS 会自动合并到主 Mutation。

---

## 注意事项

1. **DGS 合并机制**：同目录下所有 `.graphqls` 自动合并。只能有一个文件定义 `type Query` / `type Mutation`，其余用 `extend type`。建议 customer.graphqls 定义主 Query/Mutation，admin.graphqls 用 extend。

2. **如果 admin 也有独立 Query**（比如未来加 admin-only 的查询），也用 `extend type Query`。

3. **codegen 不受影响**：DGS codegen 扫描整个 schema 目录，拆文件不影响生成结果。

4. **BatchUpdateTodoInput** 目前在 schema 里定义了但放哪？— 只有 admin 用，放 admin.graphqls 或 common 都行。建议放 common（input 类型共享无害）。

---

## 文件改动

| 操作 | 文件 |
|------|------|
| 删除 | `schema/schema.graphqls` |
| 新建 | `schema/common.graphqls` |
| 新建 | `schema/customer.graphqls` |
| 新建 | `schema/admin.graphqls` |

---

## 验证

- `./gradlew build` 编译通过
- `./gradlew generateJava` 生成的类型不变
- 发请求到 `/customer/graphql` 和 `/admin/graphql` 功能正常
