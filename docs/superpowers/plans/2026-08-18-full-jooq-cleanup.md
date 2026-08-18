# 全量切 jOOQ + GraphQL Operation 重命名计划

> 日期: 2026-08-18
> 前置: jOOQ 基础设施已就绪，Todo 模块已示范完成，大部分模块新 repo 已写好但仍有残留 Jimmer 引用

## 目标

1. 消除所有 Jimmer 依赖（entity/、infra/jimmer/、infra/repo/、KSP、dto 文件）
2. 所有 service 完全使用 jOOQ repo + CrudOps + TxRunner
3. GraphQL operation 命名统一为 `${query|mutation}_${module}_${action}`
4. 删除所有旧代码，编译通过

---

## Operation 命名规则

**格式**: `${query|mutation}_${module}_${action}`

**不需要 `core_` 前缀** — Federation 时 subgraph 本身已隔离，field name 里不需要再声明来源。

### 命名映射表

| 旧名 | 新名 | 模块 |
|------|------|------|
| `query_findTodoById` | `query_todo_findById` | todo |
| `query_findTodosByCursor` | `query_todo_findByCursor` | todo |
| `query_findTodosByIds` | `query_todo_findByIds` | todo |
| `mutation_createTodo` | `mutation_todo_create` | todo |
| `mutation_updateTodo` | `mutation_todo_update` | todo |
| `mutation_updateTodoItems` | `mutation_todoItem_batchUpdate` | todoItem |
| `mutation_deleteTodoById` | `mutation_todo_delete` | todo |
| `mutation_deleteTodosByIds` | `mutation_todo_batchDelete` | todo |
| `query_findScanById` | `query_scan_findById` | scan |
| `query_findScansByCursor` | `query_scan_findByCursor` | scan |
| `mutation_newScan` | `mutation_scan_create` | scan |
| `mutation_updateScan` | `mutation_scan_update` | scan |
| `mutation_deleteScanById` | `mutation_scan_delete` | scan |
| `query_getDefaultScanCollection` | `query_collection_getDefault` | collection |
| `query_findScanCollectionItemsByCursor` | `query_collection_findItemsByCursor` | collection |
| `mutation_addScanCollectionItem` | `mutation_collection_addItem` | collection |
| `mutation_removeScanCollectionItems` | `mutation_collection_removeItems` | collection |
| `mutation_presignUpload` | `mutation_storage_presignUpload` | storage |
| `mutation_presignDownload` | `mutation_storage_presignDownload` | storage |
| `query_me` | `query_auth_me` | auth |
| `mutation_authGoogle` | `mutation_auth_loginGoogle` | auth |
| `mutation_authApple` | `mutation_auth_loginApple` | auth |
| `mutation_authWechat` | `mutation_auth_loginWechat` | auth |
| `mutation_authAnonymous` | `mutation_auth_loginAnonymous` | auth |
| `mutation_authExchange` | `mutation_auth_exchange` | auth |
| `mutation_authRefresh` | `mutation_auth_refresh` | auth |
| `mutation_authLogout` | `mutation_auth_logout` | auth |
| `mutation_authDeleteAccount` | `mutation_auth_deleteAccount` | auth |
| `mutation_verifyPurchase` | `mutation_iap_verifyPurchase` | iap |
| `mutation_submitFeedback` | `mutation_feedback_submit` | feedback |

---

## 残留的 Jimmer 依赖（需清除）

### 仍使用 Jimmer repo 的模块

| 文件 | 问题 |
|------|------|
| `modules/scan/repo/ScanCollectionRepository.kt` | 继承 BaseAppCrudRepository (Jimmer) |
| `modules/scan/repo/ScanCollectionItemRepository.kt` | 继承 BaseAppCrudRepository (Jimmer) |
| `modules/app/repo/AppInfoRepository.kt` | 继承 BaseCrudRepository (Jimmer) |
| `modules/app/repo/AppConfigRevisionRepository.kt` | 继承 BaseAppCrudRepository (Jimmer) |

### 仍引用 entity/dto 的文件

| 文件 | 引用 |
|------|------|
| `modules/scan/service/ScanService.kt` | `entity.scan.ImageRef`, scan dto |
| `modules/scan/service/ScanCollectionService.kt` | scan collection dto |
| `modules/scan/ScanRunner.kt` | scan dto (ScanInput, ScanMediaItem) |
| `modules/ai/service/SpringAiScanRunner.kt` | scan dto |
| `modules/auth/service/AuthService.kt` | auth dto |
| `modules/auth/dto/AuthDtos.kt` | 手写 DTO |
| `modules/iap/service/IapService.kt` | iap dto |
| `modules/iap/dto/` | 手写 DTO |
| `bff/graphql/customer/CollectionFetcher.kt` | scan collection dto |
| `bff/graphql/customer/FeedbackFetcher.kt` | entity reference |

### 基础设施残留

| 文件/目录 | 替换 |
|-----------|------|
| `infra/repo/BaseCrudRepository.kt` | 删除（用 CrudOps） |
| `infra/repo/BaseAppCrudRepository.kt` | 删除（用 CrudOps） |
| `infra/jimmer/` 整个目录 | 提取 DataSource 逻辑到 `infra/jooq/`，其余删除 |
| `entity/` 整个目录 | 删除（用 model/） |
| `src/main/dto/` | 删除 |
| `infra/http/Envelope.kt` | 保留（webhook 用） |
| `infra/http/GlobalExceptionHandler.kt` | 保留但精简（REST 部分） |

---

## 执行步骤

### Phase 1: 清除 scan/collection 的 Jimmer 依赖

| # | 任务 |
|---|------|
| 1.1 | 重写 `ScanCollectionRepository` 用 CrudOps（不继承 BaseAppCrudRepository） |
| 1.2 | 重写 `ScanCollectionItemRepository` 用 CrudOps |
| 1.3 | `ScanCollectionService` 去掉 Jimmer entity/dto 引用，改用 model + 新 repo |
| 1.4 | `CollectionFetcher` 去掉旧 dto 引用 |
| 1.5 | `ScanService` 去掉 Jimmer entity 引用（`ImageRef` 移到 `model/`） |
| 1.6 | `ScanRunner` interface + `SpringAiScanRunner` 的 DTO（`ScanInput`, `ScanMediaItem`）移到 `modules/scan/` 或 `model/` |

### Phase 2: 清除 app 模块的 Jimmer 依赖

| # | 任务 |
|---|------|
| 2.1 | 重写 `AppInfoRepository` 用 CrudOps |
| 2.2 | 重写 `AppConfigRevisionRepository` 用 CrudOps |
| 2.3 | `AppConfigService` 去掉 Jimmer entity/dto 引用 |

### Phase 3: 清除 auth 模块的旧 DTO

| # | 任务 |
|---|------|
| 3.1 | `AuthDtos.kt` 内的类型 → DGS codegen 生成（已在 schema 中）或移到 service 内 |
| 3.2 | `AuthService` 去掉 `modules/auth/dto/` 引用，改用 DGS 生成的类型或内联 |
| 3.3 | `ProviderVerifier` / `WechatVerifier` 如引用旧 DTO 则修正 |

### Phase 4: 清除 iap 模块的旧 DTO

| # | 任务 |
|---|------|
| 4.1 | `IapTypes.kt` / `IapDto.kt` → DGS 生成或内联 |
| 4.2 | `IapService` 去掉旧 DTO 引用 |
| 4.3 | `WebhookController` 检查是否引用旧 DTO |

### Phase 5: GraphQL operation 重命名

| # | 任务 |
|---|------|
| 5.1 | 更新所有 `.graphqls` schema 文件中的 field name |
| 5.2 | 更新所有 DataFetcher 的 `@DgsQuery(field=...)` / `@DgsMutation(field=...)` |
| 5.3 | `./gradlew :core-api:generateJava` 重新生成 DGS types |
| 5.4 | 编译通过 |

### Phase 6: 删除 Jimmer 残留 + 依赖

| # | 任务 |
|---|------|
| 6.1 | 删除 `entity/` 整个目录 |
| 6.2 | 删除 `src/main/dto/` 整个目录 |
| 6.3 | 删除 `infra/repo/` 整个目录 |
| 6.4 | 删除 `infra/jimmer/` — 但先提取 `ClusterRegistry` 的 DataSource 创建逻辑到 `infra/jooq/DataSourceConfig.kt` |
| 6.5 | `build.gradle.kts` 删除 Jimmer 依赖 + KSP plugin + KSP args |
| 6.6 | 删除 `springdoc-openapi` 依赖（如还在） |
| 6.7 | 编译通过 |

### Phase 7: 验证

| # | 任务 |
|---|------|
| 7.1 | `./gradlew :core-api:compileKotlin` 通过 |
| 7.2 | GraphiQL 测试几个 query/mutation |
| 7.3 | 更新 ARCHITECTURE.md / AGENTS.md |

---

## 并行策略

```
Phase 1 + 2 + 3 + 4 — 可并行（各模块独立）
    ↓
Phase 5 — operation 重命名（全局）
    ↓
Phase 6 — 删除 Jimmer
    ↓
Phase 7 — 验证
```

---

## DataSource 迁移说明

`infra/jimmer/ClusterRegistry.kt` 当前负责：
- 创建 HikariDataSource (writer + reader)
- 创建 ReadWriteRoutingDataSource
- 创建 KSqlClient（Jimmer 专属，要删）

需要提取到 `infra/jooq/DataSourceConfig.kt`：
```kotlin
@Configuration
class DataSourceConfig(private val props: ClusterProperties) {
    @Bean fun writerDataSource(): HikariDataSource = ...
    @Bean fun readerDataSource(): HikariDataSource = ...
    @Bean fun routingDataSource(): DataSource = ReadWriteRoutingDataSource(writer, reader)
}
```

`JooqConfig` 注入 `routingDataSource` 创建 `DSLContext`。
`ReadWriteRoutingDataSource` 保留（或简化为基于 ThreadLocal 的路由）。

---

## Federation 预留

当前命名 `query_todo_findById` 在 Federation 中：
- 每个 subgraph 暴露自己的 field → gateway 合并
- 不同 subgraph 的 module 前缀天然不冲突
- 如果两个 subgraph 都有 `todo` 模块 → 不可能（一个 entity 只属于一个 subgraph）
- Entity reference: `@key(fields: "id")` + `__resolveReference` 由 owner subgraph 提供

不需要额外的 `core_` namespace。
