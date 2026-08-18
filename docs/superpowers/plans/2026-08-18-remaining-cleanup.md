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
| OperationResult | domain model（typeMapping 指向 dto.common.OperationResult）✅ |

---

## 已完成

### DTO 目录重组（提交 `04a7aa4`）✅
- `infra/dto/` → `dto/common/`（Page, CommonDto, CursorQueryInput）
- `modules/scan/dto/` → `dto/scan/`（ScanDto, ScanCollectionDto）
- `modules/feedback/dto/` → `dto/feedback/`（FeedBackDto）
- `modules/iap/` → `dto/iap/`（IapDto, IapTypes）
- 新建 `dto/storage/PresignResult.kt`
- DGS typeMapping 添加 OperationResult

### 去除 @Transactional（提交 `7dd42bc`, `70a8348`, `bf4e873`）✅
- `FeedbackFacadeService.kt` — 1 个方法改用 `tx.withTx`
- `AppConfigFacadeService.kt` — 2 个方法改用 `tx.withTx`
- `ScanCollectionFacadeService.kt` — 3 个方法改用 `tx.withTx`

### 事务上移到 FacadeService（提交 `389374c`）✅
- `ScanFacadeService` — 3 个 mutation 方法改用 `tx.withTx`
- `ScanCommands` — 接收 SvcCtx，去掉 TxRunner
- `IapFacadeService` — 3 个方法改用 `tx.withTx`
- `IapCommands` — 接收 SvcCtx，去掉 TxRunner
- `IapWebhookHandler` — 接收 SvcCtx，去掉 TxRunner

### 模块统一 FacadeService + Internal Service 结构（提交 `1819584`）✅
| 模块 | FacadeService | Internal 拆分 | 事务在 Facade | 状态 |
|------|:---:|:---:|:---:|:---:|
| todo | ✅ | ✅ | ✅ | 拆分到独立文件 ✅ |
| scan | ✅ | ✅ | ✅ | 事务上移 ✅ |
| scanCollection | ✅ | ✅ | ✅ | 拆出 Queries/Commands ✅ |
| auth | ✅ | ✅ | ✅ | 保留单文件（417行）✅ |
| iap | ✅ | ✅ | ✅ | 事务上移 ✅ |
| feedback | ✅ | ✅ | ✅ | 拆出 FeedbackCommands ✅ |
| storage | ✅ | ✅ | ✅ | OK ✅ |
| app | ✅ | ✅ | ✅ | 拆出 AppConfigCommands ✅ |
| ai | — | — | — | 不需要 Facade ✅ |
