# RPC 试点实现计划 — 服务端（ifmix_server）：RPC 基础设施 + demo 模块

> **给接手的 agent**：按顺序读 `AGENTS.md` → `docs/design/proposals/graphql-to-http-rpc-openapi.md` §一（协议定稿，唯一真相源）→ `docs/design/infra/wire-encryption.md` §10 → 本文件。契约冲突时以 proposal §一 与 wire §10 为准并回改本文件。逐任务更新「状态」，不要重做已完成任务。
> - 仓库：`/Users/jason/ai/myprojects/ifmix_server`；分支 `feature/graphql-to-rpc`（自 main 切出；**若已建 feature/rpc-pilot，直接 `git branch -m feature/graphql-to-rpc`**）。
> - 客户端配套见 `rpc-pilot-client.md`（antique 仓库，不归你改）。**客户端 wire v3 已完成并合入 master**；其头部用 `x-wirep-version`（客户端已按本定稿改名）。
> - **硬性边界：本试点只做基础设施 + demo 模块。其余模块（media/cs/install/customer/auth/pay/ai）一律不动；GraphQL 全量保留不删（双轨并存，联调通过后另行删除）。**

## 0. 一页纸摘要

为 `POST /api/customer/core/{actionName}` 建立协议无关的 RPC 基础设施（ActionSpec / RequestMeta / ActionContextFactory），迁移 demo 的 8 个 action。请求信封 `{meta, input}`、响应 `Envelope{code, msg, data}`、HTTP status = code 前三位。**`ActionContextFactory` 自包含**：直接消费 `meta.accessToken`（`AuthJwtService.verify`），不与 GraphQL 解析路径（RequestParser/ActionContextProvider）共享任何包装或适配代码。业务层（DemoFacade/Handler/Repository）**零改动**，DTO 转换全部在 BFF 层（generated types → 协议 DTO 的解耦留给阶段 2）。

## 0.1 协议契约速查（与 proposal §一 一致，实现时逐条对齐）

```
URL:      POST /api/customer/core/{actionName}
          actionName = {q|m}_{module}_{resource}_{action}，如 q_demo_todo_getById（命名规范见 proposal §一）
请求体:   {"meta": {...RequestMeta...}, "input": {...该 action 的输入对象...}}
          wire v3 加密时 body 是 octet-stream，WireCryptoFilter 解密后 controller 看到明文 JSON
响应体:   {"code": "200000", "msg": "success", "data": {...}}
          HTTP status = code 前三位（200000→200、429000→429…），成功固定 200000/200
header 留守: Content-Type / x-wirep-version: 3（加密时）/ User-Agent / CF 注入头 / x-req-id（客户端同时发 header 与 meta.reqId，同值——日志与错误响应回显依赖 header 通道）
凭证:     meta.accessToken = 纯 token 字符串（无 Bearer 前缀）；ActionContextFactory 直接 jwt.verify，
          不经过 header、不做虚拟 header 包装
错误:     解密失败 → 明文 {"code":"400003",...} + HTTP 400（wire filter 已有）；业务错误 → ApiError → GlobalExceptionHandler → Envelope
429 类:   ApiError.retryAfterSec 非空时 GlobalExceptionHandler 输出 Retry-After 响应头（客户端信封路径已会读该头）
```

## 1. 并行执行计划（多 subagent 加速）

工作包（WP）按**文件所有权**划分——不同 WP 的文件集零交集，可安全并行；同 WP 内任务串行。

| WP | 内容 | 独占文件集 | 依赖 | 并行性 |
|----|------|-----------|------|--------|
| **S1** | wire v3 服务端 + 版本头改名 | `WireCrypto*`、`RequestHeaders.kt`、wire 相关测试、`wire-v3-plan-server.md`、wire 两篇文档 | 无 | Wave 1 |
| **S2** | RPC 基础设施 | `infra/http/ActionSpec.kt`、`RequestMeta.kt`、`RpcRequestBody.kt`、`ActionContextFactory.kt`（新建，自包含）、`ActionContext.kt`（追加 3 字段）、`GlobalExceptionHandler.kt`（追加 Retry-After）、各自测试 | 无 | Wave 1 |
| **S3** | demo 协议 DTO + mapper | `dto/demo/**`（新建）、`dto/common/FindOptionsDto.kt`（新建）、`dto/common/FilterGroupDto.kt`（新建） | 无 | Wave 1 |
| **S4** | DemoQueryService + DemoController | `bff/api/customer/demo/**` | S2 + S3 | Wave 2 |
| **S5** | 合约/单测补全 + 全量回归 | `src/test/**`（S2/S4 未覆盖的 controller 级测试） | S4 | Wave 3 |
| **S6** | 联调 E2E + 差异对比 + 停下等 review | 文档回写 | 全部 | Wave 4（串行） |

**并行方式**：同一工作目录只能串行；要真并行，每个 subagent 用独立 `git worktree`（`git worktree add ../ifmix_s1 feature/graphql-to-rpc-s1` 之类），按 WP 提交，按 Wave 顺序 rebase 合并回 `feature/graphql-to-rpc`。合并后跑一次全量 `./gradlew :core-api:compileKotlin :core-api:test`。**每个 WP 的 agent 严禁改动所有权表之外的文件**（含"顺手格式化"）；发现需要改他人文件时，在 WP 报告里列出待办，由 S6 统一处理。

以下每个 WP 一节：目标、逐文件规格（含精确签名与行为）、验收。

---

## 2. WP-S1：wire v3 服务端 + 版本头改名

**执行依据**：`docs/design/infra/wire-v3-plan-server.md`（任务清单与验收以它为准，全部执行）。本节只补充改名相关决策：

1. `RequestHeaders.PROTO_VERSION` 的值改为 `x-wirep-version`（常量名可保留 PROTO_VERSION 或改 WIREP_VERSION，二选一后全仓统一）。
2. `WireCryptoFilter` 按 `x-wirep-version: 3` 识别加密请求；**不保留旧名/旧 v2 解析路径**（v2 从未上线）。
3. 同步 `wire-encryption.md` 与 `wire-v3-plan-server.md` 内所有 `x-proto-version` 引用 → `x-wirep-version`；`wire-encryption.md` §9 v2.1 小节改写为：「由 RPC 协议承接，meta 定义见 proposals/graphql-to-http-rpc-openapi.md §一」。
4. 客户端已实现 `x-wirep-version`（若联调发现客户端发的是旧名，属客户端分支未同步，报告用户，**不要**做服务端兼容）。

**验收**：wire 全部测试（向量、gzip 阈值、多 kid、400003、降级）通过；全仓 grep `x-proto-version` 仅剩历史章节说明。

---

## 3. WP-S2：RPC 基础设施（`infra/http/`）

### 3.1 新建 `RequestMeta.kt`

```kotlin
package com.ifmix.core.api.infra.http

@JsonIgnoreProperties(ignoreUnknown = true)   // 未知字段忽略，向前兼容
data class RequestMeta(
    val reqId: String? = null,
    val projectId: String? = null,
    val accessToken: String? = null,          // 纯 token，无 Bearer 前缀
    val locale: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val userTz: String? = null,               // IANA 时区名，可选
    val appVersion: String? = null,
    val otaVersion: String? = null,
    val clientPlatform: String? = null,       // android | ios | web
    val deviceModel: String? = null,
    val osVersion: String? = null,
)
```

### 3.2 新建 `RpcRequestBody.kt`

```kotlin
data class RpcRequestBody(
    val meta: RequestMeta?,          // 缺失视为全空 meta（容错：明文 curl 调试）
    val input: JsonNode? = null,     // 各 controller 用 objectMapper.convertValue 转具体输入
)
```

### 3.3 兼容性决策（2026-10-06 修订，覆盖早先版本）

**不做任何与 GraphQL 解析路径的兼容或共享**：不建 `MetaHeaderRequestWrapper` 之类的虚拟 header 适配层，不改造 `RequestParser`。`ActionContextFactory` 自包含——token 校验逻辑从 `RequestParser.parseActor`（infra/auth/RequestParser.kt:78-125）**复制出来改造**，直接吃 `RequestMeta`。被复制的重复逻辑（token 校验、格式正则）在阶段 7 删 GraphQL 时随 `RequestParser` 一并收敛，现在不做任何为并存而设的桥接。GraphQL 路径（`RequestParser`/`ActionContextProvider`）保持原样运行，S2 **不许改动这两个文件**。

### 3.4 新建 `ActionSpec.kt`

```kotlin
package com.ifmix.core.api.infra.http

/** demo 8 个 action 全部为 CUSTOMER；另两档为后续模块预留，本试点不使用。 */
enum class ActorRequirement { NONE, INSTALL_OR_CUSTOMER, CUSTOMER }

data class ActionSpec(
    val reqName: String,
    val isMutation: Boolean,
    val actor: ActorRequirement = ActorRequirement.CUSTOMER,
    val requireProjectId: Boolean = true,
)
```

语义由 `ActionContextFactory` 按下表直接解释（不映射到 RequestParser 的任何参数）：

| ActorRequirement | 无 token | token 为 install(type=5) | token 为 customer/manager |
|---|---|---|---|
| `NONE` | 放行（actor=null） | 校验通过后放行（actor=null，记录 tokenInstallId/tokenType） | 校验通过后放行 |
| `INSTALL_OR_CUSTOMER` | 放行（actor=null） | 同上 | 同上（actor 按 token） |
| `CUSTOMER` | 抛 `UNAUTHORIZED("authentication required")` | 抛 `UNAUTHORIZED("customer authentication required")` | 必须有合法 actor，否则 UNAUTHORIZED；manager token → `FORBIDDEN` |

### 3.5 新建 `ActionContextFactory.kt`（自包含，唯一解析入口）

```kotlin
package com.ifmix.core.api.infra.http

@Component
class ActionContextFactory(
    private val jwt: AuthJwtService,   // com.ifmix.core.api.infra.auth
    @param:Value("\${app.header-validation.strict:true}")
    private val strict: Boolean = true,
) {
    /** 从 RPC 请求构造 ActionContext。meta 缺失时按空 RequestMeta 处理（明文 curl 调试场景）。 */
    fun fromApi(request: HttpServletRequest, spec: ActionSpec, meta: RequestMeta?): ActionContext
}
```

实现规则（逐条，校验失败即抛 `ApiError`，与 GraphQL 路径语义对齐）：

1. **token 校验**（自含实现，来源 RequestParser.parseActor:81-96）：`meta.accessToken` 为空 → 按 §3.4 表处理；非空 → `jwt.verify(meta.accessToken)`，`TokenExpiredException` → `ApiError(TOKEN_EXPIRED, "access token expired")`；返回 null → `ApiError(UNAUTHORIZED, "invalid token: signature verification failed")`。**不要求/不剥离 Bearer 前缀**（meta 里就是纯 token；若带了 `"Bearer "` 前缀直接 `UNAUTHORIZED, "invalid token: send raw token in meta.accessToken"`——协议教育优于静默容忍）。
2. **aud 校验**（parseActor:98-102 同语义）：`verified.projectId != null && meta.projectId != null && 两者不等` → `UNAUTHORIZED("invalid token: app mismatch")`。
3. **actor 构造**：install token（`AuthJwtService.TOKEN_TYPE_INSTALL`）→ actor=null，`tokenInstallId = verified.installId?.let(tryUuid)`、`tokenType = 5`；其余 token → `actorId = verified.actorId?.let(tryUuid) ?: 抛 UNAUTHORIZED("invalid token: missing or invalid subject")`，构造 `Actor(actorId, actorType, anonymous, sessionId)`（`Actor` 是 RequestParser.kt:16-22 的公开 data class，直接 import 复用——纯数据类不算兼容层）。按 §3.4 表裁决放行/拒绝。
4. **projectId**：`meta.projectId` blank 且 `spec.requireProjectId` → `ApiError(INVALID_REQUEST, "projectId is required")`；非空则须匹配 `PROJECT_ID_RE`（正则从 RequestParser companion 复制：`^[a-z][a-z0-9-]{2,29}$`）→ 不匹配抛 `INVALID_REQUEST("invalid projectId format")`。
5. **locale**：`meta.locale?.takeIf { it.isNotBlank() }?.let { RequestParser.normalizeLocale(it) }`（companion 公开纯函数，直接调用不算兼容层；合法但不在支持集 → null，不做 strict 抛错——线上语义）。
6. **currency / country**：uppercase 后分别匹配 `^[A-Z]{3}$` / `^[A-Z]{2}$`（正则同样复制进 factory companion）；不匹配：strict=true 抛 `INVALID_REQUEST`，否则 WARN + null（沿用 `app.header-validation.strict` 配置语义）。
7. **clientPlatform**：`meta.clientPlatform` 非空 → `ClientPlatform.fromHeader(it)`，`IllegalArgumentException` → strict 抛 `INVALID_REQUEST` / 否则 null。
8. **clientIp / botScore**：取自**真实 request**（`ClientIpResolver.resolve(request)`、`request.getHeader(RequestHeaders.CF_BOT_SCORE)` 按现有 parseBotScore 规则 1..99）——这两个是边缘注入信号，永不走 meta。
9. **requestId**：`meta.reqId?.takeIf { it.isNotBlank() } ?: LogContext.requestId(request)`（客户端 header 与 meta 同值，两者任一即可）。
10. **meta 独有三字段**：`userTz` / `deviceModel` / `osVersion` = 对应 meta 值 `trim()?.takeIf { it.isNotEmpty() }`，无任何格式校验。
11. **固定值**：`installId = null`、`legacyInstallId = null`（RPC 协议没有这两个不可信信源）、`actionName = spec.reqName`、`isMutation = spec.isMutation`、`preferReader = !spec.isMutation`。
12. **收尾**（与 ActionContextProvider.fromDfe:78-79 相同的两行）：`ActionContextHolder.set(ctx)` + `LogContext.bind(ctx, request)`。

### 3.6 修改 `ActionContext.kt`（追加，不改既有字段）

追加 3 个可空字段：`val userTz: String? = null`、`val deviceModel: String? = null`、`val osVersion: String? = null`；`logFields()` 追加 `"tz" to userTz, "dm" to deviceModel, "os" to osVersion`。既有字段与用法一律不动。

### 3.7 修改 `GlobalExceptionHandler.kt`（追加 2 行）

`handleApiError` 中 `val resp = ResponseEntity.status(code.status)` 之后、return 之前追加：

```kotlin
ex.retryAfterSec?.let { resp.header("Retry-After", it.toString()) }
```

（客户端信封路径已有 `retryAfterFrom(res)` 读该头；AI_UNAVAILABLE 的固定 Retry-After: 60 逻辑保持不变。）

### 3.8 日志纪律

- 解密后的 body **不落日志**（`RequestLoggingFilter` 只打 header/状态，现状即满足；确认无 body 输出即可）。
- `meta.accessToken` 出现在任何日志/异常 message 里都算 bug：RequestParser 现有报错信息不含 token 原文，保持。

### 3.9 S2 单元测试（新建 `src/test/kotlin/com/ifmix/core/api/infra/http/api/`）

参照现有 `WireCryptoTest` / `LogContextTest` 的纯单测风格（MockHttpServletRequest + Mockito，不起 Spring context）：

1. `ActionContextFactoryTest`（mock `AuthJwtService`）：
   - CUSTOMER 端点无 token → `UNAUTHORIZED("authentication required")`；install token → `UNAUTHORIZED("customer authentication required")`；manager token → `FORBIDDEN`。
   - token 过期 → `TOKEN_EXPIRED`；验签失败 → `UNAUTHORIZED`；aud 与 meta.projectId 不一致 → `UNAUTHORIZED("invalid token: app mismatch")`。
   - `meta.accessToken` 带 `"Bearer "` 前缀 → `UNAUTHORIZED`（协议教育用例）。
   - install token 合法 → actor=null 且 tokenInstallId/tokenType 正确进 ctx。
   - projectId 缺失（requireProjectId=true）→ `INVALID_REQUEST`；格式非法 → `INVALID_REQUEST`。
   - locale/currency/country 软校验：strict=true 时 currency 非法抛、locale 不支持集返回 null。
   - userTz/deviceModel/osVersion 进 ctx；installId/legacyInstallId 恒 null；actionName/isMutation/preferReader 正确。
   - requestId：meta.reqId 优先；缺失时回落 header `x-req-id`（用 LogContext 现有行为）。
2. `GlobalExceptionHandlerRetryTest`：retryAfterSec=120 的 ApiError → 响应含 `Retry-After: 120`；无 retryAfterSec 的错误不含该头。

**S2 验收**：`./gradlew :core-api:compileKotlin :core-api:test` 全绿；不改任何 S1/S3 文件。

---

## 4. WP-S3：demo 协议 DTO + mapper（全部新建）

### 4.1 响应视图 DTO — `dto/demo/TodoViews.kt`

固定 DTO（不做 include DSL；所有返回 Todo 的 action 都返回完整视图，与客户端 GraphQL `TODO_FIELDS` 选择集对齐）：

```kotlin
package com.ifmix.core.api.dto.demo

data class TodoDto(
    val id: java.util.UUID,
    val title: String,
    val done: Boolean,
    val note: String?,
    val meta: Map<String, Any?>?,
    val recommend: TodoRecommendDto?,
    val items: List<TodoItemDto>,
    val itemCount: Int,
    val pendingCount: Int,
    val finishCount: Int,
    val createdAt: String,        // Instant.toString()，ISO-8601 UTC
    val updatedAt: String?,
)
data class TodoItemDto(
    val id: java.util.UUID, val content: String, val done: Boolean,
    val note: String?, val createdAt: String, val updatedAt: String?,
)
data class TodoRecommendDto(
    val sectionId: java.util.UUID, val sectionName: String, val viewCount: Int?,
    val recItems: List<TodoRecItemDto>?,
)
data class TodoRecItemDto(
    val recId: java.util.UUID, val title: String?, val priority: Int,
    val createdAt: String, val updatedAt: String?,
)
data class CreateTodoResultDto(val todo: TodoDto)
data class UpdateTodoResultDto(val success: Boolean, val todo: TodoDto?)
data class UpdateTodoItemsResultDto(val success: Boolean)
```

`ActionResult` 与 `PageInfo` 复用现有 `com.ifmix.core.api.dto.common.ActionResult` / `PageInfo`（schema 形状一致，勿新建）。分页容器复用现有 `Page<T>`（其 items 是 TodoDto，pageInfo 字段名 nextCursor/hasMore 与客户端一致）。

### 4.2 查询输入 DTO — `dto/demo/TodoInputs.kt`

与 GraphQL argument 名一一对应（客户端 variables 原样搬进 input）：

```kotlin
data class FindTodoByIdInput(val id: java.util.UUID)
data class FindTodosByIdsInput(val ids: List<java.util.UUID>)
data class FindTodosInput(val findOptions: com.ifmix.core.api.dto.common.CommonFindOptions?)
data class CreateTodoInputDto(
    val title: String, val done: Boolean? = null, val note: String? = null,
    val recommend: TodoRecommendInputDto? = null, val items: List<CreateTodoItemInputDto>? = null,
)
data class CreateTodoItemInputDto(val content: String, val done: Boolean? = null, val note: String? = null)
data class TodoRecommendInputDto(
    val sectionId: java.util.UUID, val sectionName: String, val viewCount: Int? = null,
    val recItems: List<TodoRecItemInputDto>? = null,
)
/** 客户端不提交 createdAt/updatedAt（服务端生成字段；客户端 rpcDemo.ts 用 Omit 对齐）。
 *  注意与 GraphQL input 的差异：GraphQL 的 TodoRecItemInput.createdAt 是必填（老客户端自己造时间戳），
 *  RPC 契约改为服务端 mapper 统一打戳，见 §4.4。 */
data class TodoRecItemInputDto(
    val recId: java.util.UUID, val title: String? = null, val priority: Int,
)
data class UpdateTodoInputDto(val id: java.util.UUID, val set: UpdateTodoSetInputDto?, val unset: List<String>? = null)
data class UpdateTodoSetInputDto(val title: String? = null, val done: Boolean? = null, val note: String? = null, val recommend: TodoRecommendInputDto? = null)
data class UpdateTodoItemsMutationInputDto(
    val create: List<CreateTodoItemForTodoInputDto>? = null,
    val update: List<UpdateTodoItemInputDto>? = null,
    val delete: List<java.util.UUID>? = null,
)
data class CreateTodoItemForTodoInputDto(val todoId: java.util.UUID, val content: String, val done: Boolean? = null, val note: String? = null)
data class UpdateTodoItemInputDto(val id: java.util.UUID, val set: UpdateTodoItemSetInputDto?, val unset: List<String>? = null)
data class UpdateTodoItemSetInputDto(val content: String? = null, val done: Boolean? = null, val note: String? = null)
```

注意：unset 用 `List<String>`（值 "NOTE"/"RECOMMEND"），不做枚举——服务端映射时按字符串比较，客户端少一类码生成。

### 4.3 通用查询 DTO — `dto/common/FindOptionsDto.kt` + `dto/common/FilterGroupDto.kt`

```kotlin
package com.ifmix.core.api.dto.common

// 与 schema/common/common.graphqls 的 CommonFindOptions 字段一一对应
data class CommonFindOptions(
    val filter: FilterGroup? = null,
    val cursor: String? = null,
    val sortBy: String? = null,
    val sortDirection: String? = null,   // "ASC" | "DESC"
    val limit: Int? = null,
)

// 与 schema/common/filter.graphqls 一一对应
data class FilterGroup(val and: List<FilterExpr>? = null, val or: List<FilterExpr>? = null)
data class FilterExpr(val field: FieldFilter? = null, val group: FilterGroup? = null)
data class FieldFilter(
    val field: String, val op: String,
    val value: Any? = null,            // JSON 标量
    val values: List<Any>? = null,     // JSON 数组
)
```

op 取值集合（校验用）：`EQ, NE, GT, GTE, LT, LTE, IN, NIN, LIKE, IS_NULL, IS_NOT_NULL`。sortDirection 合法值 `ASC`/`DESC`（大小写敏感），非法值在 mapper 抛 `ApiError(INVALID_REQUEST)`。

### 4.4 mapper — `dto/demo/DemoRpcMappers.kt`（纯 object，无 Spring 依赖）

两类映射函数，全部显式手写：

1. **DTO → generated**（入参侧，供 controller 调现有 Facade）：`toGenerated(CreateTodoInputDto): com.ifmix.core.api.generated.types.CreateTodoInput`、`toGenerated(UpdateTodoInputDto)`（unset 字符串转 `TodoUnsetField`，非法值抛 `ApiError(INVALID_REQUEST, "invalid unset field: xxx")`）、`toGenerated(UpdateTodoItemsMutationInputDto)`、`toGenerated(CommonFindOptions)`（dto/common → generated，含 FilterGroup 递归与 FilterOp 字符串转枚举，非法 op 抛 INVALID_REQUEST）、`recommendToDomain(TodoRecommendInputDto): TodoRecommend`（复用 generated types 里已有的 `toDomain()` 语义——参考 `TodoAggHandler` 现有 import）。**recItem 打戳规则**：`TodoRecItemInputDto` 无 createdAt/updatedAt 字段（客户端不提交，见 §4.2），mapper 转 generated `TodoRecItemInput` 时 `createdAt = Instant.now().toString()`、`updatedAt = null`——generated input 的 createdAt 是非空 `DateTime!`，不能透传 null。
2. **entity → DTO**（出参侧）：`toDto(Todo, items, counts): TodoDto`、`toDto(TodoItem): TodoItemDto`、`recommendToDto(TodoRecommend): TodoRecommendDto`、`recItemToDto(...)`。DateTime 全部 `Instant.toString()`。

**S3 验收**：纯 Kotlin 单测 `dto/demo/DemoRpcMappersTest`（非法 unset/op/sortDirection 抛 INVALID_REQUEST；TodoRecommend 往返字段一致）通过；不 import Spring/DGS；不修改任何既有文件。

---

## 5. WP-S4：DemoQueryService + DemoController（`bff/api/customer/demo/`）

### 5.1 `DemoQueryService.kt`

```kotlin
@Service
class DemoQueryService(private val facade: DemoFacade)   // 只调 Facade，禁 import repo/handler
```

方法与聚合策略（禁循环 findById；批量结果必须按输入 ID 顺序排列——`findByIds`/`findByTodoIds` 返回后**按入参顺序重排**）：

```kotlin
/** 单条：todo + 该条 items + counts（2 次查询：items + counts） */
fun findTodoById(ctx: ActionContext, id: UUID): TodoDto?
/** 批量：1 次 findByIds + 1 次 items + 1 次 counts，按 ids 顺序组装；缺 counts 的 todo 填 0 */
fun findTodosByIds(ctx: ActionContext, ids: List<UUID>): List<TodoDto>
/** 分页：1 次 findByOptions（拿 Page<Todo>）+ 1 次 items + 1 次 counts */
fun findTodos(ctx: ActionContext, findOptions: dto.common.CommonFindOptions?): Page<TodoDto>
```

内部组装：`val ids = todos.map{it.id}` → `facade.findItemsByTodoIds(ctx, ids).groupBy { it.todoId }` → `facade.countItemsByTodoIds(ctx, ids)`（返回 `Map<UUID, TodoItemCounts>`，缺 key = 0，对齐 `TodoItemCountsDataLoader.ZERO` 语义）→ `DemoRpcMappers.toDto(todo, items, counts)`。**TodoDto 永远带全 items+counts**（固定 DTO 决策）。注意：`countItemsByTodoIds` 的 Facade 方法返回 `Map<UUID, TodoItemCounts>`（见 DemoFacade.kt:34-35 签名，直接用）。

### 5.2 `DemoController.kt`

```kotlin
@RestController
@RequestMapping("/api/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
class DemoController(
    private val ctxFactory: ActionContextFactory,
    private val facade: DemoFacade,
    private val queryService: DemoQueryService,
    private val globalTx: GlobalTxRunner,
    private val objectMapper: ObjectMapper,
)
```

8 个 endpoint，全部 `@PostMapping("/{actionName}", consumes = [MediaType.APPLICATION_JSON_VALUE])`，方法体统一模式：

```kotlin
val ctx = ctxFactory.fromApi(request, Specs.XXX, body.meta)
val input = objectMapper.convertValue(body.input ?: EmptyNode.instance, FindTodoByIdInput::class.java)
// mutation: globalTx.withTx(ctx) { txCtx -> facade.xxx(txCtx, DemoRpcMappers.toGenerated(input)) }
// query:    直接调 queryService / facade
return ResponseEntity.ok(Envelope.ok(resultDto))
```

逐 endpoint 规格（actionName → spec → 行为；语义与 DemoFetcher.kt 逐行对齐；actionName 按四段命名规范 `{q|m}_{module}_{resource}_{action}`）：

| actionName | ActionSpec | 实现 |
|---|---|---|
| `q_demo_todo_getById` | query/CUSTOMER | `queryService.findTodoById(ctx, input.id)`；null → 抛 `ApiError(NOT_FOUND, "Todo not found: ${input.id}")`（对齐 DemoFetcher.kt:26 的 IllegalArgumentException 语义，但改用 NOT_FOUND 使 HTTP 404） |
| `q_demo_todo_getByIds` | query/CUSTOMER | `queryService.findTodosByIds(ctx, input.ids)` → `Envelope.ok(List<TodoDto>)` |
| `q_demo_todo_list` | query/CUSTOMER | `queryService.findTodos(ctx, input.findOptions)` → `Envelope.ok(Page<TodoDto>)` |
| `m_demo_todo_createOne` | mutation/CUSTOMER | `globalTx.withTx(ctx) { facade.create(it, DemoRpcMappers.toGenerated(input)) }` → `CreateTodoResultDto(todo = queryService 组装的完整视图)`（复用 findTodosByIds 的组装逻辑，只传单元素列表） |
| `m_demo_todo_updateOne` | mutation/CUSTOMER | `globalTx.withTx(ctx) { facade.partialUpdate(it, toGenerated(input)) }`；然后**用原 ctx（非 txCtx）** `queryService.findTodoById(ctx, input.id)`（对齐 DemoFetcher.kt:57 写后读）→ `UpdateTodoResultDto(success = true, todo = ...)` |
| `m_demo_todo_updateItems` | mutation/CUSTOMER | `globalTx.withTx(ctx) { facade.batchUpdateItems(it, toGenerated(input)) }` → `UpdateTodoItemsResultDto(success = true)` |
| `m_demo_todo_deleteOne` | mutation/CUSTOMER | `globalTx.withTx(ctx) { facade.deleteById(it, input.id) }` → `ActionResult(success = true)` |
| `m_demo_todo_deleteMany` | mutation/CUSTOMER | `globalTx.withTx(ctx) { facade.deleteByIds(it, input.ids) }` → `ActionResult(success = true, modifiedCount = count)` |

Spec 常量集中定义为 `DemoSpecs` object（8 个 `ActionSpec`，actionName 与上表一致，全部 CUSTOMER + requireProjectId=true）。

### 5.3 S4 单元测试（`src/test/kotlin/com/ifmix/core/api/bff/api/customer/demo/`）

仿 `InstallFetcherAttestTest` 的 Mockito 风格（mock facade/globalTx 直通；ctxFactory 用真实实例 + MockHttpServletRequest，JWT mock）：

1. 每个 endpoint 一条成功用例：URL/方法正确、返回 Envelope 形状、mutation 包了 withTx、query 不包。
2. `findTodoById` 未命中 → HTTP 404 + `{"code":"404000",...}`。
3. 分页聚合：mock facade 三类返回，断言 TodoDto 组装顺序与 ids 输入一致、缺 counts 补 0。
4. 转换边界：非法 unset 字符串 / 非法 FilterOp → HTTP 400 + `400000`。

**S4 验收**：`./gradlew :core-api:compileKotlin :core-api:test` 全绿；`bff/graphql` 既有测试全绿（GraphQL 路径未被触碰的证明）。

---

## 6. WP-S5：合约测试补全 + 回归

1. 补 controller 级「HTTP status = code 前三位」矩阵测试：401000/401002/403000/404000/429000(带 Retry-After)/500000 各一例（mock 抛 ApiError 即可，不必真走业务）。
2. **命名一致性测试（proposal §一 护栏，新 URL 规则配套）**：反射扫描 `DemoSpecs` 全部 ActionSpec 与 `@PostMapping` 路由——path 以 `m_` 开头 ⇔ `isMutation=true`；module 段 = `demo`；resource 段 = `todo`；action 动词在标准动词表内（getById/getByIds/list/createOne/updateOne/updateItems/deleteOne/deleteMany）。
3. 加密路径用例：mockMvc 或 wrapper 级验证 `x-wirep-version: 3` + octet-stream 请求 → controller 收到明文（复用 wire 固定向量）。
4. 全量回归：`:core-api:test` 全绿 + 既有 GraphQL fetcher 测试全绿。

## 7. WP-S6：联调 E2E + 收尾（串行，最后执行）

1. 本地 `bootRun`（PG + Redis；`application-local.yml` 已有 kid=1 开发 key：`app.wire-crypto.keys`）。用 curl/脚本按契约速查打 8 个 action：明文 5 例 + 加密 2 例 + 429 伪造头 1 例。
2. 与客户端 agent 联调（antique 仓库 client-sdk）：8 个 action 明文 + 加密走通；对比同用例 GraphQL 与 RPC 返回，输出差异表（预期零差异）。
3. 回写文档：proposal 状态行标注「demo 试点已实施」；发现契约偏差回改 §一 并通知客户端文档。
4. **停下等 review**：输出试点报告（改动文件清单、测试结果、差异表、wire 指标），用户确认前不得迁移其他模块、不得删 GraphQL。

## 8. 明确不做

- 不动 `modules/demo/**`、`infra/repo/CrudRepoTemplate.kt`、`infra/repo/FilterGroupResolver.kt`（generated 解耦是阶段 2 的事，试点用 BFF mapper 过渡）。
- **不改 `infra/auth/RequestParser.kt` 与 `infra/graphql/ActionContextProvider.kt`**（GraphQL 路径原样运行；RPC 不与之共享包装/适配代码，重复逻辑阶段 7 收敛）。
- 不删 `bff/graphql/**`、DGS 依赖、trusted documents；不动其他模块的任何文件。
- 不建通用幂等、不引 Konvert、不改 ORM 关联、不做 OpenAPI 全量契约（springdoc 注解仅顺手加在 DemoController 上）。
