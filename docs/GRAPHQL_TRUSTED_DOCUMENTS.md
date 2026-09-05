# GraphQL Trusted Documents (Persisted Query Allowlist)

安全机制：生产环境只允许**预注册的 query** 执行，前端不发送 raw query，杜绝任意 query 攻击面。

## 请求契约（前后端交接）

- **header**：`x-api-name: <apiName>`，如 `q_ai_findMyScanById`
  - 格式约定 `${q|m}_${module}_${action}[Vn]`，全局唯一，是 allowlist 的 key
  - 不必等于 GraphQL 顶层 field name（允许组合/投影变体，如 `q_demo_findTodoAndCustomer`、`q_ai_findMyScanByIdV2`）
- **body**：`{"query":"","variables":{...}}`
  - `query:""` 仅为**通过 Spring GraphQL HTTP transport 的「query 字段必须存在」校验**的占位，客户端 wire 上**没有真实 query**
  - 真实 query 由服务端按 `x-api-name` 从 allowlist 提供
  - `variables` 照常由前端传

## 服务端机制（`infra/graphql/trusted/`）

```
POST /customer/core/gql
  header x-api-name, body {query:"", variables}
        │
        ▼
  ApiNameHeaderInterceptor (WebGraphQlInterceptor)
    读 x-api-name → configureExecutionInput 存入 GraphQLContext[trusted.apiName]
        │  (不改 body)
        ▼
  TrustedDocumentProvider (graphql-java PreparsedDocumentProvider)
    apiName = context[trusted.apiName]
    命中 allowlist → 返回预解析并缓存的 Document
    传了未知 apiName → 拒绝 (403000)
    未传 apiName:
      allow-raw-query=false(uat/prod) → 拒绝 "x-api-name header is required"
      allow-raw-query=true(dev)       → 回退 body raw query (parseAndValidate)，供 GraphiQL/API 探索
        │
        ▼
  GraphQL 引擎执行（variables 来自 body）
```

- **PersistedQueryStore**：接口 + `ClasspathPersistedQueryStore`。启动加载 `classpath:graphql/persisted-queries/{bff}.json`（当前只 customer），每条 query 预解析为 `Document` 缓存。接口预留 Redis 实现。
- **DGS 集成**：DGS 的默认 `preparsedDocumentProvider` bean 标注 `@ConditionalOnMissingBean`；本项目提供自定义 `PreparsedDocumentProvider` bean 后 DGS 自动退让，经其 `sourceBuilderCustomizer` 注入。无需自写 `GraphQlSourceBuilderCustomizer`。

## 配置

```yaml
# application.yml（默认安全，uat/prod 继承）；trusted-documents 机制恒开
graphql:
  trusted-documents:
    allow-raw-query: false   # 禁止无 x-api-name 的 raw query
    allowlist-path: classpath:graphql/persisted-queries/
```

```yaml
# application-local.yml（本地 API 探索）；机制同样开启，仅额外允许 raw query 兜底
graphql:
  trusted-documents:
    allow-raw-query: true
```

## allowlist 格式（`resources/graphql/persisted-queries/customer.json`）

```json
{
  "q_auth_me": "query q_auth_me { q_auth_me { user { id email } tier } }",
  "m_cs_submitFeedback": "mutation m_cs_submitFeedback($input: SubmitFeedbackInput!) { m_cs_submitFeedback(input: $input) { id } }"
}
```

key = apiName，value = 完整 query 文本。**由前端 build 时从 `graphql.ts` 的 query const 提取生成并提交进本 repo**（阶段 B/C）。

## 前端集成（阶段 B/C，待做）

1. build 脚本从 `apps/shared/src/api/graphql.ts` 提取所有 named operation → 生成 `{apiName: query}` → 输出为 server 的 `customer.json`。
2. manifest 完整性校验（前端所有 op 都进 manifest，防遗漏导致 uat 404）。
3. `gqlOp` 改为发 `{"query":"","variables":{...}}` + header `x-api-name`，不发真实 query。
4. 清理与 `graphql.ts` 漂移的孤儿 `graphql/*.graphql` 文件。

## 日志

body 无真实 query，`RequestLoggingFilter` 记的 body 只有 variables，天然不含 query。
