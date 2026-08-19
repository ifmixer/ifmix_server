# 架构重组 Review — 遗留问题

> 日期: 2026-08-19
> 对象: 2026-08-19-architecture-restructure.md 的执行结果

## 总结

模块重命名和目录结构基本完成（demo/ai/cms/app/payment/storage/auth），但**核心分层约定大量未落地**。当前代码是"文件搬了家，但逻辑没改"的状态。

---

## P0 — 违反计划的核心问题

### 1. Context 分层未使用

**计划**: DataFetcher 传 `OperationContext` → Facade 构建 `ModuleCtx` → Handler 接收 `ModuleCtx` → Repo 接收 `RepoCtx`

**实际**: 全链路仍在传 `RequestContext`（String 类型的 appId/userId/installId）。`OperationContext`/`ModuleCtx`/`RepoCtx` 虽然创建了文件，但**没有任何模块实际使用**。

影响文件:
- `DemoFacade` — 接收 `RequestContext` 而非 `OperationContext`
- `TodoEntityHandler` — 接收 `RequestContext` 而非 `ModuleCtx`
- `TodoRepository` — 接收 `RepoCtx` 但由 Handler 手动 `RepoCtx()` 空构造
- `AiFacade` — 接收 `RequestContext`
- 所有 Fetcher — `getContext()` 返回 `RequestContext` 而非 `OperationContext`
- `GraphQLContextBuilder` — 仍构建 `RequestContext`，未改为 `OperationContext`

### 2. RequestContext 未改 ObjectId

**计划**: 入口处立即转 ObjectId，全链路 `ObjectId`

**实际**: `RequestContext.appId` 仍是 `String`，`userId`/`installId` 仍是 `String?`。所有 Entity 赋值处都还在 `ObjectId(ctx.appId)` 手动转。

### 3. TxRunner 未在 DataFetcher 中使用

**计划**: DataFetcher 的 mutation 用 `txRunner.withTx(opCtx) { txOpCtx -> ... }`

**实际**: DemoFetcher 的 mutation 直接调 Facade 无事务包裹。TxRunner 虽已改造为 ClientSession 模式，但无人调用。

### 4. Mutation 未返回 XxxResult

**计划**: `mutation_demo_createTodo` 返回 `CreateTodoResult { success, todo }`

**实际**: 返回 `Todo!` 直接（schema 和 fetcher 都是），且无条件回查。

### 5. Entity 未直出 GraphQL

**计划**: typeMapping 映射 output type 到 Entity，DataFetcher 直接返回 Entity，零 Mapper

**实际**: 
- 仍在用 `doc.toTodo()` / `doc.toTodoItem()` 手写 mapper 扩展函数
- DGS typeMapping 未配置 output type → Entity
- `build.gradle.kts` 的 typeMapping 未更新
- `TodoConnection` 仍用 DGS 生成的 class 而非 `Page<T>`

### 6. 旧代码未删除

计划明确要删除的文件仍存在：

| 文件 | 状态 |
|------|------|
| `modules/demo/TodoConfig.kt` | ❌ 仍在 |
| `modules/demo/TodoService.kt` | ❌ 仍在 |
| `modules/demo/service/TodoItemService.kt` | ❌ 仍在（改成了 adapter 但仍在） |
| `modules/app/AppConfigView.kt` | ❌ 仍在 |
| `modules/app/AppConfigMapper.kt` | ❌ 仍在 |
| `modules/app/AppConfigConfig.kt` | ❌ 仍在 |
| `modules/ai/AiConfig.kt` | ❌ 仍在 |
| `modules/ai/AiDtos.kt` | ❌ 仍在（ScanDto, CreateScanRequest） |
| `modules/auth/AuthConfig.kt` | ❌ 仍在 |
| `modules/auth/AuthService.kt` | ❌ 仍在 |
| `modules/auth/AuthDtos.kt` | ❌ 仍在 |
| `modules/payment/PaymentConfig.kt` | ❌ 仍在 |
| `common/service/` 目录 | ❌ 仍在（空？需确认） |
| `bff/customer/` 目录 | 已清空 ✓ |

### 7. AiFacade 直接注入 MongoTemplate

**计划**: Service 不直接调 MongoTemplate，必须走 Repo

**实际**: `AiFacade` 构造函数注入了 `mongo: MongoTemplate`，内部直接用 `mongo.updateFirst()` 等。

### 8. Config 类未删除，仍在间接注册 bean

**计划**: 全部 `@Service`/`@Component`，删除 Config

**实际**: `AiConfig`, `AuthConfig`, `AppConfigConfig`, `PaymentConfig`, `TodoConfig` 全部仍在用 `@Bean` 手动注册。Facade/Handler 没有加 `@Service`/`@Component`（部分加了，部分没加）。

---

## P1 — 部分落地但不完整

### 9. GraphQL schema 命名已更新 ✓

`customer.graphqls` 已改为 `query_demo_*` / `mutation_ai_*` 等命名规则。

### 10. ObjectId scalar 已注册 ✓

`ObjectIdScalar.kt` + `GraphQLScalarWiring` 已注册。但 schema 里 ID 字段仍是 `ID!` 而非 `ObjectId!`。

### 11. Cluster enum 已创建 ✓

`common/db/Cluster.kt` 已存在。

### 12. DataLoader 只有 TodoItem ✓（部分）

`TodoItemDataLoader` 存在，但其他 entity（Scan, Collection 等）没有 DataLoader。顶层 query 也未走 DataLoader。

---

## P2 — 需后续处理

### 13. Konvert 依赖未删除

`build.gradle.kts` 未检查，可能仍有 konvert 相关依赖。

### 14. set/unset Update Input 未改

schema 中 `UpdateTodoInput` 仍是 flat 结构（`title, done, meta, unset`），未改为 `{ set: UpdateTodoSetInput, unset: [TodoUnsetField!] }` 模式。

### 15. Entity 字段未改 ObjectId

所有 Entity 的 `userId`, `installId`, `todoId`, `collectionId` 等仍是 `String?`，未改为 `ObjectId?`。

---

## 修复计划

### Phase A: 核心 Context 链路（最高优先级）

1. `RequestContext` 中 `appId: String` → `appId: ObjectId`, `userId/installId: String?` → `ObjectId?`
2. `GraphQLContextBuilder` 构建 `OperationContext`（入口转 ObjectId）
3. 所有 Fetcher: `getContext()` 返回 `OperationContext`
4. 所有 Facade: 接收 `OperationContext`，内部 `ModuleCtx.from(opCtx)` 构建
5. 所有 Handler: 接收 `ModuleCtx`，调 Repo 时 `RepoCtx.from(mc)`
6. 所有 Repo: 接收 `RepoCtx` + 显式 `appId: ObjectId`

### Phase B: Entity 直出 + 删旧代码

1. `build.gradle.kts` typeMapping: output types → Entity class, Connection → Page, OperationResult → 共享 class
2. schema 中 `ID!` → `ObjectId!`
3. Entity 字段 String → ObjectId
4. 删除所有 `*Config.kt`，Facade/Handler 加 `@Service`/`@Component`
5. 删除所有 DTO/Mapper 文件
6. 删除 Konvert 依赖
7. Fetcher 直接返回 Entity（不调 toTodo()）

### Phase C: 事务 + Mutation 结果

1. DemoFetcher mutation 加 `txRunner.withTx`
2. schema: mutation 返回 `XxxResult { success, entity }` 或 `OperationResult`
3. 回查仅在 `dfe.selectionSet.contains("entity")` 时执行

### Phase D: DataLoader 全覆盖 + Filter

1. 所有顶层 query 走 DataLoader
2. 所有关联字段走 DataLoader
3. set/unset Update Input 改造
4. FilterCriteriaParser 实现

## 执行记录

### 本轮完成 (commit 48c5fcb + e7e612e + additional)

| # | 问题 | 状态 |
|---|------|------|
| 8 | Config 类未删除 | ⚠️ 部分保留（ConditionalOnMissingBean 需要） |
| 7 | AiFacade 直接注入 MongoTemplate | ✅ 已移至 ScanRecordRepository |
| 全局 | @Service/@Component 注解 | ✅ 已补全所有 Facade/Handler |
| 全局 | ObjectIdScalar 注册 | ✅ 已在 GraphQLScalarWiring 注册 |
| 全局 | Filter DSL | ✅ 已实现 FilterCriteriaParser + 各 Repo 白名单 |

### 待后续处理

| # | 问题 | 建议方式 |
|---|------|---------|
| 1 | RequestContext → ObjectId 全链路 | 独立大任务，影响 ~100+ 文件 |
| 4 | Mutation 返回 XxxResult | 独立任务，schema+fetcher+handler 联动 |
| 5 | Entity 直出 (DGS typeMapping) | 独立任务，需更新 build.gradle.kts |
| 12 | DataLoader 全覆盖 | 独立任务，需新增多个 DataLoader |
| 14 | UpdateTodoInput set 嵌套改造 | 部分完成，还需 schema 更新 |
| 15 | 所有 Entity String→ObjectId | 与 P0-1 联动，大任务 |
