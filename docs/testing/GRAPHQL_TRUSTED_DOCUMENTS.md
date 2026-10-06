# GraphQL Trusted Documents (Persisted Query Allowlist)

安全机制：生产环境只允许**预注册的 query** 执行，前端不发送 raw query，杜绝任意 query 攻击面。

本次改动：reqName 从 **`x-api-name` header 迁移到 URL path**，以便 Cloudflare / nginx 等前置层能按具体 query 分流、缓存、限流、加 WAF 规则。

## 两个 endpoint（并存，职责分开）

| endpoint | 路径 | 用途 | body |
|----------|------|------|------|
| **GReq**（persisted query） | `POST /customer/core/greq/{reqName}` | 生产主入口，前端只走这里 | `{"query":"","variables":{...}}` |
| **GQL**（raw query） | `POST /customer/core/gql` | GraphiQL / 本地 API 探索，**保留不动** | `{"query":"...","variables":{...}}` |

- **GReq**：reqName 在 path 末段，服务端按 reqName 从 allowlist 取预注册 query 执行。前置层（CF/nginx）看到的是 `/customer/core/greq/q_ai_scan_getById` 这样的具体路径，可直接分流。
- **GQL**：原始 raw query 入口，机制与行为完全不变；`allow-raw-query=false`（uat/prod）时仍拒绝 raw query。

> `x-api-name` header **已废弃删除**，不再支持、不做兼容（未上线）。

## 请求契约（前后端交接）

### GReq 请求

- **method**：`POST`
- **path**：`/customer/core/greq/{reqName}`
  - `{reqName}` 为整体单段，格式约定 `${q|m}_${namespace}_${resource}_${action}[Vn]`（2026-10-06 四段式定稿：namespace 目前=module，resource 可为聚合根），全局唯一，是 allowlist 的 key
  - 例：`/customer/core/greq/q_ai_scan_getById`、`/customer/core/greq/m_cs_feedback_createOne`
  - 不必等于 GraphQL 顶层 field name（允许组合/投影变体，如 `q_ai_scan_getByIdV2`）
  - **不展开**成 `/query/module/action` 多段——reqName 作为单个末段透传
- **body**：`{"query":"","variables":{...}}`
  - `query:""` 仅为**通过 Spring GraphQL HTTP transport 的「query 字段必须存在」校验**的占位，客户端 wire 上**没有真实 query**
  - 真实 query 由服务端按 path 中的 `{reqName}` 从 allowlist 提供
  - `variables` 照常由前端传
- **其余业务 header 不变**：`x-project-id`、`x-install-id`、`Authorization` 等照常传。

### 响应

与原 GraphQL 响应格式一致（`{data|errors}`）。未知 reqName / 缺 reqName 返回 `403000 FORBIDDEN`。

**HTTP 状态码**：有 error 时按 `errors[0].extensions.code` 前 3 位设 HTTP status（详见下方[HTTP 状态码映射](#http-状态码映射)）；无 errors → 200。

## 服务端机制（`infra/graphql/trusted/`）

```
POST /customer/core/greq/{reqName}
  body {query:"", variables}
        │
        ▼
  ReqNamePathInterceptor (WebGraphQlInterceptor)
    从 request.uri path 取 /greq/ 之后的末段 → reqName
    → configureExecutionInput 存入 GraphQLContext[trusted.reqName]
        │  (不改 body)
        ▼
  TrustedDocumentProvider (graphql-java PreparsedDocumentProvider)   ← 逻辑不变
    reqName = context[trusted.reqName]
    命中 allowlist → 返回预解析并缓存的 Document
    传了未知 reqName → 拒绝 (403000)
    未传 reqName（即走了 /gql 或 /greq 无后缀）:
      allow-raw-query=false(uat/prod) → 拒绝
      allow-raw-query=true(dev)       → 回退 body raw query (parseAndValidate)
        │
        ▼
  GraphQL 引擎执行（variables 来自 body）
```

### 组件

- **GReq 路由**：新增 `RouterFunction<ServerResponse>` bean（`GReqRouterConfig`），把 `POST /customer/core/greq/*` 映射到 Spring GraphQL 自动装配的同一个 `GraphQlHttpHandler`。因此 GReq 与 GQL 共用同一套 interceptor 链与执行引擎，仅入口路径不同。
- **ReqNamePathInterceptor**：从 `request.uri` 的 path 中解析 `/greq/` 之后的末段作为 reqName，存入 `GraphQLContext[trusted.reqName]`。不读 header、不改 body。走 `/gql` 的请求 path 里没有 `/greq/` 段 → reqName 为空 → 天然走 raw-query 分支。
- **TrustedDocumentProvider**：只从 context 取 reqName 查 allowlist。
- **PersistedQueryStore**：接口 + `ClasspathPersistedQueryStore`。启动加载 `classpath:graphql/persisted-queries/{bff}/`（当前只 customer），每条 query 预解析为 `Document` 缓存。接口预留 Redis 实现。

## 配置

```yaml
# application.yml（默认安全，uat/prod 继承）；trusted-documents 机制恒开
spring:
  graphql:
    path: /customer/core/gql          # GQL raw query 入口（保留）

graphql:
  trusted-documents:
    greq-path: /customer/core/greq     # GReq persisted query 入口前缀
    allow-raw-query: false             # 未带 reqName 时禁止 raw query
    allowlist-path: classpath:graphql/persisted-queries/customer/
```

```yaml
# application-local.yml（本地 API 探索）；机制同样开启，仅额外允许 raw query 兜底
graphql:
  trusted-documents:
    allow-raw-query: true
```

## allowlist 格式（`resources/graphql/persisted-queries/customer/*.json`）

```json
{
  "q_auth_session_me": "query q_auth_session_me { q_auth_me { user { id email } tier } }",
  "m_cs_feedback_createOne": "mutation m_cs_feedback_createOne($input: SubmitFeedbackInput!) { m_cs_submitFeedback(input: $input) { id } }"
}
```

key = reqName（2026-10-06 起四段式；39 个存量 reqName 已全量改名，总表见 `docs/design/proposals/rpc-rollout-client.md` §1），value = 完整 query 文本（其内引用的 GraphQL 顶层 field 名不变）。**由前端 build 时从 `graphql.ts` 的 query const 提取生成并提交进本 repo**。

## 前端改动（待做）

原来：`gqlOp` 发 `POST /customer/core/gql` + header `x-api-name: {reqName}` + body `{"query":"","variables":{...}}`。

改为：

1. **URL 拼 reqName**：`POST /customer/core/greq/${reqName}`，body 仍 `{"query":"","variables":{...}}`。
2. **删除 `x-api-name` header**：不再设置该 header。
3. 其余 header（`x-project-id` / `x-install-id` / `Authorization` 等）与 body 结构不变。
4. build 脚本继续从 `apps/shared/src/api/graphql.ts` 提取所有 named operation → 生成 `{reqName: query}` → 输出为 server 的 `customer/*.json`。
5. manifest 完整性校验（前端所有 op 都进 manifest，防遗漏导致 uat 404）。
6. 清理与 `graphql.ts` 漂移的孤儿 `graphql/*.graphql` 文件。

> reqName 需可安全放入 URL path 段。当前约定格式 `${q|m}_${namespace}_${resource}_${action}[Vn]`（四段式）只含 `[A-Za-z0-9_]`，无需额外 URL 编码。

## 前置层分流（CF / nginx）

reqName 进 path 后，前置层可对具体路径做规则，例如：

- 把重查询（如 `/customer/core/greq/q_ai_*`）路由到独立后端池
- 对某些 query path 做边缘缓存 / 限流 / WAF 规则
- 按 path 前缀统计 QPS

## HTTP 状态码映射

GraphQL over HTTP（legacy JSON transport）恒返回 200，业务错误码在 body 里。为让 CF/nginx/客户端能直接按 HTTP status 判断，`GraphQlHttpStatusFilter`（`infra/http/`）读 response body 的第一个 error：

```
errors[0].extensions.code  →  取前 3 位  →  HTTP status
"401000"                   →  401
"403000"                   →  403
"429001"                   →  429
（无 errors / 无 code / code 非法）→ 不改动，成功即 200
```

- `ErrorCode.externalCode` 本身即「前 3 位 = HTTP 语义」的 6 位编码（见 `infra/http/ErrorCode.kt`），取前 3 位即得正确 HTTP status。
- filter 仅作用于 GraphQL/GReq 端点（path 含 `/gql` 或 `/greq/`），@Order 外层于 `RequestLoggingFilter`。
- 多个 error 只取第一个。

## 日志

body 无真实 query，`RequestLoggingFilter` 记的 body 只有 variables，天然不含 query。reqName 现在在 URL path 中，日志的 `uri` 字段即可见（原 `x-api-name` header 已从 `LOGGED_HEADERS` 移除）。
