# REST → GraphQL (DGS) 迁移设计

> 日期: 2026-08-17
> 状态: 草案

## 目标

将 ifmix_server 从 REST（PUT/POST BFF 风格）迁移到 GraphQL (Netflix DGS 12.x)。

- Service/Repository 层返回 **Jimmer interface**（不再返回 DTO class）
- 查询形状由 GraphQL selection → Jimmer Fetcher 动态决定
- Update 操作使用 **set/unset 语义**，防止 Java/Go 客户端把 `null` 误当 `undefined` 清空数据
- Create 支持嵌入子实体；Update 子实体走独立 mutation（不需要传 parentId，通过 item.id 反查归属）
- Mutation 返回 `{success, entity}` 结构，客户端可按需 select entity 字段（fetcher 层按需查询）
- 不做 persisted query
- 删除所有旧 REST controller，不留包袱

---

## 版本选择

| 依赖 | 版本 | 说明 |
|------|------|------|
| DGS Framework | 12.0.1 | Spring Boot 4 + Jackson 3 原生支持 |
| DGS Codegen (Gradle) | 最新 7.x | schema-first → Kotlin data class |

---

## 核心设计决策

### D1: 查询返回 Jimmer Interface + Fetcher 动态投影

**现状**: Service 返回 `TodoDetailDto` / `TodoListDto`（Jimmer 生成的 DTO class），形状固定。

**改造**: Service 直接返回 Jimmer entity interface（`Todo`、`ScanRecord`），DataFetcher 层根据 GraphQL selection set 构建 `Fetcher<Todo>` 动态决定查询哪些字段。

```kotlin
// Repository — 接受 Fetcher 参数
fun findById(appId: UUID, id: UUID, fetcher: Fetcher<Todo>): Todo? {
    return sql.createQuery(Todo::class) {
        where(table.appId eq appId)
        where(table.id eq id)
        select(table.fetch(fetcher))
    }.limit(1).execute().firstOrNull()
}

// DataFetcher — 从 GraphQL selection 构建 Fetcher
@DgsQuery(field = "query_findTodoById")
fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
    val ctx = ctxProvider.fromDfe(dfe)
    val fetcher = fetcherBuilder.build<Todo>(dfe.selectionSet)
    return todoService.findById(ctx, id, fetcher)
        ?: throw ApiError(ErrorCode.NOT_FOUND)
}
```

**优势**:
- 客户端只请求 `{ id, title }` 时，SQL 只查 2 列（不 join items）
- 请求 `{ id, title, items { content } }` 时自动 join
- 消除 N 个 DTO class（`TodoDetailDto` / `TodoListDto` / `ScanRecordDto` / `ScanRecordListItem`…）

### D2: Update Input — set/unset 防呆

**问题**: Java/Go/Swift 没有 `undefined` 概念。当 client 传 `{ "name": null }` 时，语义不明：
- 意图 A: 用户想清空 name → 应执行 `SET name = NULL`
- 意图 B: 用户没改 name，序列化 zero-value → 不应该动 name

**方案**: `set` 嵌套对象 + `unset` 枚举数组:

```graphql
input UpdateTodoInput {
    id: UUID!
    set: UpdateTodoSetInput
    unset: [TodoUnsetField!]
}

input UpdateTodoSetInput {
    title: String
    done: Boolean
    note: String          # nullable 字段，可通过 unset 清空
}

enum TodoUnsetField {
    NOTE
}
```

**语义规则**:
- `set` 中出现的字段 → `SET col = value`
- `unset` 中列出的字段 → `SET col = NULL`
- 两者都没出现的字段 → 不动
- `set` 和 `unset` 冲突时（同一字段同时出现），`unset` 优先（即清空）

**客户端使用**:
```graphql
# 改标题
mutation { mutation_updateTodo(input: { id: "xxx", set: { title: "新标题" } }) { success } }

# 设置 note
mutation { mutation_updateTodo(input: { id: "xxx", set: { note: "备忘" } }) { success } }

# 清空 note
mutation { mutation_updateTodo(input: { id: "xxx", unset: [NOTE] }) { success } }

# 什么都不传 = 什么都不改（安全）
mutation { mutation_updateTodo(input: { id: "xxx" }) { success } }
```

### D3: Mutation 返回 `{success, entity}` — Fetcher 按需查

Mutation 的返回类型包含可选的实体字段。Service 层仍然只返回 `Boolean`/`Unit`，**DataFetcher 层根据 client 是否 select 了 entity 字段来决定是否回查**。

```graphql
type UpdateTodoPayload {
    success: Boolean!
    todo: Todo            # 客户端 select 了才查
}
```

DataFetcher 实现：

```kotlin
@DgsMutation(field = "mutation_updateTodo")
fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
    val ctx = ctxProvider.fromDfe(dfe)
    val success = todoService.updateTodo(ctx, input)

    // 客户端是否 select 了 todo 字段？
    val todo = if (dfe.selectionSet.contains("todo")) {
        val fetcher = fetcherBuilder.build<Todo>(dfe.selectionSet.getField("todo").selectionSet)
        todoService.findById(ctx, input.id, fetcher)
    } else null

    return UpdateTodoPayload(success = success, todo = todo)
}
```

客户端可自由选择：
```graphql
# 只要 success
mutation { mutation_updateTodo(input: {...}) { success } }

# 要 success + 更新后的 todo 部分字段
mutation { mutation_updateTodo(input: {...}) { success, todo { id, title, note, items { id, content } } } }
```

### D4: Create 嵌入子实体 / Update 子实体独立 mutation

**Create** — 一次 mutation 包含父 + 子:

```graphql
extend type Mutation {
    mutation_createTodo(input: CreateTodoInput!): CreateTodoPayload!
}

type CreateTodoPayload {
    todo: Todo!
}

input CreateTodoInput {
    title: String!
    done: Boolean
    note: String
    items: [CreateTodoItemInput!]
}
```

**Update 子实体** — 独立 mutation，**不需要传 todoId**（item.id 反查归属）:

```graphql
input UpdateTodoItemsMutationInput {
    create: [CreateTodoItemForTodoInput!]   # 需要指定挂在哪个 todo 下
    update: [UpdateTodoItemInput!]           # 通过 item.id 定位，不需要 todoId
    delete: [UUID!]                          # 通过 item.id 定位
}

input CreateTodoItemForTodoInput {
    todoId: UUID!               # 新建 item 需要指定父
    content: String!
    done: Boolean
}

input UpdateTodoItemInput {
    id: UUID!
    set: UpdateTodoItemSetInput
    # TodoItem 所有标量都是 NOT NULL，暂无 unset 需求
}

input UpdateTodoItemSetInput {
    content: String
    done: Boolean
}
```

### D5: GraphQL Endpoint 按角色拆分

| Endpoint | 用途 | Schema 来源 |
|----------|------|-------------|
| `POST /customer/graphql` | 客户端（移动 App） | `schema/common/` + `schema/customer/` |
| `POST /admin/graphql` | 运营后台 | `schema/common/` + `schema/admin/` |

两个 endpoint 共享 common scalar/type，但各自暴露不同的 Query/Mutation。拦截器分别配置：
- `/customer/graphql` — 需要 `x-app-id`，走 AuthInterceptor
- `/admin/graphql` — 需要 admin token（未来实现）

Schema 文件结构：
```
resources/schema/
├── common/
│   └── common.graphqls         # scalar, OperationResult, 共享 enum
├── customer/
│   ├── scan.graphqls
│   ├── collection.graphqls
│   ├── auth.graphqls
│   ├── todo.graphqls
│   ├── storage.graphqls
│   ├── feedback.graphqls
│   └── iap.graphqls
└── admin/
    └── (future)
```

DGS 多 endpoint 配置：通过 `@DgsComponent` 上的自定义注解或 Spring 的 `@ConditionalOnProperty` 区分。或者更简单：**初期只做 `/customer/graphql`**，admin 后续再加。

---

## Schema 设计

### 公共类型 (common)

```graphql
# schema/common/common.graphqls
scalar UUID       # 22-char Base58
scalar DateTime   # epoch millis (Long)
scalar Long
scalar JSON       # 透传 JSONB

type OperationResult {
    success: Boolean!
    modifiedCount: Int
}

type Query
type Mutation
```

### Scan 模块 (customer)

```graphql
# schema/customer/scan.graphqls
type ScanRecord {
    id: UUID!
    images: [ImageRef!]!
    result: JSON
    status: ScanStatus!
    clientIp: String
    lang: String
    country: String
    currency: String
    userDisplayName: String
    userNotes: String
    collected: Boolean!
    createdAt: DateTime!
    updatedAt: DateTime
}

type ImageRef {
    key: String!
}

enum ScanStatus { PENDING, PROCESSING, COMPLETED, FAILED }

# --- Query ---
input ScanQueryInput {
    cursor: String
    limit: Int
    collected: Boolean
}

type ScanRecordPage {
    items: [ScanRecord!]!
    nextCursor: String
    hasMore: Boolean!
}

extend type Query {
    query_findScanById(id: UUID!): ScanRecord!
    query_findScansByCursor(input: ScanQueryInput): ScanRecordPage!
}

# --- Mutation ---
input NewScanInput {
    images: [NewScanImageInput!]!
}

input NewScanImageInput {
    imageKey: String!
    mediaType: String
}

type NewScanPayload {
    scanRecord: ScanRecord!
}

input UpdateScanInput {
    id: UUID!
    set: UpdateScanSetInput
    unset: [ScanUnsetField!]
}

input UpdateScanSetInput {
    userDisplayName: String
    userNotes: String
    collected: Boolean
}

enum ScanUnsetField { USER_DISPLAY_NAME, USER_NOTES }

type UpdateScanPayload {
    success: Boolean!
    scanRecord: ScanRecord
}

type DeleteScanPayload {
    success: Boolean!
}

extend type Mutation {
    mutation_newScan(input: NewScanInput!): NewScanPayload!
    mutation_updateScan(input: UpdateScanInput!): UpdateScanPayload!
    mutation_deleteScanById(id: UUID!): DeleteScanPayload!
}
```

### Collection 模块 (customer)

```graphql
# schema/customer/collection.graphqls
type ScanCollection {
    id: UUID!
    isDefault: Boolean!
    createdAt: DateTime!
}

type ScanCollectionItem {
    id: UUID!
    scanRecord: ScanRecord!
    createdAt: DateTime!
}

type ScanCollectionItemPage {
    items: [ScanCollectionItem!]!
    nextCursor: String
    hasMore: Boolean!
}

input ListScanCollectionItemsInput {
    cursor: String
    limit: Int
}

extend type Query {
    query_getDefaultScanCollection: ScanCollection!
    query_findScanCollectionItemsByCursor(input: ListScanCollectionItemsInput): ScanCollectionItemPage!
}

input AddScanCollectionItemInput {
    scanRecordId: UUID!
}

type AddScanCollectionItemPayload {
    collectionId: UUID!
    alreadyExists: Boolean!
}

input RemoveScanCollectionItemsInput {
    scanRecordIds: [UUID!]!
}

type RemoveScanCollectionItemsPayload {
    removedCount: Int!
}

extend type Mutation {
    mutation_addScanCollectionItem(input: AddScanCollectionItemInput!): AddScanCollectionItemPayload!
    mutation_removeScanCollectionItems(input: RemoveScanCollectionItemsInput!): RemoveScanCollectionItemsPayload!
}
```

### Auth 模块 (customer)

```graphql
# schema/customer/auth.graphqls
type UserInfo {
    id: UUID!
    email: String
    displayName: String
    avatarUrl: String
}

type LoginPayload {
    accessToken: String!
    refreshToken: String!
    expiresIn: Int!
    user: UserInfo!
    deviceSecret: String
}

type MePayload {
    user: UserInfo!
    tier: String!
    tierActive: Boolean!
    tierExpiresAt: DateTime
}

type RefreshPayload {
    accessToken: String!
    refreshToken: String!
    expiresIn: Int!
}

type ExchangePayload {
    accessToken: String!
    refreshToken: String!
    expiresIn: Int!
}

input ProviderLoginInput {
    idToken: String!
}

input WechatLoginInput {
    code: String!
}

input ExchangeInput {
    deviceSecret: String!
    targetAppId: UUID!
}

input RefreshInput {
    refreshToken: String!
}

input LogoutInput {
    refreshToken: String!
}

extend type Query {
    query_me: MePayload!
}

extend type Mutation {
    mutation_authGoogle(input: ProviderLoginInput!): LoginPayload!
    mutation_authApple(input: ProviderLoginInput!): LoginPayload!
    mutation_authWechat(input: WechatLoginInput!): LoginPayload!
    mutation_authAnonymous: LoginPayload!
    mutation_authExchange(input: ExchangeInput!): ExchangePayload!
    mutation_authRefresh(input: RefreshInput!): RefreshPayload!
    mutation_authLogout(input: LogoutInput!): OperationResult!
    mutation_authDeleteAccount: OperationResult!
}
```

### Todo 模块 (customer)

```graphql
# schema/customer/todo.graphqls
type Todo {
    id: UUID!
    title: String!
    done: Boolean!
    note: String          # nullable — 演示 unset
    meta: JSON
    items: [TodoItem!]!
    createdAt: DateTime!
    updatedAt: DateTime
}

type TodoItem {
    id: UUID!
    content: String!
    done: Boolean!
    createdAt: DateTime!
    updatedAt: DateTime
}

# --- Query ---
input TodoQueryInput {
    cursor: String
    limit: Int
}

type TodoPage {
    items: [Todo!]!
    nextCursor: String
    hasMore: Boolean!
}

extend type Query {
    query_findTodoById(id: UUID!): Todo!
    query_findTodosByCursor(input: TodoQueryInput): TodoPage!
    query_findTodosByIds(ids: [UUID!]!): [Todo!]!
}

# --- Mutation: Create ---
input CreateTodoInput {
    title: String!
    done: Boolean
    note: String
    items: [CreateTodoItemInput!]   # 嵌入子实体
}

input CreateTodoItemInput {
    content: String!
    done: Boolean
}

type CreateTodoPayload {
    todo: Todo!
}

# --- Mutation: Update Todo ---
input UpdateTodoInput {
    id: UUID!
    set: UpdateTodoSetInput
    unset: [TodoUnsetField!]
}

input UpdateTodoSetInput {
    title: String
    done: Boolean
    note: String
}

enum TodoUnsetField {
    NOTE
}

type UpdateTodoPayload {
    success: Boolean!
    todo: Todo
}

# --- Mutation: Update TodoItems（独立，不需要 todoId） ---
input UpdateTodoItemsMutationInput {
    create: [CreateTodoItemForTodoInput!]
    update: [UpdateTodoItemInput!]
    delete: [UUID!]
}

input CreateTodoItemForTodoInput {
    todoId: UUID!                  # 新建时需要指定挂到哪个 todo
    content: String!
    done: Boolean
}

input UpdateTodoItemInput {
    id: UUID!                      # 通过 id 定位，不需要 todoId
    set: UpdateTodoItemSetInput
}

input UpdateTodoItemSetInput {
    content: String
    done: Boolean
}

type UpdateTodoItemsPayload {
    success: Boolean!
}

# --- Mutation: Delete ---
type DeleteTodoPayload {
    success: Boolean!
}

extend type Mutation {
    mutation_createTodo(input: CreateTodoInput!): CreateTodoPayload!
    mutation_updateTodo(input: UpdateTodoInput!): UpdateTodoPayload!
    mutation_updateTodoItems(input: UpdateTodoItemsMutationInput!): UpdateTodoItemsPayload!
    mutation_deleteTodoById(id: UUID!): DeleteTodoPayload!
    mutation_deleteTodosByIds(ids: [UUID!]!): DeleteTodoPayload!
}
```

### Storage 模块 (customer)

```graphql
# schema/customer/storage.graphqls
enum UploadCategory { ANTIQUE_SCAN }
enum ContentType { IMAGE_JPEG, IMAGE_PNG, IMAGE_WEBP }

input PresignUploadInput {
    category: UploadCategory!
    contentType: ContentType!
}

type PresignUploadPayload {
    mediaId: UUID!
    uploadUrl: String!
    imageKey: String!
    downloadUrl: String!
}

input PresignDownloadInput {
    imageKey: String!
    durationSeconds: Int
}

type PresignDownloadPayload {
    url: String!
}

extend type Mutation {
    mutation_presignUpload(input: PresignUploadInput!): PresignUploadPayload!
    mutation_presignDownload(input: PresignDownloadInput!): PresignDownloadPayload!
}
```

### IAP 模块 (customer)

```graphql
# schema/customer/iap.graphqls
enum Platform { APPLE, GOOGLE }

input VerifyPurchaseInput {
    platform: Platform!
    productId: String!
    signedTransaction: String
    purchaseToken: String
}

type VerifyPurchasePayload {
    tier: String!
    expiresAt: DateTime
}

extend type Mutation {
    mutation_verifyPurchase(input: VerifyPurchaseInput!): VerifyPurchasePayload!
}
```

### Feedback 模块 (customer)

```graphql
# schema/customer/feedback.graphqls
enum FeedbackCategory {
    LIKED
    PRICE_TOO_HIGH
    PRICE_TOO_LOW
    PRICE_MISSING
    WRONG_IDENTIFICATION
    FEATURE_REQUEST
    MORE_RECOMMENDATIONS
}

input SubmitFeedbackInput {
    scanRecordId: UUID
    category: FeedbackCategory!
    comment: String
}

type SubmitFeedbackPayload {
    id: UUID!
}

extend type Mutation {
    mutation_submitFeedback(input: SubmitFeedbackInput!): SubmitFeedbackPayload!
}
```

---

## Infra 改造

### GraphQL Endpoint 路由配置

```kotlin
// infra/graphql/GraphQLEndpointConfig.kt
@Configuration
class GraphQLEndpointConfig {

    /**
     * DGS 默认注册 /graphql。
     * 通过 Spring GraphQL 的 RouterFunction 注册 /customer/graphql。
     * /admin/graphql 后续再加。
     */
    @Bean
    fun graphqlEndpointCustomizer(): WebMvcRegistrations {
        // 重映射 DGS 的默认 /graphql → /customer/graphql
        // 方案 A: application.yml 配置
        // 方案 B: 自定义 RouterFunction
    }
}
```

application.yml:
```yaml
spring:
  graphql:
    path: /customer/graphql
    graphiql:
      enabled: true
      path: /customer/graphiql
    schema:
      locations: classpath:schema/common/,classpath:schema/customer/
```

### GraphQL Selection → Jimmer Fetcher 转换

```kotlin
// infra/graphql/FetcherBuilder.kt
@Component
class FetcherBuilder {

    inline fun <reified E : Any> build(
        selectionSet: DataFetchingFieldSelectionSet,
    ): Fetcher<E> {
        return newFetcher(E::class).by {
            applySelection(this, E::class, selectionSet)
        }
    }

    /**
     * 从嵌套 field 的 selectionSet 构建 fetcher（用于 mutation payload 中的 entity 字段）。
     */
    inline fun <reified E : Any> buildFromField(
        parentSelectionSet: DataFetchingFieldSelectionSet,
        fieldName: String,
    ): Fetcher<E>? {
        val field = parentSelectionSet.getField(fieldName) ?: return null
        return build(field.selectionSet)
    }
}
```

### OperationContextProvider

```kotlin
@Component
class OperationContextProvider {

    fun fromDfe(dfe: DgsDataFetchingEnvironment): OperationContext {
        val requestData = DgsContext.getRequestData(dfe) as? DgsWebMvcRequestData
            ?: throw ApiError(ErrorCode.INTERNAL)
        val request = requestData.webRequest?.nativeRequest as? HttpServletRequest
            ?: throw ApiError(ErrorCode.INTERNAL)

        return OperationContext(
            appId = request.getHeader("x-app-id")?.toUuidOrNull(),
            installId = request.getHeader("x-install-id")?.toUuidOrNull(),
            lang = request.getHeader("x-lang"),
            currency = request.getHeader("x-currency"),
            country = request.getHeader("x-country"),
            clientPlatform = ClientPlatform.fromHeader(request.getHeader("x-client-platform")),
            userId = (request.getAttribute(AuthInterceptor.ATTR_USER_ID) as? String)?.toUuidOrNull(),
            clientIp = ClientIpResolver.resolve(request),
        )
    }
}
```

### 异常处理

```kotlin
@Component
class GraphQLExceptionHandler : DefaultDataFetcherExceptionHandler() {

    override fun handleException(params: DataFetcherExceptionHandlerParameters)
        : CompletableFuture<DataFetcherExceptionHandlerResult> {
        val ex = params.exception
        if (ex is ApiError) {
            val error = TypedGraphQLError.newBuilder()
                .message(ex.message ?: ex.errorCode.name)
                .errorType(mapType(ex.errorCode))
                .extensions(mapOf("code" to ex.errorCode.externalCode))
                .path(params.path)
                .build()
            return CompletableFuture.completedFuture(
                DataFetcherExceptionHandlerResult.newResult(error).build()
            )
        }
        return super.handleException(params)
    }

    private fun mapType(code: ErrorCode) = when (code) {
        ErrorCode.UNAUTHORIZED, ErrorCode.FORBIDDEN -> ErrorType.PERMISSION_DENIED
        ErrorCode.NOT_FOUND -> ErrorType.NOT_FOUND
        ErrorCode.RATE_LIMITED -> ErrorType.UNAVAILABLE
        else -> ErrorType.BAD_REQUEST
    }
}
```

### Custom Scalars

```kotlin
@DgsScalar(name = "UUID")
class UuidScalar : Coercing<UUID, String> {
    // serialize: uuid.toBase58() → 22 chars
    // parseValue/parseLiteral: str.toUuidFromBase58()
}

@DgsScalar(name = "DateTime")
class DateTimeScalar : Coercing<Instant, Long> {
    // serialize: instant.toEpochMilli()
    // parse: Instant.ofEpochMilli(long)
}

@DgsScalar(name = "JSON")
class JsonScalar : Coercing<Any, Any> {
    // passthrough Map/List — Jackson 已处理
}
```

### WebConfig 拦截器

```kotlin
override fun addInterceptors(registry: InterceptorRegistry) {
    registry.addInterceptor(headerValidationInterceptor)
        .addPathPatterns("/customer/graphql")
    registry.addInterceptor(authInterceptor)
        .addPathPatterns("/customer/graphql")
    // /admin/graphql 未来加 admin auth interceptor
}
```

---

## DataFetcher 示例（TodoFetcher 完整）

```kotlin
@DgsComponent
class TodoFetcher(
    private val todoService: TodoService,
    private val ctxProvider: OperationContextProvider,
    private val fetcherBuilder: FetcherBuilder,
) {

    // ===== Query =====

    @DgsQuery(field = "query_findTodoById")
    fun findById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): Todo {
        val ctx = ctxProvider.fromDfe(dfe)
        val fetcher = fetcherBuilder.build<Todo>(dfe.selectionSet)
        return todoService.findById(ctx, id, fetcher)
            ?: throw ApiError(ErrorCode.NOT_FOUND)
    }

    @DgsQuery(field = "query_findTodosByCursor")
    fun findByCursor(dfe: DgsDataFetchingEnvironment, @InputArgument input: TodoQueryInput?): TodoPage {
        val ctx = ctxProvider.fromDfe(dfe)
        // items 子字段的 fetcher
        val itemsFetcher = fetcherBuilder.buildFromField<Todo>(dfe.selectionSet, "items")
            ?: fetcherBuilder.build<Todo>(/* minimal: id only */)
        val page = todoService.findByCursor(ctx, input ?: TodoQueryInput(), itemsFetcher)
        return TodoPage(items = page.items, nextCursor = page.nextCursor, hasMore = page.hasMore)
    }

    // ===== Mutation =====

    @DgsMutation(field = "mutation_createTodo")
    fun createTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateTodoInput): CreateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val id = todoService.createTodo(ctx, input)
        // 客户端 select 了 todo 字段？回查
        val todo = fetcherBuilder.buildFromField<Todo>(dfe.selectionSet, "todo")?.let { fetcher ->
            todoService.findById(ctx, id, fetcher)
        }
        return CreateTodoPayload(todo = todo!!)
    }

    @DgsMutation(field = "mutation_updateTodo")
    fun updateTodo(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoInput): UpdateTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = todoService.updateTodo(ctx, input)
        // 如果 client select 了 todo，回查
        val todo = if (success) {
            fetcherBuilder.buildFromField<Todo>(dfe.selectionSet, "todo")?.let { fetcher ->
                todoService.findById(ctx, input.id, fetcher)
            }
        } else null
        return UpdateTodoPayload(success = success, todo = todo)
    }

    @DgsMutation(field = "mutation_updateTodoItems")
    fun updateTodoItems(dfe: DgsDataFetchingEnvironment, @InputArgument input: UpdateTodoItemsMutationInput): UpdateTodoItemsPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        todoService.updateTodoItems(ctx, input)
        return UpdateTodoItemsPayload(success = true)
    }

    @DgsMutation(field = "mutation_deleteTodoById")
    fun deleteTodoById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): DeleteTodoPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val success = todoService.deleteTodo(ctx, id)
        return DeleteTodoPayload(success = success)
    }
}
```

---

## Service 层改造要点

### 之前

```kotlin
fun findTodoByCursor(ctx: OperationContext, input: CursorQueryInput): Page<TodoListDto>
fun getTodo(ctx: OperationContext, id: UUID): TodoDetailDto
```

### 之后

```kotlin
// 返回 Jimmer entity interface，形状由 fetcher 决定
fun findByCursor(ctx: OperationContext, input: TodoQueryInput, fetcher: Fetcher<Todo>): Page<Todo>
fun findById(ctx: OperationContext, id: UUID, fetcher: Fetcher<Todo>): Todo?

// Create 返回 ID（fetcher 层按需回查）
fun createTodo(ctx: OperationContext, input: CreateTodoInput): UUID

// Update — service 返回 boolean，fetcher 层按需回查
fun updateTodo(ctx: OperationContext, input: UpdateTodoInput): Boolean
fun updateTodoItems(ctx: OperationContext, input: UpdateTodoItemsMutationInput)
fun deleteTodo(ctx: OperationContext, id: UUID): Boolean
```

### Todo Entity 新增 `note` 字段

```kotlin
@Entity
@Table(name = "core_todo")
interface Todo : AppScopedProps, SoftDeletableProps {
    @Id val id: UUID
    override val appId: UUID
    val installId: UUID?
    val userId: UUID?
    val title: String
    val done: Boolean
    val note: String?         // ← NEW: nullable，用来演示 unset
    @Serialized val meta: Map<String, Any?>?
    @OneToMany(mappedBy = "todo") val items: List<TodoItem>
}
```

对应 Flyway migration:
```sql
-- V14__add_todo_note.sql
ALTER TABLE core_todo ADD COLUMN note TEXT;
```

---

## 删除清单

| 删除目标 | 路径 |
|----------|------|
| 全部 REST Controller | `bff/customer/` 整个目录 |
| `bff/app/AppConfigController.kt` | GraphQL 替代 |
| `EnvelopeResponseAdvice.kt` | GraphQL 不用 |
| `OpenApiConfig.kt` | 无 Swagger |
| `EnvelopeSchemaCustomizer.kt` | 同上 |
| `OperationContextArgumentResolver.kt` | 改用 `OperationContextProvider` |
| `springdoc-openapi` 依赖 | Gradle 移除 |
| Jimmer DTO 文件 | `src/main/dto/` 下 view/input DTO |
| 手写 DTO class | `modules/*/dto/` 中 REST 专用的请求/响应类 |
| Swagger 注解 | `@Schema`, `@Operation` (io.swagger) |

**保留**:
- `bff/webhooks/WebhookController.kt` — Apple/Google 回调
- `bff/wellknown/JwksController.kt` — JWKS
- `Envelope.kt` — 精简后留给 webhook

---

## 目录结构（最终态）

```
core-api/src/main/
├── kotlin/com/ifmix/api/core/
│   ├── CoreApplication.kt
│   ├── bff/
│   │   ├── graphql/
│   │   │   ├── customer/                  # customer endpoint 的 DataFetchers
│   │   │   │   ├── ScanFetcher.kt
│   │   │   │   ├── CollectionFetcher.kt
│   │   │   │   ├── AuthFetcher.kt
│   │   │   │   ├── TodoFetcher.kt
│   │   │   │   ├── StorageFetcher.kt
│   │   │   │   ├── FeedbackFetcher.kt
│   │   │   │   └── IapFetcher.kt
│   │   │   └── admin/                     # (future)
│   │   ├── webhooks/WebhookController.kt
│   │   └── wellknown/JwksController.kt
│   ├── modules/                           # 不变
│   ├── entity/                            # 不变（加 note 字段）
│   └── infra/
│       ├── graphql/
│       │   ├── OperationContextProvider.kt
│       │   ├── GraphQLExceptionHandler.kt
│       │   ├── FetcherBuilder.kt
│       │   ├── GraphQLEndpointConfig.kt
│       │   └── scalars/
│       │       ├── UuidScalar.kt
│       │       ├── DateTimeScalar.kt
│       │       └── JsonScalar.kt
│       └── ...
├── resources/
│   ├── schema/
│   │   ├── common/
│   │   │   └── common.graphqls
│   │   ├── customer/
│   │   │   ├── scan.graphqls
│   │   │   ├── collection.graphqls
│   │   │   ├── auth.graphqls
│   │   │   ├── todo.graphqls
│   │   │   ├── storage.graphqls
│   │   │   ├── feedback.graphqls
│   │   │   └── iap.graphqls
│   │   └── admin/
│   │       └── (future)
│   └── application.yml
└── dto/                                   # 删除或只留 Jimmer entity .dto（如果还有需要）
```

---

## Gradle 配置

```kotlin
plugins {
    // ... existing
    id("com.netflix.dgs.codegen") version "7.0.3"
}

dependencies {
    // 移除
    // implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // 新增
    implementation(platform("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:12.0.1"))
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter")
    testImplementation("com.netflix.graphql.dgs:graphql-dgs-client")
}

tasks.withType<com.netflix.graphql.dgs.codegen.gradle.GenerateJavaTask> {
    packageName = "com.ifmix.api.core.generated"
    language = "kotlin"
    generateClient = true
    generateDataTypes = true
    typeMapping = mutableMapOf(
        "UUID" to "java.util.UUID",
        "DateTime" to "java.time.Instant",
        "Long" to "kotlin.Long",
        "JSON" to "kotlin.Any",
    )
}
```

---

## 执行步骤

| # | 内容 | 依赖 |
|---|------|------|
| 1 | 添加 DGS 依赖 + codegen plugin，编译通过 | — |
| 2 | 写 `schema/common/common.graphqls` | 1 |
| 3 | 实现 custom scalars (UUID/DateTime/JSON) | 1 |
| 4 | 实现 `OperationContextProvider` + `GraphQLExceptionHandler` + `FetcherBuilder` | 1 |
| 5 | 配置 endpoint `/customer/graphql` + WebConfig 拦截器 | 4 |
| 6 | Todo entity 加 `note` 字段 + Flyway V14 | — |
| 7 | 迁移 Todo 模块（schema + fetcher + service 改造，含 updateItems 拆分 + unset 演示） | 2-6 |
| 8 | 迁移 Scan 模块 | 2-5 |
| 9 | 迁移 Auth 模块 | 2-5 |
| 10 | 迁移 Collection / Storage / Feedback / IAP | 2-5 |
| 11 | 删除旧 REST controller + Envelope + OpenAPI + Jimmer DTO | 7-10 |
| 12 | 重写 E2E 测试（DGS Client + /customer/graphql） | 11 |
| 13 | 更新 ARCHITECTURE.md + AGENTS.md | 12 |

---

## 开放问题

1. **`ScanRecord.result`** — 用 `scalar JSON` 透传，还是定义 GraphQL type？→ 建议先 `JSON`，模型输出格式稳定后再 type 化。
2. **DGS 多 endpoint** — DGS 原生不直接支持多 GraphQL endpoint。方案：用两个 Spring GraphQL `HttpGraphQlController` bean 分别指向不同 schema registry。或者初期只做 `/customer/graphql`，admin 用独立 module/进程。
3. **N+1** — `ScanCollectionItem.scanRecord` 需要 DataLoader。Phase 2 优化。
4. **`@ConditionalOnBean`** — DGS DataFetcher 用 `@ConditionalOnBean(AntiqueService::class)` 可以条件加载。
