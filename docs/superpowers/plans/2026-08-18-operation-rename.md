# GraphQL Operation 命名修正

> 日期: 2026-08-18
> 规则: `${query|mutation}_${module}_${action}`，action 包含具体操作对象

## 命名规则

- 格式: `${query|mutation}_${module}_${action}`
- action 必须包含操作对象名称（如 `findTodoById` 而非 `findById`）
- 这样即使将来 federation 合并多个 subgraph，field name 依然自解释

## 重命名映射

| 当前 | 修正后 | 说明 |
|------|--------|------|
| `query_todo_findById` | `query_todo_findTodoById` | 加对象 |
| `query_todo_findByCursor` | `query_todo_findTodosByCursor` | 加对象+复数 |
| `query_todo_findByIds` | `query_todo_findTodosByIds` | 加对象+复数 |
| `mutation_todo_create` | `mutation_todo_createTodo` | 加对象 |
| `mutation_todo_update` | `mutation_todo_updateTodo` | 加对象 |
| `mutation_todo_delete` | `mutation_todo_deleteTodo` | 加对象 |
| `mutation_todo_batchDelete` | `mutation_todo_batchDeleteTodos` | 加对象+复数 |
| `mutation_todoItem_batchUpdate` | `mutation_todoItem_batchUpdateTodoItems` | 加对象+复数 |
| `query_scan_findById` | `query_scan_findScanById` | 加对象 |
| `query_scan_findByCursor` | `query_scan_findScansByCursor` | 加对象+复数 |
| `mutation_scan_create` | `mutation_scan_createScan` | 加对象 |
| `mutation_scan_update` | `mutation_scan_updateScan` | 加对象 |
| `mutation_scan_delete` | `mutation_scan_deleteScan` | 加对象 |
| `query_collection_getDefault` | `query_collection_getDefaultCollection` | 加对象 |
| `query_collection_findItemsByCursor` | `query_collection_findCollectionItemsByCursor` | 加对象 |
| `mutation_collection_addItem` | `mutation_collection_addCollectionItem` | 加对象 |
| `mutation_collection_removeItems` | `mutation_collection_removeCollectionItems` | 加对象 |
| `mutation_storage_presignUpload` | `mutation_storage_presignUpload` | ✅ 已含对象（upload） |
| `mutation_storage_presignDownload` | `mutation_storage_presignDownload` | ✅ 已含对象（download） |
| `query_auth_me` | `query_auth_me` | ✅ 特殊（me 是约定俗成） |
| `mutation_auth_loginGoogle` | `mutation_auth_loginGoogle` | ✅ 已含对象（Google） |
| `mutation_auth_loginApple` | `mutation_auth_loginApple` | ✅ 已含对象 |
| `mutation_auth_loginWechat` | `mutation_auth_loginWechat` | ✅ 已含对象 |
| `mutation_auth_loginAnonymous` | `mutation_auth_loginAnonymous` | ✅ 已含对象 |
| `mutation_auth_exchange` | `mutation_auth_exchangeToken` | 加对象 |
| `mutation_auth_refresh` | `mutation_auth_refreshToken` | 加对象 |
| `mutation_auth_logout` | `mutation_auth_logout` | ✅ 无歧义 |
| `mutation_auth_deleteAccount` | `mutation_auth_deleteAccount` | ✅ 已含对象 |
| `mutation_iap_verifyPurchase` | `mutation_iap_verifyPurchase` | ✅ 已含对象 |
| `mutation_feedback_submit` | `mutation_feedback_submitFeedback` | 加对象 |

## 需要改的文件

每个 operation 改动涉及 2 处：
1. `.graphqls` schema 文件中的 field name
2. DataFetcher 的 `@DgsQuery(field=...)` / `@DgsMutation(field=...)` annotation

## 执行步骤

```
1. 批量更新 schema/customer/*.graphqls 中的 field name
2. 批量更新 bff/graphql/customer/**/*Fetcher.kt 中的 annotation field
3. ./gradlew :core-api:generateJava（DGS codegen 重新生成 client）
4. ./gradlew :core-api:compileKotlin（编译通过）
```

## 不改的（已经自解释）

- `mutation_storage_presignUpload` / `presignDownload`
- `query_auth_me`
- `mutation_auth_loginGoogle/Apple/Wechat/Anonymous`
- `mutation_auth_logout`
- `mutation_auth_deleteAccount`
- `mutation_iap_verifyPurchase`
