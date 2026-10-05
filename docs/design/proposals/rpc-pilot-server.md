# RPC 试点实现计划 — 服务端（ifmix_server）：RPC 基础设施 + demo 模块

> **给接手的 agent**：按顺序读 `AGENTS.md` → `docs/design/proposals/graphql-to-http-rpc-openapi.md` §一（协议定稿，唯一真相源）→ `docs/design/infra/wire-encryption.md` §10 → 本文件。契约冲突时以 proposal §一 与 wire §10 为准并回改本文件。逐任务更新「状态」，不要重做已完成任务。
> - 仓库：`/Users/jason/ai/myprojects/ifmix_server`；建议分支 `feature/rpc-pilot`（自 main 切出）。
> - 前端配套见 `rpc-pilot-client.md`（antique 仓库，不归你改）。客户端 wire-v3（HPKE）已由用户完成，假定可用。
> - **硬性边界：本试点只做基础设施 + demo 模块。其余模块（media/cs/install/customer/auth/pay/ai）一律不动；GraphQL 全量保留不删。** 试点完成、E2E 通过、用户 review 通过后另开任务再迁移。

## 0. 一页纸摘要

为 `POST /customer/core/rpc/{reqName}` 建立协议无关的 RPC 基础设施（ActionSpec / ActionContextFactory / RequestMeta / Envelope），并把 demo 的 8 个 action 作为首个迁移对象。请求信封 `{meta, input}`、响应 `Envelope{code, msg, data}`、HTTP status = code 前三位。凭证从 `Authorization` header 切到 `meta.accessToken`（**仅 RPC 路径**；GraphQL 路径维持现状直到删除）。wire v3 加密对 RPC 与 GraphQL 一视同仁（filter 与路径无关），服务端 HPKE 实现按 `wire-v3-plan-server.md` 执行。

## 0.1 协议契约速查（与 proposal §一 一致）

```
URL:     POST /customer/core/rpc/{reqName}    （reqName 带 q_/m_ 前缀，如 m_demo_createTodo）
请求:    {"meta": RequestMeta, "input": {...XxxRequest}}
         meta（全可空，必填性由 ActionSpec 声明）:
           reqId, projectId, accessToken, locale, currency, country,
           userTz(IANA 名，可选), appVersion, otaVersion, clientPlatform,
           deviceModel, osVersion
         不进 meta: installId(从 token iid 解出) / refreshToken(业务 input) / ts(wire pt 已有)
响应:    {"code": "200000", "msg": "...", "data": {...XxxResponse}}
         HTTP status = code 前三位；成功 200000/200
header 留守: Content-Type / x-wirep-version(=3 加密，缺省 v1 明文) / User-Agent / CF 注入头
         （注意: x-proto-version 已定稿改名为 x-wirep-version，见 T0）
加密:    wire v3 HPKE，Content-Type: application/octet-stream，filter 解密在最外层，
         controller 看到的始终是明文 JSON
错误:    解密失败 → 明文 400003（不区分原因）；业务错误 → Envelope code，HTTP status 同步
```

## 1. 任务分解

### T0 前置检查：wire v3 服务端状态 + 版本头改名

- [ ] **状态：pending**
- 检查 `docs/design/infra/wire-v3-plan-server.md` 各任务状态：若未完成，先按该文档执行完毕（本试点的 E2E 依赖它）；若已完成，只做下面的改名。
- 版本头改名：`RequestHeaders.PROTO_VERSION` 值从 `x-proto-version` 改为 `x-wirep-version`；`WireCryptoFilter` 改为按 `x-wirep-version: 3` 生效；同步 `wire-v3-plan-server.md` / `wire-encryption.md` 内的头部引用（这是 proposal §六「文档同步待办」的一部分）。旧名 `x-proto-version` **不做兼容**（v2 从未上线，客户端同步改名）。
- **验收**：`./gradlew :core-api:compileKotlin :core-api:test` 通过；wire 测试用例全部引用新头名。

### T1 RPC 基础设施（新增于 `infra/http/`，协议无关）

- [ ] **状态：pending**
- `ActionSpec.kt`：每个 endpoint 的服务端声明——reqName、是否 mutation、actor 要求（匿名 / install token / customer token / 可选认证）、是否必须 projectId、locale/currency/country 是否参与上下文。
- `RequestMeta.kt`：typed data class，字段见 §0.1；Jackson 反序列化；未知字段忽略（向前兼容）。
- `ActionContextFactory.kt`：从 `HttpServletRequest` + 解析出的 `RequestMeta` + `ActionSpec` 构造 `ActionContext`。要点：
  - `meta.accessToken` 替代 `Authorization` header：走 `RequestParser` 现有 token 校验路径（抽出「校验 token 字符串 → ActionContext 身份字段」的可复用方法，GraphQL 路径继续用旧入口，不要复制粘贴校验逻辑）。
  - `installId` 只来自 token iid claim；`reqId` 缺省服务端生成 UUID 并回写响应头 `x-req-id`（对齐现有语义）；`cf-ray` 写入日志字段。
  - 失败即抛 `ApiError`（token 过期/无效、缺 projectId 等），由 GlobalExceptionHandler 统一转 Envelope。
- `Envelope` 复用现有 `infra/http/Envelope.kt`；确认 GlobalExceptionHandler 对所有 RPC 异常输出 `{code, msg, data:null}` 且 HTTP status = code 前三位（补测试）。
- **日志脱敏**：`RequestLoggingFilter` / 解密后日志不得打出 `meta.accessToken` / `refreshToken` 明文；解密后的完整 body 不落日志（只打 meta 摘要 + input 大小）。
- 全链路单测：明文请求、加密请求（kid=1 开发 key）、缺 token、token 过期、缺 projectId、reqId 回显。

### T2 demo DTO 与 DemoQueryService

- [ ] **状态：pending**
- 为 8 个 demo action 定义请求/响应类型（手写 data class；单实体读视图可按 proposal §一 DTO 规则用 Jimmer DTO 语言）：`CreateTodoInput`→`TodoDto`、`UpdateTodoInput`、`DeleteTodoInput`/`DeleteTodoByIdsInput`、`BatchUpdateTodoItemsInput`、`FindTodoByIdInput`、`FindTodosInput`（分页）→`TodoPageDto`、`FindTodosByIdsInput`。
- 枚举 Int 透传、DateTime ISO-8601 字符串、unset 优先于 set 的语义与现状一致。
- 新增 `DemoQueryService`（`modules/demo/` 或 `bff` 侧按分层规则放置）：`q_demo_findTodos` / `findTodosByIds` 的聚合——先查根分页、收集 ID、批量查 items/count、按 Map 组装，**禁止循环 findById，批量结果顺序必须与传入 ID 一致**。替代 TodoItemsResolver/DataLoader 的职责。
- **验收**：无 N+1（聚合查询固定为 ≤3 条 SQL）；`modules/demo` 不 import 任何 GraphQL generated types。

### T3 DemoController

- [ ] **状态：pending**
- 新增 `bff/rpc/customer/demo/DemoController.kt`（`@DgsComponent` 不再使用，普通 `@RestController`）：8 个 endpoint，路径 = 原 reqName，`operationId` = reqName。
- Controller 只做：ActionSpec 声明 → ActionContextFactory 构造上下文 → mutation 包 `GlobalTxRunner` → 调 Facade（query 走 DemoQueryService）→ 包 Envelope。不 import repo/handler。
- AI/demo 语义红线：本模块无事务拆分要求，但保持「mutation 在 GlobalTxRunner 边界内」与现状一致。
- **验收**：8 个 action 全部可经 RPC 调用；GraphQL 路径的 demo action 仍然工作（双轨并存）。

### T4 合约测试（MockMvc/WebTestClient）

- [ ] **状态：pending**
- 每个 action 一条固定用例：HTTP status、Envelope 形状、响应 JSON 与 persisted document 固定响应字段一致。
- 横切用例：code/status 一致性（含 401/403/404/429）、`{meta, input}` 解析、加密请求走通（复用 wire-v3 固定向量路径）、`userTz`/`deviceModel` 透传进日志字段。

### T5 联调与收尾（review 门禁在此）

- [ ] **状态：pending**
- 本地 `bootRun`（PG + Redis + kid=1 开发 key），配合前端 agent 用 client-sdk 走通 8 个 action 的明文 + 加密 E2E。
- GraphQL 与 RPC 固定用例差异对比：字段、null、错误码、分页游标一致，记录差异表。
- 更新 `docs/design/proposals/graphql-to-http-rpc-openapi.md`：状态行标注「demo 试点已实施」；发现契约偏差时回改 §一。
- **停下等 review**：输出试点报告（改动清单、测试结果、差异表），用户确认 OK 前不得迁移其他模块、不得删 GraphQL。

## 2. 明确不做

- 不迁移 demo 以外任何模块；不删 GraphQL/DGS/trusted documents；不做 OpenAPI 全量契约（T3 的 springdoc 注解顺手带上即可，阶段 6 再系统化）。
- 不动 webhook、pay 验签、attest 路径。
- 不建通用幂等、不引 Konvert、不把逻辑外键改 ORM 关联。
