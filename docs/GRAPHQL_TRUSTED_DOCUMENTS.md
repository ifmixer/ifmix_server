# GraphQL Trusted Documents (Persisted Query Allowlist)

安全机制：生产环境只允许**预注册的 query** 执行，前端不发送 raw query，杜绝任意 query 攻击面。

本次改动：apqName 从 **`x-api-name` header 迁移到 URL path**，以便 Cloudflare / nginx 等前置层能按具体 query 分流、缓存、限流、加 WAF 规则。

## 两个 endpoint（并存，职责分开）

| endpoint | 路径 | 用途 | body |
|----------|------|------|------|
| **APQ**（persisted query） | `POST /customer/core/apq/{apqName}` | 生产主入口，前端只走这里 | `{"query":"","variables":{...}}` |
| **GQL**（raw query） | `POST /customer/core/gql` | GraphiQL / 本地 API 探索，**保留不动** | `{"query":"...","variables":{...}}` |

- **APQ**：apqName 在 path 末段，服务端按 apqName 从 allowlist 取预注册 query 执行。前置层（CF/nginx）看到的是 `/customer/core/apq/q_ai_findMyScanById` 这样的具体路径，可直接分流。
- **GQL**：原始 raw query 入口，机制与行为完全不变；`allow-raw-query=false`（uat/prod）时仍拒绝 raw query。

> `x-api-name` header **已废弃删除**，不再支持、不做兼容（未上线）。

## 请求契约（前后端交接）

### APQ 请求

- **method**：`POST`
- **path**：`/customer/core/apq/{apqName}`
  - `{apqName}` 为整体单段，格式约定 `${q|m}_${module}_${action}[Vn]`，全局唯一，是 allowlist 的 key
  - 例：`/customer/core/apq/q_ai_findMyScanById`、`/customer/core/apq/m_cs_submitFeedback`
  - 不必等于 GraphQL 顶层 field name（允许组合/投影变体，如 `q_demo_findTodoAndCustomer`、`q_ai_findMyScanByIdV2`）
  - **不展开**成 `/query/module/action` 多段——apqName 作为单个末段透传
- **body**：`{"query":"","variables":{...}}`
  - `query:""` 仅为**通过 Spring GraphQL HTTP transport 的「query 字段必须存在」校验**的占位，客户端 wire 上**没有真实 query**
  - 真实 query 由服务端按 path 中的 `{apqName}` 从 allowlist 提供
  - `variables` 照常由前端传
- **其余业务 header 不变**：`x-project-id`、`x-install-id`、`Authorization` 等照常传。

### 响应

与原 GraphQL 响应格式一致（`{data|errors}`）。未知 apqName / 缺 apqName 返回 `403000 FORBIDDEN`。

## 服务端机制（`infra/graphql/trusted/`）

```
POST /customer/core/apq/{apqName}
  body {query:"", variables}
        │
        ▼
  ApqNamePathInterceptor (WebGraphQlInterceptor)
    从 request.uri path 取 /apq/ 之后的末段 → apqName
    → configureExecutionInput 存入 GraphQLContext[trusted.apqName]
        │  (不改 body)
        ▼
  TrustedDocumentProvider (graphql-java PreparsedDocumentProvider)   ← 逻辑不变
    apqName = context[trusted.apqName]
    命中 allowlist → 返回预解析并缓存的 Document
    传了未知 apqName → 拒绝 (403000)
    未传 apqName（即走了 /gql 或 /apq 无后缀）:
      allow-raw-query=false(uat/prod) → 拒绝
      allow-raw-query=true(dev)       → 回退 body raw query (parseAndValidate)
        │
        ▼
  GraphQL 引擎执行（variables 来自 body）
```

### 组件

- **APQ 路由**：新增 `RouterFunction<ServerResponse>` bean（`ApqRouterConfig`），把 `POST /customer/core/apq/*` 映射到 Spring GraphQL 自动装配的同一个 `GraphQlHttpHandler`。因此 APQ 与 GQL 共用同一套 interceptor 链与执行引擎，仅入口路径不同。
- **ApqNamePathInterceptor**：从 `request.uri` 的 path 中解析 `/apq/` 之后的末段作为 apqName，存入 `GraphQLContext[trusted.apqName]`。不读 header、不改 body。走 `/gql` 的请求 path 里没有 `/apq/` 段 → apqName 为空 → 天然走 raw-query 分支。
- **TrustedDocumentProvider**：只从 context 取 apqName 查 allowlist。
- **PersistedQueryStore**：接口 + `ClasspathPersistedQueryStore`。启动加载 `classpath:graphql/persisted-queries/{bff}/`（当前只 customer），每条 query 预解析为 `Document` 缓存。接口预留 Redis 实现。

## 配置

```yaml
# application.yml（默认安全，uat/prod 继承）；trusted-documents 机制恒开
spring:
  graphql:
    path: /customer/core/gql          # GQL raw query 入口（保留）

graphql:
  trusted-documents:
    apq-path: /customer/core/apq       # APQ persisted query 入口前缀
    allow-raw-query: false             # 未带 apqName 时禁止 raw query
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
  "q_auth_me": "query q_auth_me { q_auth_me { user { id email } tier } }",
  "m_cs_submitFeedback": "mutation m_cs_submitFeedback($input: SubmitFeedbackInput!) { m_cs_submitFeedback(input: $input) { id } }"
}
```

key = apqName，value = 完整 query 文本。**由前端 build 时从 `graphql.ts` 的 query const 提取生成并提交进本 repo**。

## 前端改动（待做）

原来：`gqlOp` 发 `POST /customer/core/gql` + header `x-api-name: {apqName}` + body `{"query":"","variables":{...}}`。

改为：

1. **URL 拼 apqName**：`POST /customer/core/apq/${apqName}`，body 仍 `{"query":"","variables":{...}}`。
2. **删除 `x-api-name` header**：不再设置该 header。
3. 其余 header（`x-project-id` / `x-install-id` / `Authorization` 等）与 body 结构不变。
4. build 脚本继续从 `apps/shared/src/api/graphql.ts` 提取所有 named operation → 生成 `{apqName: query}` → 输出为 server 的 `customer/*.json`。
5. manifest 完整性校验（前端所有 op 都进 manifest，防遗漏导致 uat 404）。
6. 清理与 `graphql.ts` 漂移的孤儿 `graphql/*.graphql` 文件。

> apqName 需可安全放入 URL path 段。当前约定格式 `${q|m}_${module}_${action}[Vn]` 只含 `[A-Za-z0-9_]`，无需额外 URL 编码。

## 前置层分流（CF / nginx）

apqName 进 path 后，前置层可对具体路径做规则，例如：

- 把重查询（如 `/customer/core/apq/q_ai_*`）路由到独立后端池
- 对某些 query path 做边缘缓存 / 限流 / WAF 规则
- 按 path 前缀统计 QPS

## 日志

body 无真实 query，`RequestLoggingFilter` 记的 body 只有 variables，天然不含 query。apqName 现在在 URL path 中，日志的 `uri` 字段即可见（原 `x-api-name` header 已从 `LOGGED_HEADERS` 移除）。
