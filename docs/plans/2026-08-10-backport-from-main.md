# Backport 业务功能到 mongodb 分支

> 从 main (jimmer+pg) 移植业务逻辑变更到 feature/mongodb，保持 MongoDB Document/Repository 风格。
> 微信登录(#1)已排除。

---

## Task 2: ScanPrompt 国际化增强

**目标:** 升级 AI 扫描 prompt，支持多语言输出和货币本地化。

**变更范围:**
- `common/ai/ScanPrompt.kt`

**具体内容:**
1. 扩充 OUTPUT SCHEMA 字段：
   - `name_en`（英文名，始终输出）
   - `aliases`（别名数组）
   - `description`（用户可见描述）
   - `primary_category` / `secondary_category` / `tertiary_category`（替代原 `category` / `sub_category`）
   - `dynasty_confidence`（0-1）
   - `materials`（复数，替代 `material`）
   - `techniques`（复数，替代 `technique`）
   - `texture`
   - `depth_cm`
   - `condition` 枚举化：`PRISTINE | EXCELLENT | GOOD | FAIR | POOR | DAMAGED`
   - `restoration_history`
   - `authenticity` 枚举化：`AUTHENTIC | SUSPICIOUS | FAKE | UNCERTAIN`
   - `authenticity_notes`
   - `price_currency`（ISO 4217）
   - `value_confidence`（0-1）
2. 新增规则 #8：status / authenticity / condition 为固定英文 token，不做本地化
3. 新增 `localeInstruction(lang, country, currency)` 方法：根据用户 locale 生成 prompt 前缀
4. 注释/文档从印尼语改为英文

**对 ScanResult / ScanDtos 的影响:** 需要同步更新 `ScanResult.kt` 和 `ScanDtos.kt` 中的数据类字段以匹配新 schema。

---

## Task 3: Todo 批量操作 + 权限检查

**目标:** 添加批量 CRUD 接口 + ownership 归属校验。

**变更范围:**
- `bff/customer/CustomerTodoController.kt`
- `modules/todo/TodoService.kt`
- `modules/todo/TodoDtos.kt`
- `common/db/Ownership.kt`（增强或新增 `ownsRow` 函数）
- `common/db/CRUDRepository.kt`（增加批量方法）

**具体内容:**
1. TodoDocument 增加 `userId` 字段（可选，匿名用户为 null）
2. 新增 `ownsRow(ctx, userId, installId)` 归属判定函数：
   - 登录用户：匹配 userId
   - 匿名用户：匹配 installId
3. Controller 新增接口：
   - `PUT /query/core/todo/findTodosByIds` — 批量查询 + ownership 过滤
   - `POST /mutation/core/todo/updateTodosByIds` — 批量更新（先校验归属）
   - `POST /mutation/core/todo/deleteTodosByIds` — 批量删除（先校验归属）
   - `POST /mutation/core/todo/deleteTodoItemsByIds` — 批量删 item
4. 所有单条操作增加 `mustGetInstallId()` + `checkOwnership()` 前置检查
5. CRUDRepository / CRUDService 新增 `findByIds`、`deleteByIds`、`updateByIds` 通用方法
6. 新增 `ByIdsRequest` DTO（`ids: List<String>`）
7. 返回值统一为 `OperationResult(success, modifiedCount)`

---

## Task 4: Install Binding（设备绑定）

**目标:** 登录时记录 userId ↔ installId 绑定关系。

**变更范围:**
- 新增 `modules/auth/UserInstallBindingDocument.kt`
- 新增 `modules/auth/UserInstallBindingRepo.kt`
- 修改 `modules/auth/MergeOnLoginListener.kt`
- 修改 `modules/auth/AuthLoggedInEvent.kt`（如需增加字段）

**具体内容:**
1. `UserInstallBindingDocument`：
   - `appId`, `userId`, `installId`, `firstSeenAt`, `lastSeenAt`, `loginCount`, `clientIp`, `clientPlatform`
   - MongoDB collection: `core_user_install_binding`
2. `UserInstallBindingRepo`：upsert 方法（按 appId+userId+installId 去重，累加 loginCount，更新 lastSeenAt）
3. `MergeOnLoginListener.onLogin()` 增加逻辑：
   - 调用 `UserInstallBindingRepo.upsert(...)` 记录绑定
   - 保留现有的 scan_record/subscription 归并逻辑

---

## Task 5: UploadRecord（上传记录追踪）

**目标:** presignUpload 时写入上传记录，用于后续校验/清理/统计。

**变更范围:**
- 新增 `modules/storage/UploadRecordDocument.kt`（或放 `common/storage/`）
- 新增 `modules/storage/UploadRecordRepo.kt`
- 修改 `bff/customer/CustomerStorageController.kt`

**具体内容:**
1. `UploadRecordDocument`：
   - `appId`, `installId`(可选), `userId`(可选), `objectKey`, `contentType`, `category`, `clientIp`, `createdAt`
   - MongoDB collection: `core_upload_record`
2. `CustomerStorageController.presignUpload()` 增加参数 `category`（如 "scan"、"avatar"）
3. 调用 presign 后，异步写入一条 UploadRecord

---

## Task 6: StorageConfig 增强（R2 public URL）

**目标:** 支持 Cloudflare R2 自定义域名，presignDownload 可返回公开 URL。

**变更范围:**
- `common/storage/StorageConfig.kt`
- `common/storage/S3ObjectStorage.kt`（或 `ObjectStorage` 接口）

**具体内容:**
1. StorageConfig 新增 `@Value("${app.storage.public-url:}")` 配置
2. 新增 `publicBaseUrl` 属性（trimEnd '/'，空字符串返回 null）
3. 重构 credentials 提取为私有方法，去掉 DefaultCredentialsProvider fallback
4. S3Presigner 加 `pathStyleAccessEnabled(true)`（非 amazonaws.com 端点）
5. `ObjectStorage.presignDownload()` 判断：有 publicBaseUrl 时直接拼接公开 URL，否则走签名

---

## Task 7: RequestLoggingFilter

**目标:** 添加 HTTP 请求/响应结构化日志。

**变更范围:**
- 新增 `common/http/RequestLoggingFilter.kt`

**具体内容:**
1. 继承 `OncePerRequestFilter`
2. 跳过 `/actuator`、`/api-docs`、`/swagger-ui` 等路径
3. 使用 `ContentCachingRequestWrapper` + `ContentCachingResponseWrapper`
4. 正常请求：DEBUG 打印 method、path、请求体摘要、status、耗时
5. 错误响应（4xx/5xx）：WARN 额外打印响应体

---

## Task 8: Envelope OpenAPI Schema

**目标:** 让 Swagger UI 正确展示 Envelope 包装后的响应结构。

**变更范围:**
- 新增或增强 `common/config/EnvelopeSchemaCustomizer.kt`（实现 OpenAPI `GlobalOpenApiCustomizer`）

**具体内容:**
- 遍历所有 operation 的 response schema，自动包装为 `{ code, message, data: T }` 结构
- 在 OpenAPI spec 层面体现，不影响运行时逻辑

---

## Task 9: ScanRecord 新增 `collected` 字段

**目标:** 标记扫描记录是否已被收藏。

**变更范围:**
- `modules/antique/ScanRecordDocument.kt`
- 相关 DTO/mapper

**具体内容:**
- 新增 `collected: Boolean = false` 字段
- 收藏操作时更新该字段（已有 CollectionService 逻辑中补充）

---

## 执行顺序建议

```
Task 6 (StorageConfig)          -- 无依赖，独立基础设施
Task 7 (RequestLoggingFilter)   -- 无依赖，独立基础设施
Task 9 (ScanRecord collected)   -- 小改动，独立
Task 2 (ScanPrompt)             -- 独立，但涉及 ScanResult DTO
Task 5 (UploadRecord)           -- 依赖 Task 6 的 StorageConfig
Task 4 (Install Binding)        -- 依赖理解 auth 模块
Task 3 (Todo 批量+权限)         -- 改动最大，放后面
Task 8 (Envelope OpenAPI)       -- 可选，最后做
```

---

## 注意事项

- 所有新 Document 类继承现有 `BaseDocument`（含 `id`、`appId`、`createdAt`、`updatedAt`、`deletedAt`）
- Repository 继承 `CRUDRepository<T>` 或 `CRUDAppRepository<T>`
- 保持现有 `RequestContext` 模型（不引入 main 中的 `OperationContext` 重命名）
- ID 保持 MongoDB ObjectId hex string（不改 UUID）
- 测试按现有 `AbstractMongoTest` 模式编写
