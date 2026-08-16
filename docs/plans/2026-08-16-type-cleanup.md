# 计划：类型清理 — DGS Codegen + ObjectId + lateinit

## 目标

1. 用 DGS codegen 自动生成 GraphQL 类型，删除手写类型
2. BaseDocument 字段改为 `lateinit`（去掉无意义默认值）
3. `id` / `appId` 等关联 ID 字段改为 `ObjectId` 类型

---

## 步骤

### Phase 1: DGS Codegen 插件接入

1. **根 `build.gradle.kts`** plugins 块加：
   ```kotlin
   id("com.netflix.graphql.dgs.codegen") version "7.0.3" apply false
   ```

2. **`core-api/build.gradle.kts`** 加插件 + 配置：
   ```kotlin
   plugins {
       id("com.netflix.graphql.dgs.codegen")
   }

   // 生成 Kotlin data class
   tasks.withType<com.netflix.graphql.dgs.codegen.gradle.GenerateJavaTask> {
       packageName = "com.ifmix.api.core.graphql.generated"
       language = "kotlin"
       generateDataTypes = true
       generateInterfaces = false
       generateKotlinNullableClasses = true
       typeMapping = mutableMapOf(
           "DateTime" to "java.time.Instant",
           "JSON" to "Map<String, Any?>",
           "Long" to "kotlin.Long",
       )
   }
   ```

3. **运行 `./gradlew generateJava`**，确认生成产物在 `core-api/build/generated/` 下。

4. **删除手写类型文件**（全部 6 个）：
   - `graphql/common/type/TodoType.kt`
   - `graphql/common/type/TodoItemType.kt`
   - `graphql/common/type/TodoConnection.kt`
   - `graphql/common/type/OperationResult.kt`
   - `graphql/common/type/ScanTypes.kt`
   - `graphql/common/type/CollectionTypes.kt`

5. **修正 import**（9 个文件引用了旧类型）：
   - `CustomerTodoFetcher.kt` → import generated 包的 `Todo`, `TodoItem`, `TodoConnection`
   - `CustomerScanFetcher.kt` → import generated 的 `ScanRecord`, `ScanConnection`, `PresignUploadResult`, `PresignDownloadResult`
   - `CustomerCollectionFetcher.kt` → import generated 的 `Collection`, `CollectionItemConnection`
   - `AdminTodoFetcher.kt` → import generated 的 `OperationResult`
   - `TodoItemDataLoader.kt` → import generated 的 `TodoItem`
   - `TodoMapper.kt` → import generated 的 `Todo`, `TodoItem`
   - `ScanGraphQLMapper.kt` → import generated 的 `ScanRecord`
   - `CollectionGraphQLMapper.kt` → import generated 的 `CollectionItemType`, `Collection`, `ScanRecord`
   - `SchemaTypeConsistencyTest.kt` → 该测试验证手写类型与 schema 一致，codegen 后此测试无意义，**删除**

6. **字段名适配**：codegen 生成的类名基于 schema type 名。注意：
   - schema `Todo` → 生成 `Todo`（原来手写叫 `TodoType`）
   - schema `TodoItem` → 生成 `TodoItem`（原来叫 `TodoItemType`）
   - schema `ScanRecord` → 生成 `ScanRecord`（原来叫 `ScanRecordType`）
   - schema `Collection` → 生成 `Collection`（原来叫 `CollectionType`）
   - schema `CollectionItemType` → 生成 `CollectionItemType`（名字不变）

   mapper 里的构造调用需要对应修改函数名/类名。

---

### Phase 2: BaseDocument 改 lateinit

1. **`BaseDocument.kt`** 修改：
   ```kotlin
   abstract class BaseDocument {
       @Id
       lateinit var id: String
       lateinit var createdAt: Instant
       lateinit var updatedAt: Instant
   }
   ```

2. **`BaseAppDocument`** 修改：
   ```kotlin
   abstract class BaseAppDocument : BaseDocument(), AppScoped, SoftDeletable {
       override lateinit var appId: String
       override var deletedAt: Instant? = null  // 这个保持 null 默认值，语义正确
   }
   ```

3. **`AppScoped` 接口** — `appId` 类型保持 `String`（Phase 3 再改 ObjectId）。

4. **检查所有 create 路径**确保显式赋值 `createdAt` / `updatedAt`：
   - `TodoItemService.create` — ✅ 已显式赋值
   - `TodoService` 里的 create — 需检查
   - `CRUDRepository.insertOne` — 当前只做 `mongo.insert(entity)`，不自动盖时间戳。**建议**在 `insertOne` 里加：
     ```kotlin
     fun insertOne(ctx: RequestContext, entity: T): String {
         val now = Instant.now()
         if (!entity::createdAt.isInitialized) entity.createdAt = now
         if (!entity::updatedAt.isInitialized) entity.updatedAt = now
         if (appScoped) (entity as AppScoped).appId = ctx.appId
         mongo.insert(entity)
         return entity.id
     }
     ```
     注意：`::isInitialized` 只对 `lateinit` 属性可用，需要该属性在当前类可见（可通过反射或在 BaseDocument 加 helper 方法）。
     
     **更简单的方案**：在 `insertOne` 里无条件盖时间戳（反正都是新建）：
     ```kotlin
     fun insertOne(ctx: RequestContext, entity: T): String {
         val now = Instant.now()
         entity.createdAt = now
         entity.updatedAt = now
         if (appScoped) (entity as AppScoped).appId = ctx.appId
         mongo.insert(entity)
         return entity.id
     }
     ```
     这样各 service 的 create 里不再需要手动赋 createdAt/updatedAt。

5. **id 赋值**：MongoDB `mongo.insert()` 会自动生成 `_id` 并回写到 entity（Spring Data Mongo 行为），所以 `id` 不需要手动赋值，`lateinit` 在 insert 后被填充。确认 insert 后返回 `entity.id` 可用。

---

### Phase 3: id / appId 改为 ObjectId

1. **`BaseDocument.kt`**：
   ```kotlin
   @Id
   lateinit var id: ObjectId
   ```

2. **`AppScoped` 接口**：
   ```kotlin
   interface AppScoped {
       var appId: ObjectId
   }
   ```

3. **`BaseAppDocument`**：
   ```kotlin
   override lateinit var appId: ObjectId
   ```

4. **所有 Document 子类里引用其他文档 id 的字段改为 ObjectId**：
   - `TodoItemDocument.todoId: String` → `ObjectId`
   - `CollectionItemDocument` 里的 `collectionId`, `scanRecordId` → `ObjectId`
   - 其他有 `userId`, `installId` 的如果存的是 ObjectId 也要改（但这些可能是外部 ID/字符串，需确认）

5. **`CRUDRepository`** 影响：
   - `insertOne` 返回值从 `String` 改为 `ObjectId`（或 `.toHexString()`）
   - `findById(ctx, id: String)` — 参数保持 String（GraphQL 层传入的是 hex string），内部已经在用 `ObjectId(id)` 构造查询，不用改
   - `invalidId` 验证逻辑不变

6. **所有 mapper 里 id 字段加 `.toHexString()`**：
   - `TodoMapper.kt`: `id = this.id.toHexString()`, `todoId = this.todoId.toHexString()`
   - `ScanGraphQLMapper.kt`: 同理
   - `CollectionGraphQLMapper.kt`: 同理

7. **Service 层**：
   - `TodoItemService.create` 返回 `String` — 改为 `entity.id.toHexString()` 或返回 `ObjectId`
   - 各处 `repo.insertOne(ctx, doc)` 返回值适配

8. **`RequestContext.appId`** 改为 `ObjectId`：
   - `RequestContext` 类里 `appId: String` → `appId: ObjectId`
   - 构建 RequestContext 的地方（auth interceptor / context builder）在解析时就转为 ObjectId
   - `CRUDRepository.insertOne` 里直接 `(entity as AppScoped).appId = ctx.appId`，无需转换
   - GraphQL context builder 同理适配

---

## 执行顺序

建议按 Phase 2 → Phase 3 → Phase 1 顺序执行（先改底层，再接 codegen），或者 Phase 1 先做也行（纯加法，不影响运行时）。

最安全的顺序：**Phase 1 → Phase 2 → Phase 3**，每步完成后 `./gradlew build` 确认编译通过。

---

## 验证

- 每个 Phase 完成后运行 `./gradlew build`（编译 + 测试）
- Phase 1 后确认 `generateJava` task 生成正确的 Kotlin data class
- Phase 3 后跑集成测试（testcontainers MongoDB）确认 CRUD 正常
