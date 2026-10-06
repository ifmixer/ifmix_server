# RPC 全量迁移计划 — 服务端（ifmix_server）：demo 之后的所有修改

> **给接手的 agent**：按顺序读 `AGENTS.md` → `docs/design/proposals/graphql-to-http-rpc-openapi.md` §一（协议唯一真相源）→ `rpc-pilot-server.md`（demo 试点，模式先例与基础设施规格）→ `rpc-rollout-client.md` §1（**action 名总表，单一真相，本文件不复制**）→ 本文件。冲突时以 proposal §一 为准并回改文档。
> - 仓库：`/Users/jason/ai/myprojects/ifmix_server`；继续在 `feature/graphql-to-rpc` 分支按阶段提交，commit 前缀 `[rpc][M*]`。
> - 客户端按 `rpc-rollout-client.md` 并行推进（R0–R5）；**每个 M 阶段联调前确认客户端对应阶段就绪，时序以 gate 为准**。
> - **硬边界：gqlOp 路径全量保留到 M5；每迁移一个模块只新增 RPC 代码，不改既有 Facade/Handler/Repository 的行为；webhook 路径不动；`infra/auth/RequestParser.kt`、`infra/graphql/ActionContextProvider.kt` 不改。**

## 0. 现状与总览

demo 试点（S1–S6）：客户端已验收；服务端若未完成，**先按 `rpc-pilot-server.md` 完成**，并叠加本文件的四处契约修订（§2）。之后按 M1–M5 迁移其余 31 个 action，模式与 pilot 完全一致：每模块一组 DTO + Konvert/手写 mapper + Controller（ActionSpec 查表）+ 合约测试；gql 版本保留到 M5。

**并行纪律（用户约束：subagent ≤ 3）**：每个阶段内最多 3 个并行 subagent（各自 git worktree，按文件所有权矩阵工作，rebase 回主分支）；无并行价值时串行。任何 WP 严禁改动所有权之外的文件。

## 1. 契约速查（最新定稿，覆盖 pilot 文档的过时处）

```
URL:      POST /api/customer/core/{actionName}
          actionName = {q|m}_{module}_{resource}_{action}（四段下划线；总表见 rpc-rollout-client.md §1）
请求体:   {"meta": {...}, "input": {...}}（wire v3 加密时 octet-stream；meta 字段见 pilot §0.1）
响应体:   {"reqId": "...", "code": "200000", "msg": "success", "data": {...}}   ← reqId 为本次新增
          reqId = 回显 meta.reqId（缺省用服务端生成值）；HTTP status = code 前三位
header:   Content-Type / x-wirep-version: 3 / User-Agent / CF 注入头 / x-req-id（继续回显，客户端日志依赖）
AAD:      wire v3 请求 AAD = ver(1)‖kid(1)‖enc(32)‖flags(1) = 35 字节（文档笔误已修正；若按 36B 起草过，丢弃重写）
mapper:   Konvert 优先，手写兜底（§3.2）
```

## 2. 四处契约修订（若 pilot 未完成，落地在 pilot 内；已完成则在 M0 补）

1. **AAD 35 字节**：`wire-encryption.md §10.1` / `wire-v3-plan-server.md` / `wire-v3-plan-client.md` 已修正。检查 S1 产出：AAD 拼装必须 `ver‖kid‖enc‖flags` 共 35B。
2. **recItems 打戳**：`TodoRecItemInputDto` 无 createdAt/updatedAt 字段；mapper 转 generated 时 `createdAt = Instant.now().toString()`、`updatedAt = null`（pilot 文档 §4.2/4.4 已更新）。
3. **Envelope 顶层 reqId**（本文件 §3.1）。
4. **Konvert 优先的 mapper 规则**（本文件 §3.2，proposal §一 已修订；pilot 的 `DemoRpcMappers` 纯手写部分按此改造）。

## 3. 全量通用规范

### 3.1 Envelope 加 reqId

1. `infra/http/Envelope.kt`：`data class Envelope<out T>(val code, val msg, val data, val reqId: String? = null)`——追加可空字段，既有 `ok()/error()` 不变（reqId=null 兼容 GraphQL 路径与其他调用点）。
2. 成功路径（RPC controller 统一）：`Envelope.ok(data).copy(reqId = ctx.requestId)`。
3. 错误路径：`ActionContextFactory.fromApi` 在计算 requestId 后立即 `request.setAttribute("com.ifmix.parsed.reqId", requestId)`（attr 常量放 RequestHeaders companion）；`GlobalExceptionHandler.handleApiError` 与 `handleGeneric` 的 body 构造改为带 reqId：`request?.getAttribute(ATTR) as? String ?: request?.getHeader(RequestHeaders.REQ_ID)`。factory 抛出的异常发生在 setAttribute 之后，天然带值；factory 之前挂掉的（如 body 不可读）用 header 兜底，再没有就 null。
4. 测试：成功响应体含 `reqId` 且与请求 `meta.reqId` 同值；401/429/500 错误响应体含 reqId；无任何 reqId 来源（明文 curl 不带）时字段为 null 不报错。

### 3.2 mapper 规则：Konvert 优先

依赖：`build.gradle.kts` 加 Konvert KSP（选 KSP2 兼容的最新稳定版，以官方文档为准）+ `konvert-api`。

| 场景 | 做法 |
|---|---|
| 入参 DTO ↔ generated input 纯字段同名搬运（含嵌套） | Konvert `@Mapper` 生成 |
| 涉及类型转换/打戳/枚举转换（unset String→TodoUnsetField、recItems createdAt 打戳、FilterOp 字符串→枚举） | 手写，或 Konvert 生成主干 + 手写 `@Mapping` 表达式——以可读性定，不硬凑 |
| entity → 视图 DTO（含 `Instant → String`、items/counts 批量组装） | 手写（组装逻辑本就是业务代码） |
| Jimmer DTO language 产物（`toEntity()`、实体视图构造） | 直接用，不套 Konvert |

KSP 生成失败/生成代码不可读时允许该处回退手写，但必须在 WP 报告里列出回退点。每个模块的 mapper 测试要求：Konvert 生成路径与手写路径的输出逐字段断言。

### 3.3 Controller / ActionSpec 迁移规则（与 pilot 相同，三处强调）

1. Controller 模板 = `DemoController`：`@RequestMapping("/api/customer/core")` + 每 action `@PostMapping("/{actionName}")` + `ActionContextFactory.fromApi` + mutation 包 `GlobalTxRunner` + `Envelope.ok(data).copy(reqId=...)`；不 import repo/handler。
2. **ActionSpec 的唯一依据是现有 DataFetcher 的 `fromDfe(...)` 实参**：迁移某 action 时，逐字段把该 fetcher 里 `ctxProvider.fromDfe(dfe, requireAppId=…, requireActorType=…, requireLocale=…)` 的实参搬进 `ActionSpec`（映射：requireActorType=ACTOR_CUSTOMER → CUSTOMER；null → NONE 或 INSTALL_OR_CUSTOMER 按 token 语义），并在该模块测试里加一张「actionName ↔ fromDfe 实参」对照断言。**禁止凭感觉填**。
3. **命名一致性测试**（每模块）：反射扫描该模块全部 ActionSpec——path 段 `m_` ⇔ `isMutation=true`；module 段 = 所属模块；resource/action 在 rpc-rollout-client.md §1 表内。

## 4. 阶段计划（与客户端 R0–R5 对齐）

### M0 pilot 收尾 + 修订落地（gate 0 = 客户端 gate 1 联调）

S1–S6 未完成的先完成；已完成的把 §2 的 2/3/4 三处修订补进 demo（AAD 若 S1 已实现只做核对）。产出：demo 8 action（新名）+ envelope reqId + Konvert 化 mapper 的联调报告。

### M1 media + cs（6 actions）

每模块 WP（可 2 个 subagent 并行，文件集零交集）：
- `dto/media/**` + `dto/cs/**`（手写/Konvert）、`bff/api/customer/media/MediaController.kt`、`bff/api/customer/cs/CsController.kt`、各自合约测试。presign 无聚合，不需要 QueryService。
- media 语义红线：presignUpload/Download 的鉴权与 key 校验路径不变（对照 `fromDfe` 实参）；cs 的限流（429000）如现有 fetcher 有 retryAfterSec，必须带上（§3.1 的 Retry-After 已通用）。
- 验收：模块合约测试 + 与客户端 R1 联调差异表为空。

### M2 install + customer + auth 身份链路（8 actions）⚠️ 风险最高

3 个 subagent 上限用满：install / customer+auth 两个 WP 并行 + 第三个做共享测试基建（错误码矩阵）。

- **ActionSpec 对照是本阶段核心**：`m_install_*` 全部为 install-token/bootstrap 语义（`fromDfe` 实参逐个核对，特别是 createInstall 的匿名允许、refresh 的 `requireActorType=null`——access token 过期不拦截 refresh，refreshToken 在 input）；`m_auth_login` 的 `mustGetLoginInstallId` 语义进 controller 断言；`q_auth_session_me` = CUSTOMER。
- 语义红线：IP 限流（customer 创建）、install/customer 绑定、token iid、账号合并行为不得改变；refresh 的 401003、install 的 403001/403002/409001/404001 错误码逐一保留。
- 全局事务：login/refresh/logout/createAnonymous/createInstall/updateInstall 保留现有 `GlobalTxRunner` 边界（对照各 fetcher）。
- 验收：身份链路合约测试 + 与客户端 R2 联调（含明文降级下引导链可用）→ **gate 2**。

### M3 pay + ai（14 actions）

2 个 subagent 并行（pay 一个、ai 一个）。

- pay：`m_pay_iap_verify` 验签路径与事务不变；402000 保留。
- ai 语义红线（照 pilot §2 精神）：createScan/runDeepResearch 的「AI 调用在事务外、结果写入事务内」拆分不变（AI 在 Handler 层，本迁移不动 Handler）；updateScan 写后读从 writer；collection item 列表先分页再批量组装、禁循环 findById——`AiQueryService` 按 DemoQueryService 模式新建；列表视图不读 JSONB 大字段。
- 配额/限流错误码 429000/429001/503000 逐一保留（retryAfterSec 必带 → Retry-After 头自动生效）。
- 验收：AI 成功/部分成功/不可用/配额/限流/事务拆分测试全绿 + 与客户端 R3 联调 → **gate 3**。

### M4 OpenAPI 全量契约 + 清理

1. springdoc：全部 action 唯一 operationId（= actionName）、request/response schema、meta/错误描述；启用 `/core/api-docs/json`；snapshot 测试断言 39 个 actionName 全在且无重复。
2. 清理：`app.auth.legacy-install-id-fallback` 开关与 `legacyInstallId` 路径移除（RPC 无此信源；确认老 GraphQL 路径无生产流量后执行——若未到 M5，此项推迟到 M5 一并做）。
3. **gate 4 = 客户端 R4 完成评审**。

### M5 删 GraphQL（最终 gate，与客户端 R5 同步）

照 `graphql-to-http-rpc-openapi.md` 原阶段 7 清单执行：删 `bff/graphql/**`、`infra/graphql/**`、`resources/schema/**`、`resources/graphql/persisted-queries/**`、DGS 依赖与 codegen、GraphiQlHttpStatusFilter、trusted-document 测试；确认源码无 `com.netflix.graphql`/`generated.types`/`/greq/`；此时把 RequestParser/ActionContextProvider 一并删除，`ActionContextFactory` 内复制来的 token 校验逻辑就位为唯一实现；更新 AGENTS.md / ARCHITECTURE.md / CODING_GUIDE.md / 本目录各文档状态。**最终 review 前不动手**。

## 5. 并行与提交纪律

- 每 M 阶段 subagent ≤ 3（用户硬约束），按「每模块一个 WP + 共享基建一个 WP」划分，文件集零交集；worktree 并行后 rebase。
- 每 WP 一个 commit（`[rpc][M*][模块]`）；每阶段结束 `:core-api:compileKotlin :core-api:test` 全绿 + 联调差异表 + 更新本文档与 pilot 文档状态行。
- gate 未过不进下一阶段；契约偏差停下写报告、回改 §一，不得两端各自迁就。

## 6. 明确不做

- 不删 GraphQL（M5 才删，且与客户端 R5 同步）；不改 webhook；不动 `modules/**` 既有 Facade/Handler/Repository 行为（新增 QueryService 除外）；不建通用幂等；不改 ORM 关联；requestParser/ActionContextProvider 不改（M5 一并删除）；Konvert 不用于 Jimmer 实体构造（`toEntity()` 已覆盖）。
