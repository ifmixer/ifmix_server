# 剩余整理任务

> 日期: 2026-08-18
> 状态: **全部完成** ✅
## 已完成

### Context 分层重构（提交 `6a816de`）
- `RequestContext` — per-request，从 HTTP header 解析
- `OperationContext` — per-operation，由 `OperationContextProvider.fromDfe()` 构建
- `SvcCtx` — per-service-call，Service 层构建，含 DSL/routing 决策
- TxRunner、CrudRepoOps、CrudServiceOps 全部适配 SvcCtx
- 所有 repo 层参数 `RepoContext → SvcCtx`
- todo/auth/feedback/app/scanCollection 模块完成 FacadeService 拆分

### FacadeService 拆分 + Storage（提交 `74225f7`）
- `ScanFacadeService` + `ScanQueries` / `ScanCommands`（替换原 `AntiqueService`）
- `IapFacadeService` + `IapCommands` / `IapWebhookHandler`
- `StorageFacadeService` + `StorageCommands`（Fetcher 不再直连 repo）
- `WebhookController` 改为注入 `IapFacadeService`
- DGS typeMapping：`Todo`/`TodoItem`/`ScanCollection` 使用 domain model

### TypeMapping 完善 + Fetcher 清理（提交 `9f72978`）
- `ScanRecord` → domain model（field alias `images` = `imageKeys`）
- `ImageRef` → domain model
- `ScanCollection` → domain model
- `ScanFetcher` — 去掉 `toDgs()` 转换，直接返回 domain model
- `CollectionFetcher` — 去掉 `Dgs*` alias，直接使用 domain model
- `ScanCollectionFacadeService.findItemsByCursorPage` — 用 stub ScanRecord 填充 scanRecord 字段（暂未实现 DataFetcher）
- `ScanCollectionItem` 保持 generated type（关系表，字段与 domain model 不一致）

### TypeMapping 策略总结
| 类型 | 处理 |
|------|------|
| Todo / TodoItem | domain model ✅ |
| ScanRecord | domain model ✅ |
| ScanCollection | domain model ✅ |
| ImageRef | domain model ✅ |
| ScanCollectionItem | generated type（关系表，有 scanRecord 字段） |
| ScanCollectionItemPage / ScanRecordPage / TodoPage | generated type（分页包装） |
| OperationResult | generated type（字段与 infra.dto 略有不同） |


---

## 已完成

### 去除 @Transactional（提交 `7dd42bc`, `70a8348`, `bf4e873`）✅
- `FeedbackFacadeService.kt` — 1 个方法改用 `tx.withTx`
- `AppConfigFacadeService.kt` — 2 个方法改用 `tx.withTx`
- `ScanCollectionFacadeService.kt` — 3 个方法改用 `tx.withTx`


---

## 追加: Internal Service 事务上移到 FacadeService

以下 internal service 仍自己开事务，需要上移到 FacadeService：

| Internal Service | 当前 | 应改为 |
|---|---|---|
| `ScanCommands.kt` | 自己 `tx.withTx(svc(opCtx))` | 接收 `SvcCtx`，不开事务 |
| `IapCommands.kt` | 同上 | 同上 |
| `IapWebhookHandler.kt` | 同上 | 同上 |

**改法：**

```kotlin
// 改前 — ScanCommands
fun newScan(opCtx: OperationContext, input: NewScanInput): ScanRecord = tx.withTx(svc(opCtx)) { txCtx ->
    repo.insert(txCtx, ...)
}

// 改后 — ScanFacadeService 开事务
fun newScan(opCtx: OperationContext, input: NewScanInput): ScanRecord =
    tx.withTx(svc(opCtx)) { sc -> commands.newScan(sc, input) }

// 改后 — ScanCommands 只接收 SvcCtx
fun newScan(sc: SvcCtx, input: NewScanInput): ScanRecord {
    repo.insert(sc, ...)
}
```

同时 internal service 不再注入 `TxRunner`，不再有 `private fun svc(opCtx)` 方法。

步骤：
1. ScanFacadeService 加 `tx.withTx` 包裹每个 mutation 方法
2. ScanCommands 方法参数从 `opCtx: OperationContext` 改为 `sc: SvcCtx`，去掉 `tx.withTx` 和 `svc()`
3. IapFacadeService 同理
4. IapCommands / IapWebhookHandler 同理
5. 编译通过


---

## 追加: 所有模块统一 FacadeService + Internal Service 结构

每个模块应有：
- `XxxFacadeService` — 薄壳，opCtx → svcCtx + 开事务 + 委托
- Internal class（Queries/Commands）— 接收 SvcCtx，纯实现

### 当前状态与待改

| 模块 | FacadeService | Internal 拆分 | 事务在 Facade | 备注 |
|------|:---:|:---:|:---:|------|
| todo | ✅ | ⚠️ 同文件 | ✅ (TodoCommands 类在 Facade 文件里) | 拆到独立文件 |
| scan | ✅ | ✅ | ❌ 在 ScanCommands | 事务上移 |
| scanCollection | ✅ | ❌ 逻辑在 Facade | ✅ | 拆 internal |
| auth | ✅ | ❌ 逻辑在 Facade (417行) | ✅ | 拆 AuthQueries + AuthCommands |
| iap | ✅ | ✅ | ❌ 在 Commands/Handler | 事务上移 |
| feedback | ✅ | ❌ 逻辑在 Facade (33行) | ✅ | 太简单，可不拆 |
| storage | ✅ | ✅ | ✅ | OK ✅ |
| app | ✅ | ❌ 逻辑在 Facade (74行) | ✅ | 拆 AppConfigQueries + AppConfigCommands |
| ai | ❌ | — | — | 内部服务不暴露 GraphQL，不需要 Facade |

### 具体任务

**1. todo** — 把 `TodoFacadeService.kt` 里的 `TodoQueries`/`TodoCommands`/`TodoItemCommands` 拆到独立文件：
```
modules/todo/service/
├── TodoFacadeService.kt         # 只有薄委托
├── TodoQueries.kt
├── TodoCommands.kt
└── TodoItemCommands.kt
```

**2. scan** — ScanCommands 事务上移到 ScanFacadeService，Commands 参数改 SvcCtx

**3. scanCollection** — 从 ScanCollectionFacadeService 拆出 `ScanCollectionQueries` + `ScanCollectionCommands`

**4. auth (最大)** — 从 AuthFacadeService (417行) 拆出：
```
modules/auth/service/
├── AuthFacadeService.kt         # 薄壳
├── AuthQueries.kt               # me()
├── AuthCommands.kt              # login/logout/refresh/exchange/delete
```

**5. iap** — IapCommands/IapWebhookHandler 事务上移到 IapFacadeService，参数改 SvcCtx

**6. app** — 从 AppConfigFacadeService 拆出 `AppConfigQueries` + `AppConfigCommands`

**7. feedback** — 拆 `FeedbackCommands`（即使只有 1 个方法也要有 internal）

### FacadeService 规则

- **简单的单行 repo 查询**（如 `findById`）可以保留在 FacadeService 中直接调 repo/ops
- **其他所有逻辑**必须委托给 Internal Service（Commands/Queries）
- 所有 mutation 必须通过 `tx.withTx` 包裹后委托

```kotlin
@Service
class XxxFacadeService(
    private val queries: XxxQueries,
    private val commands: XxxCommands,
    private val repo: XxxRepository,
    private val ops: CrudServiceOps<Xxx>,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(
        op = opCtx, dsl = opCtx.globalTxDsl ?: SvcCtx.DEFAULT.dsl
    )

    // 简单查询 — 保留在 facade
    fun findById(opCtx: OperationContext, id: UUID) = ops.findById(svc(opCtx), id, repo::findById)

    // 有逻辑的查询 — 走 internal
    fun findByCursorFiltered(opCtx: OperationContext, ...) = queries.findByCursorFiltered(svc(opCtx), ...)

    // Mutation — 开事务 + 走 internal
    fun create(opCtx: OperationContext, input: ...) = tx.withTx(svc(opCtx)) { sc ->
        commands.create(sc, input)
    }
}
```

### Internal Service 模板

```kotlin
@Component
class XxxCommands(private val repo: XxxRepository) {
    // 不注入 TxRunner，不开事务
    // 参数是 SvcCtx（已在事务中）
    fun create(sc: SvcCtx, input: ...): UUID {
        repo.insert(sc, ...)
        return id
    }
}

@Component
class XxxQueries(private val repo: XxxRepository, factory: CrudServiceOpsFactory) {
    private val ops = factory.create(...)
    fun findById(sc: SvcCtx, id: UUID) = ops.findById(sc, id, repo::findById)
}
```
