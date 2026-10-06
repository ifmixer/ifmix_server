# RPC 协议决策留存（2026-10-06 定稿，GraphQL 迁移完成后沉淀）

> **状态：✅ 全部已实施**（GraphQL 引擎已删除，`/api/customer/core/{actionName}` 为唯一移动端 API）。
> 本文件是实施计划归档后留存的**决策摘要**——历史推导与逐任务执行细节见 `archive/`（见文末索引）。新 agent 不需要重新探索这些决策的来龙去脉，直接按本文执行；需要推导依据时再去 archive 里查。

## 1. 去 GraphQL（已复议，不回迁）

- Trusted documents 模式下客户端没用字段级灵活性，GraphQL 实质是"每个 query 一个固定 RPC + 一层执行引擎税"。迁移后 DataLoader 变显式 batch，净复杂度下降。
- 复议结论**不回迁**：派生字段（mask/price）由共享 Res 类型的 companion factory / 值类型承接（服务端单落点）；类型复用两边等价；未来公开 API 是独立 surface（独立鉴权/限流/部署），届时在协议无关的 Facade 上加层即可。业务层（Facade/Handler/Repo）已刻意做到协议无关。
- 详见 archive/graphql-to-http-rpc-openapi.md §一「去 GraphQL 复议」。

## 2. 协议契约（现行实现）

```
URL:      POST /api/customer/core/{actionName}          （受众段 customer 保留；manager 将来走 /manager/api/…）
actionName = {q|m}_{module}_{resource}_{action}        四段下划线；q/m = 读/写意图
          前缀是权限的表达不是来源（裁决在 requireActorType + 业务所有权校验）；一致性由命名扫描测试锁死
          URL 不带 resourceId（ID 是高基数攻击者字段，属加密 body 载荷）
请求体:   {"meta": {...}, "input": {...}}
响应体:   {"reqId": "...", "code": "200000", "msg": "success", "data": {...}}
          HTTP status = code 前三位；成功固定 200000/200；无 partial error
          reqId 回显 meta.reqId（缺省服务端生成）；错误路径 request attribute → x-req-id header 兜底
凭证:     定稿方向 = 三段信封顶层 "authorization"（键名映射 HTTP Authorization 语义，Bearer 前缀可选），
          meta 从此零敏感字段可整段进日志。dev 态走 DevRpcHeaderAdapter（@Profile("local")，prod 不认 Authorization header——
          token 进 header 即进边缘访问日志，破坏 wire「凭证对中间层不可见」）
加密:     wire v3 信封（octet-stream），版本头 x-wirep-version；AAD 35B（ver‖kid‖enc‖flags）
```

**meta 字段**：reqId / projectId / locale / currency / country / userTz(IANA) / appVersion / otaVersion / clientPlatform / deviceModel / osVersion（typed `RequestMeta`，字段全可空，必填性由 endpoint 声明）。

**明确不进 meta**：installId（从 token iid claim 解出——客户端声称的身份不是身份）、refreshToken（只有 2 个 action 用，进 meta 徒增暴露面）、ts（wire 明文头已有）、签名（AEAD 已保完整）。

**meta ≠ ActionContext**：meta 是客户端自供未认证的原料；ActionContext 是 `ActionContextFactory` 消费 meta + header + 路径后的验证产物，业务代码只读 ActionContext。`ctx.meta.xxx` 直读透传字段，新增 meta 字段只改 RequestMeta 一处。

## 3. header 留守原则与限流分工

header 只留四类，其余全部进 meta：
1. 信封标记（Content-Type、x-wirep-version——解密前必须先看到）
2. 边缘注入信号（CF 真实 IP、cf-ray、bot-score、ipcountry）
3. 标准 User-Agent（advisory，服务端不解析，结构化信源在 meta）
4. 按需 advisory 提示（低基数、可容忍伪造、服务端不得采信）

**Authorization 不为 WAF 留守**：边缘没有验签密钥，per-token 边缘限流是高基数陷阱。**限流分工：边缘限 IP（解密前），服务端限身份/项目（锚点 customerId/projectId 读自 ActionContext）**。

## 4. Controller 模式（`bff/api/customer/{module}/`）

- actionName 常量在 controller companion object，`@PostMapping` / `@Operation(operationId)` / `fromRpc` 三处引用同一常量；ActionSpec 已撤销（8 个 XxxSpecs.kt 已删）。
- `fromRpc(request, actionName, isMutation = null, body, requireActorType = CUSTOMER, requireProjectId = true)`；isMutation 省略时由 `m_` 前缀兜底。
- 请求体泛型 `ApiRequestBody<T>`（无入参用 `NoInput`，必填用 `requireInput()`）；mutation 包 `GlobalTxRunner`。
- 读聚合走 `XxxQueryService`（不叫 Fetcher——与 DGS/Jimmer Fetcher 歧义）：先分页根、收集 IDs 批量查、按 Map 组装，禁循环 findById、禁直访 repo。

## 5. DTO 与 mapper 三层分工

分界：**字段照抄实体的用 Jimmer DTO，其余手写**（默认不引入 Konvert 做实体映射）。

1. **纯实体投影 → Jimmer DTO 生成，零手写**：entity 改名 → 重新生成 → 编译报错（不静默）。
2. **多源聚合 / 协议形状 → 手写 companion factory**：items+counts、页面级组装是逻辑不是映射。
3. **观众相关变换（手机号 mask 等）→ 值类型构造期决策**：`PhoneRes.of(raw, ctx)`，factory 传 `ActionContext`（完备上下文，签名稳定）；不用自定义 serializer（拿不到请求上下文）。
4. **出参 Res 字段一律无默认值**（null 语义除外）：加字段漏写 factory 即编译错误。
5. 每个 Res 配固定全字段 Fetcher，不做动态裁剪——读 unloaded 属性显式抛错而非静默错值。

## 6. 未决/后续事项

- **My 命名规则决策待拍板**：reqName 是否叠 My（见 `docs/plans/2026-10-06-opname-four-segment-legacy-removal-install-split.md` §1.3 方案 A/B）。
- 三段信封 `authorization` 的两端锁步迁移；Envelope 预留 meta 字段（首候选 serverTime，wire §9 ts 时效的前置）。
- 限流样板三处手抄，等 auth 合并重构后统一收口。

## 7. 归档索引（docs/design/api-protocol/archive/）

| 文档 | 内容 |
|---|---|
| graphql-to-http-rpc-openapi.md | 迁移总计划；§一 = 协议定稿全文（本文的推导依据）、§六 = 与 wire v3 合并执行顺序 |
| rpc-pilot-server.md / rpc-pilot-client.md | demo 试点（服务端/客户端）实施计划，Controller/DTO 模式先例 |
| rpc-rollout-server.md | 全量迁移 M0–M5 计划；§3 = mapper/controller 通用规范定稿 |
| rpc-refactor-controller-mapper.md | ActionSpec 撤销等四项重构的实施单（已全部落地） |

注：`rpc-rollout-client.md`（antique 仓库客户端 R0–R5）未归档——客户端迁移仍在该仓库推进，其 §1 action 名表是客户端侧参照；**服务端命名现状以本文 §2 + plans 交接文档为准**。
