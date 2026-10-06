# RPC Controller/Mapper 重构实施单（2026-10-06 定稿，交实施 agent 执行）

> **给接手的 agent**：本文是可直接执行的实施单，设计依据与取舍见 `rpc-rollout-server.md` §3.1–3.3（真相源）与 `graphql-to-http-rpc-openapi.md` §一。工作目录为本仓（`feature/graphql-to-rpc` 分支）。按「改动 1 → 2 → 3 → 4」顺序逐项提交，每项一个 commit（前缀 `[rpc][refactor]`），每项完成必须：`:core-api:compileKotlin` + `:core-api:test` 全绿后再提交。除本文列出的文件外**不改任何其他文件**；发现现实与本文冲突（如 auth 合并已改动相关代码），停下写差异报告，不要自行迁就。

## 背景（一段话）

试点 controller 目前每个 endpoint 是 `@RequestBody body: ApiRequestBody` + `ctxFactory.fromRpc(request, XxxSpecs.XXX, body.meta)` + `input(body, X::class.java)` 手工转类型的旧模式（已在上一轮泛型化中删掉 helper，签名已是 `ApiRequestBody<XxxInput>`）。本轮把剩余的样板收口：ActionSpec 撤销、actionName 常量上 controller、fromRpc 改签名、Envelope.ok 加 reqId 参数、mapper 换形态、限流收口。

## 改动 1：ActionSpec 撤销，actionName 常量上 controller

### 1.1 controller 侧

每模块 controller（8 个：demo/media/cs/pay/customer/auth/install/ai）加 companion object，常量名沿用现 `XxxSpecs` 里的命名（便于机械搬移）：

```kotlin
class AiController(/* 依赖不变 */) {
    companion object {
        const val SCAN_GET_BY_ID = "q_ai_scan_getById"
        const val SCAN_LIST = "q_ai_scan_list"
        // ... 该模块全部 action
    }

    @Operation(operationId = SCAN_GET_BY_ID)                  // operationId 引用同一常量
    @PostMapping(SCAN_GET_BY_ID, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun findScanById(request: HttpServletRequest, @RequestBody body: ApiRequestBody<FindScanByIdInput>): ResponseEntity<Envelope<ScanRecordRes>> {
        val ctx = ctxFactory.fromRpc(request, SCAN_GET_BY_ID, isMutation = false, body = body)
        ...
    }
}
```

- Kotlin companion `const val` 可直接用于注解，三处（@PostMapping / @Operation / fromRpc）编译期同源。
- 现在显式写 `isMutation = false/true`（对应原 Spec 的 `isMutation`），与常量物理同段可见。

### 1.2 fromRpc 新签名（ActionContextFactory）

```kotlin
fun fromRpc(
    request: HttpServletRequest,
    actionName: String,
    isMutation: Boolean? = null,
    body: ApiRequestBody<*>,                          // body 整体透传，factory 自取 meta
    requireActorType: ActorRequirement = ActorRequirement.CUSTOMER,   // 原 spec.actor 改名
    requireProjectId: Boolean = true,
): ActionContext
```

行为要求：

1. `isMutation == null` 时由 `actionName.startsWith("m_")` 兜底推导；**显式传参时校验一致性**：`(actionName.startsWith("m_")) != isMutation` → 抛 `IllegalStateException`（运行时锁死「前缀 ⇔ 读写」关系，取代原反射一致性测试的这条断言）。
2. 原 `spec.actor` → `requireActorType`，裁决逻辑逐行不变；原 `spec.requireProjectId` → `requireProjectId`，逻辑不变。
3. **body 透传**：factory 从 `body.meta` 取 meta（`?: RequestMeta()` 容错同现状）。
4. `ActionContext` 增加字段 `val meta: RequestMeta`（factory 解析用的那份 raw 实例，非空）。**这是给透传型 meta 字段用的**：以后新增遥测类 meta 字段只改 `RequestMeta` 一处，业务层 `ctx.meta.xxx` 直读；有校验/归一逻辑的字段（locale/currency/country/clientPlatform/projectId）仍由 factory 出派生字段，禁止业务层绕过 `ctx.locale` 直读 `ctx.meta.locale`。注释里写明这条分界。
5. `ActionContext.actionName = actionName`、`isMutation`/`preferReader` 填法与现状等价。
6. `request.setAttribute(PARSED_REQ_ID_ATTR, ...)` 等现有行为原样保留。

### 1.3 删除与迁移

- 删除 8 个 `XxxSpecs.kt`（常量搬进 controller companion，`ActionSpec` data class 与 `ActorRequirement` 中前者删除、后者保留并检查引用）。
- 各 endpoint 调用点把 `requireActorType = ...` 按原 Spec 的 `actor` 值显式传（CUSTOMER 是默认可省；install 模块的 NONE / INSTALL_OR_CUSTOMER 必须显式）——**实参唯一依据是原 DataFetcher 的 `fromDfe(...)` 实参映射，禁止凭感觉填**。
- **命名一致性测试重写**（每模块现有 `routes one-to-one ...` 测试）：反射扫 controller 的 `@PostMapping` path，断言 ① 四段格式 `{q|m}_{module}_{resource}_{action}` 合法；② module 段 = controller 包名；③ action 在 rpc-rollout-client.md §1 表内。原「path ⇔ ActionSpec.isMutation」断言由 1.2-1 的运行时校验取代，测试里删掉。
- `@Operation(operationId = ...)` 保留并引用常量（OpenAPI operationId 稳定性依赖它）。

## 改动 2：Envelope.ok(reqId, data)

`Envelope` companion 增加 overload：

```kotlin
fun <T> ok(reqId: String?, data: T): Envelope<T> = Envelope("200000", "success", data, reqId)
```

- 全部 RPC controller 成功路径 `Envelope.ok(x).copy(reqId = ctx.requestId)` → `Envelope.ok(ctx.requestId, x)`（约 39 处，机械替换）。
- 既有 `ok(data)` 保留（GraphQL 路径与其他调用点 reqId=null）。

## 改动 3：mapper 换形态（三层分工）

规则真相源：`rpc-rollout-server.md` §3.2。本次实施范围 = **存量迁移**，不做 Jimmer DTO 生成改造。

1. **`DemoApiMappers` / `AiApiMappers` 独立 object 删除**，函数改为各 Res DTO 的 companion factory（`TodoRes.of(todo, items, counts)`），逐字段逻辑平移、行为零变化。字段照抄 + `Instant.toString()` 的现状保持。
2. **出参 Res DTO 字段一律不给默认值**（null 语义除外）。加这条时如发现某字段已有默认值且被省略调用，如实保留并在此处标注，不为凑纪律改语义。
3. **聚合组装契约不变**：调用方批量取好传入（先根分页 → IDs 批量查 → Map 组装，禁循环 findById）。
4. **观众相关变换（mask/值类型）本次不实现**——当前代码没有 mask 需求。模式已定稿在 §3.2（值类型 + `of(raw, ctx)` 构造期决策 + `ActionContext` 直传），第一次出现敏感字段需求时按其落地；届时如需 `ActionContext.isManager` 之类派生属性，加在 ActionContext 上。
5. Jimmer DTO 生成（纯投影零手写）为后续优化，**前置条件是验证本仓 Jimmer 版本支持 DTO 作为查询投影**；验证前不动存量。

## 改动 4：限流收口

前置确认：customer/install 并入 auth 的重构（`81ee3d4` 一线）已落地；若该重构仍有后续改动未合，本项挂起等它。

1. 现状三处手抄同一模式：install 层 → IP 层 →（legacy fallback）→ 业务，拒绝 429000 + retryAfterSec、install 层拒绝不碰 IP 计数器、被拒不退款。位置：`AuthApiController`（合并后）/ `InstallApiController` / `AiController.rateLimitByAction` + `DownstreamLimits`。
2. 提取共享组件（建议 `infra/ratelimit/` 下，如 `ActionRateLimit`），接口按语义命名（如 `checkInstallThenIp(ctx, action, limits)`），阈值来源仍为 `RateLimitProperties`。
3. **语义红线：逐行平移**——Redis key 格式（含 `legacy:` 段、projectId/action 隔离）一个字符都不能变（存量计数器不能失效）；「install 层拒绝不退 IP 额度」「无可信 iid 走 legacy 独立计数器」两条语义原样；错误码 429000/429002 与 retryAfterSec 逐一保留。
4. 迁移后三个 controller 的限流调用点全部走共享组件，各自现有限流测试（AiRateLimitTest 等）保持全绿（key/断言不改）。

## 验收清单

- [ ] 8 个 controller 无 `XxxSpecs.` 引用；`grep -rn 'ActionSpec' core-api/src/main` 仅剩 ActorRequirement 相关（或为 0）。
- [ ] `grep -rn 'copy(reqId' core-api/src/main` 为 0。
- [ ] `grep -rn 'object.*ApiMappers' core-api/src/main` 为 0。
- [ ] 限流 Redis key 逐字符对照迁移前（diff 测试断言里的 key 字面量确认）。
- [ ] 全量 `:core-api:test` 绿；app 联调语义（URL/meta/信封）零变化——本单纯是服务端内部重构，客户端无感。

## 明确不做（另行处理）

- **凭证与 meta 分离**（meta 去 accessToken、三段信封、dev 态 Authorization 适配）：方向已定、字段名待定稿，见 openapi §一「凭证与 meta 分离」，定稿后独立 commit。
- `@JsonView` 通道、Jimmer DTO 投影化、wire 服务端实现与客户端 HPKE §10 对齐：各自单独立项。
