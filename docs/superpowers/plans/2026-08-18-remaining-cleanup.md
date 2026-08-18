# 剩余整理任务

> 日期: 2026-08-18
> 状态: Context 分层 + FacadeService 已完成（todo/auth/feedback/app/scanCollection）

## 1. ScanService → ScanFacadeService + 拆分

当前 `ScanService.kt`（名为 `AntiqueService`）需要：
- 重命名为 `ScanFacadeService`
- 拆分 internal 类：`ScanQueries`, `ScanCommands`
- presign 方法留在 facade 或移到 `StorageFacadeService`

```
modules/scan/service/
├── ScanFacadeService.kt          # 对外入口
├── internal/
│   ├── ScanQueries.kt            # findById, findByCursor
│   └── ScanCommands.kt           # create, update, delete
```

注意: 当前类名是 `AntiqueService`，统一改为 `ScanFacadeService`。

## 2. IapService → IapFacadeService + 拆分

```
modules/iap/service/
├── IapFacadeService.kt           # 对外入口
├── internal/
│   ├── IapCommands.kt            # verifyPurchase
│   └── IapWebhookHandler.kt     # handleAppleNotification, handleGoogleNotification
```

## 3. StorageFetcher 改走 FacadeService

当前 `StorageFetcher` 直接注入 `UploadRecordRepository` 并调用 `repo.insert(...)`。需要：
- 创建 `StorageFacadeService`（或合并到 ScanFacadeService）
- presignUpload/presignDownload 逻辑移到 service
- Fetcher 只调 service

```kotlin
// StorageFacadeService
@Service
class StorageFacadeService(
    private val commands: StorageCommands,
) {
    fun presignUpload(opCtx: OperationContext, input: PresignUploadInput): PresignUploadResult = commands.presignUpload(opCtx, input)
    fun presignDownload(opCtx: OperationContext, input: PresignDownloadInput): PresignDownloadResult = commands.presignDownload(opCtx, input)
}

// StorageCommands (internal)
@Component
class StorageCommands(
    private val uploadRecordRepo: UploadRecordRepository,
    private val objectStorage: ObjectStorage,
    private val tx: TxRunner,
) {
    fun presignUpload(opCtx: OperationContext, input: PresignUploadInput): PresignUploadResult {
        val svc = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)
        // 构建 objectKey, presign, 记录 upload record
        ...
    }
}

// StorageFetcher — 只调 service
@DgsComponent
class StorageFetcher(
    private val storageService: StorageFacadeService,
    private val ctxProvider: OperationContextProvider,
) { ... }
```

## 4. DGS typeMapping + Fetcher 去掉手动转换

**问题**: `CollectionFetcher` 和 `ScanFetcher` 把 domain model 手动转成 DGS 生成的类型（如 `DgsScanCollection(...)`）。加了 typeMapping 后 DGS 不再生成这些类型，fetcher 直接返回 domain model。

**必须同时做**（否则编译不过）：
1. `build.gradle.kts` typeMapping 加:
   ```kotlin
   "ScanRecord" to "com.ifmix.api.core.model.scan.ScanRecord",
   "ScanCollection" to "com.ifmix.api.core.model.scan.ScanCollection",
   "ScanCollectionItem" to "com.ifmix.api.core.model.scan.ScanCollectionItem",
   "ImageRef" to "com.ifmix.api.core.model.ImageRef",
   "OperationResult" to "com.ifmix.api.core.infra.dto.OperationResult",
   ```
2. `CollectionFetcher.kt` — 删除所有 `import com.ifmix.api.core.generated.types.{ScanCollection,ScanCollectionItem,ScanRecord,ImageRef} as Dgs*`，直接返回 domain model（去掉 `DgsScanCollection(...)` 手动构造）
3. `ScanFetcher.kt` — 同上，`import ...ScanRecord as DgsScanRecord` 删掉，直接返回 domain model
4. `AuthFetcher.kt` — `OperationResult` import 从 `generated.types` 改为 `infra.dto`
5. `rm -rf core-api/build/generated/sources/dgs-codegen && ./gradlew :core-api:generateJava`
6. 编译通过

**注意**: ScanCollectionItemPage/ScanRecordPage/TodoPage 保留 DGS 生成（它们是分页包装类型，domain model 用的是 `Page<T>` 泛型不直接对应）。

## 执行顺序

```
4 → 5 先做 typeMapping（影响后续编译）
1 → 2 拆 service
3 StorageFetcher 改走 service
最后编译通过
```
