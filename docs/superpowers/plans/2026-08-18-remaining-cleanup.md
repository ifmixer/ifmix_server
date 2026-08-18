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

## 追加: 去除 @Transactional

以下文件仍用 `@Transactional`，需要改为 `tx.withTx(svc(opCtx)) { ... }`：

| 文件 | 行 |
|------|-----|
| `modules/app/service/AppConfigFacadeService.kt` | line 40, 53 |
| `modules/scan/service/ScanCollectionFacadeService.kt` | line 30, 46, 53 |
| `modules/feedback/service/FeedbackFacadeService.kt` | line 20 |

改法：去掉 `@Transactional` 注解，用 `tx.withTx(svc(opCtx)) { sc -> ... }` 包裹。
注入 `TxRunner`，构建 `svc(opCtx)` 走 `SvcCtx.DEFAULT.dsl`。
