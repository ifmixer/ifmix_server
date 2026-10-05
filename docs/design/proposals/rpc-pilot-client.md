# RPC 试点实现计划 — 客户端（antique / client-sdk）：RPC 传输层 + demo action

> **给接手的 agent**：先读 `packages/client-sdk/AGENTS.md` → ifmix_server 仓库 `docs/design/proposals/graphql-to-http-rpc-openapi.md` §一（协议定稿，唯一真相源）→ 本文件。契约冲突时以 §一 为准并回改本文件。逐任务更新「状态」，不要重做已完成任务。
> - 仓库：`/Users/jason/ai/myprojects/antique`；主要改动在 `packages/client-sdk/packages/api/`，建议分支自 `feature/wire-v3` 切出（`feature/rpc-pilot`）。
> - **前置假设**：wire v3 客户端（`wireCrypto.ts`，RFC 9180 HPKE）已完成并合入本分支；SDK 拆分已完成（api/attest/auth/core/install/noti/pay/ui）。
> - 服务端配套见 ifmix_server 仓库 `docs/design/proposals/rpc-pilot-server.md`（不归你改）。联调（T5）需服务端 `bootRun` 就绪。
> - **硬性边界：本试点只做传输层 + demo 的 8 个 action。其余 action 不动，GraphQL 路径（gqlOp）全量保留，trusted documents 流程不删。**

## 0. 一页纸摘要

在 api 包新增 `rpcOp` 传输层：`POST {baseUrl}/customer/core/rpc/{reqName}`，请求体 `{meta, input}`，响应 `Envelope{code, msg, data}`，完整复用现有 wire v3 加密（`sendMaybeEncrypted`）、Envelope/ApiError 解析、reqId、session 处理。然后把 demo 的 8 个 action 切到 rpcOp 作为试点。HTTP status = code 前三位；凭证从 `Authorization` header 改为 `meta.accessToken`（仅 RPC 路径）。

## 0.1 协议契约速查（与 proposal §一 一致）

```
URL:     POST {baseUrl}/customer/core/rpc/{reqName}     （如 m_demo_createTodo）
请求体:  {"meta": {...}, "input": {...}}                （wire v3 加密前为 JSON，加密后 octet-stream）
meta 字段:
  reqId          客户端自供（复用 reqId.ts），响应头 x-req-id 回显
  projectId      项目上下文（demo 用例按现有 GraphQL 变量同值）
  accessToken    唯一凭证，纯 token 字符串（不带 Bearer 前缀）；install/customer token 共用此字段
  locale/currency/country   用户偏好（与现有 header 同值语义）
  userTz         可选，IANA 名（如 "Asia/Shanghai"）
  appVersion/otaVersion/clientPlatform/deviceModel/osVersion   遥测
  不传: installId（服务端从 token iid 解出）/ refreshToken（业务 input）/ ts（wire 层已有）
响应:    {"code": "200000", "msg": "...", "data": {...}}；HTTP status = code 前三位
header:  Content-Type / x-wirep-version: 3（加密时）——注意：原 x-proto-version 已定稿改名
加密:    复用 wireCrypto.ts；415/400003 降级、响应解密失败不重发等语义全部不变
```

## 1. 任务分解

### T1 版本头改名（小，先做）

- [ ] **状态：pending**
- `wireCrypto.ts` / `graphql.ts` 内 `x-proto-version` 常量与引用改为 `x-wirep-version`；`wire-vectors.json` 相关测试与注释同步。与协议版本字节 `ver=3` 无关，只改 header 名。
- **验收**：`packages/client-sdk/packages/api` 测试全绿；grep 无 `x-proto-version` 残留。

### T2 rpcOp 传输层（`packages/api/src/api/rpc.ts` + 抽公共 transport）

- [ ] **状态：pending**
- 把 `graphql.ts` 里的 `sendMaybeEncrypted` 抽为 `transport.ts` 公共函数（签名已是 url/headers/body 通用，剥离 GraphQL 日志前缀等）；`gqlOp` 改为调用它，**现有测试必须原样全绿**（只挪位置不改行为）。
- 新增 `rpc.ts`：
  - `rpcOp<T>(reqName: string, input: object, opts: RpcOpts): Promise<T>`。
  - 组装 `{meta, input}`：meta 由 `RequestMetaBuilder` 统一构造（reqId、session 的 accessToken、locale/currency/country/userTz、appVersion/otaVersion/clientPlatform/deviceModel/osVersion——取值来源对齐现有 header 的构造处，别造第二套）。
  - 响应解析复用 `envelope.ts`：code≠200000 → 抛现有 `ApiError`（`isTokenExpired`/`isRateLimited` 等判定全部生效）；**接入现有 session/refresh 管线**（401002 触发 refresh 重试的路径与 gqlOp 一致——找到 gqlOp 的错误处理挂接点，rpcOp 走同一挂接，不要复制一份 refresh 逻辑）。
  - wire：`opts.wire` 语义与 gqlOp 相同（每次请求求值、进程级降级共享 `wireUnsupported`）。
  - 日志/脱敏：复用 `redactForLog`；`meta.accessToken` 必须脱敏。
- 类型：demo 的 request/response 手写 type（对齐服务端 DTO，联调时以服务端 OpenAPI/实际响应校对；全量 codegen 是后续阶段，试点不搭）。
- **验收**：rpcOp 单测——明文/加密、Envelope 成功与各错误码、降级（415/400003）、reqId 回显、token 脱敏，全部通过；gqlOp 原测试不动一行仍绿。

### T3 demo action 切换

- [ ] **状态：pending**
- 在 api 包新增 demo RPC 函数（8 个）：`m_demo_createTodo` / `m_demo_updateTodo` / `m_demo_deleteTodo` / `m_demo_deleteTodoByIds` / `m_demo_batchUpdateTodoItems` / `q_demo_findTodoById` / `q_demo_findTodos` / `q_demo_findTodosByIds`，签名与返回值语义对齐现有 gqlOp 封装（`ActionResult`、分页游标等）。
- 客户端目前没有 demo feature 屏幕（调用面在 SDK 测试与 Developer 工具）——为每个 action 写 rpcOp 用例，断言请求体形状（meta 字段齐全、input 与原 GraphQL variables 同值）与响应解析结果同 gqlOp 版本一致。
- 不改其余 action；`customer-gql.graphql` / generated types / persisted manifest 不动。

### T4 联调 E2E（需要服务端就绪）

- [ ] **状态：pending**
- 服务端 `bootRun`（ifmix_server，PG + Redis + kid=1 开发 key）。
- 走通 8 个 action：明文与加密各一遍；>4KB 响应验证 gzip 分支；415/400003 降级路径实测一次（临时不配 key 的服务端）。
- 对比同用例 gqlOp 与 rpcOp 的返回数据与错误码，记录差异表（预期为零差异；有差异先查服务端 DTO 转换）。
- 真机（可选）：Developer 开关手动验证加密 rpcOp。

### T5 收尾（review 门禁在此）

- [ ] **状态：pending**
- 更新 client-sdk README/AGENTS.md：rpcOp 用法一段；标注「demo 试点，其余 action 仍走 gqlOp」。
- **停下等 review**：输出试点报告（改动清单、测试结果、与 gqlOp 的差异表、wire 指标 sealMs/openMs 抽样）。用户确认 OK 前不得迁移其他 action、不得删 gqlOp/trusted documents。

## 2. 明确不做

- 不迁移 demo 以外 action；不删 GraphQL/manifest/codegen 流程。
- 不加新原生依赖（HPKE/gzip 依赖已就位）；不动 attest/auth/install/pay/noti 包。
- 不做请求重试/幂等新机制（沿用现有 retry 语义）。
