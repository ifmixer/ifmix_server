# Internal Service 参数统一改为 SvcCtx

> 日期: 2026-08-18

## 问题

Internal Service 的方法参数应该是 `SvcCtx`（由 FacadeService 构建好传入），但当前多处仍接收 `OperationContext` 并自己构建 `svc(opCtx)`。

## 需要改的文件

| 文件 | 问题方法 |
|------|---------|
| `modules/ai/service/internal/ScanInternalService.kt` | findById, findByCursorFiltered, presignedUploadUrl, presignedDownloadUrl, getPublicUrl + 内含 `private fun svc()` |
| `modules/ai/service/internal/ScanCollectionInternalService.kt` | findItemsByCursor |
| `modules/scan/service/internal/ScanInternalService.kt` | presignedUploadUrl, presignedDownloadUrl, getPublicUrl |
| `modules/storage/service/internal/StorageInternalService.kt` | presignUpload, presignDownload |
| `modules/todo/service/internal/TodoInternalService.kt` | findById, findByIds, findByCursor, findItemsByTodoIds + 内含 `private fun svc()` |

## 改法

```kotlin
// 改前 — Internal Service 自己构建 SvcCtx
class TodoInternalService(...) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)
    fun findById(opCtx: OperationContext, id: UUID) = ops.findById(svc(opCtx), id, repo::findById)
}

// 改后 — 接收 SvcCtx，由 FacadeService 传入
class TodoInternalService(...) {
    fun findById(sc: SvcCtx, id: UUID) = ops.findById(sc, id, repo::findById)
}
```

FacadeService 对应调整：
```kotlin
// 改前
fun findById(opCtx: OperationContext, id: UUID) = internal.findById(opCtx, id)

// 改后
fun findById(opCtx: OperationContext, id: UUID) = internal.findById(svcCtxFactory.forApp(opCtx), id)
```

## 注意

- Internal Service 删除所有 `private fun svc()` 方法
- Internal Service 不再 import `OperationContext`
- Internal Service 不再 import `SvcCtxFactory`
- 只有 FacadeService 调 `svcCtxFactory.forApp/forAuthTenant`
- `CrudServiceOps` 的方法已接收 `SvcCtx`，不需要改

## 步骤

```
1. 各 Internal Service 方法参数 OperationContext → SvcCtx
2. 删除 Internal Service 里的 private fun svc()
3. FacadeService 调 internal 时传 svcCtxFactory.forApp(opCtx)
4. 编译通过
```
