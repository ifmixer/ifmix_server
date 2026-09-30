# GraphQL 迁移 HTTP RPC + OpenAPI 实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 分阶段实现本计划。

**目标：** 在移动端上线前，将 `core-api` 的 34 个 DGS GraphQL action 迁移为 HTTP RPC，并以 OpenAPI/Swagger 作为唯一移动端 API 契约。

**架构：** 使用 `POST /customer/core/rpc/{reqName}`，保留现有 `q_`/`m_` reqName。Controller 负责路由、按 action 校验 header、构造 `ActionContext`、开启必要事务和包装 `Envelope`；复杂读取由 `XxxQueryService` 批量查询并聚合；现有 Facade → Handler → Repository 分层继续保留。

**技术栈：** Kotlin / Spring MVC / springdoc-openapi / Jimmer DTO + Fetcher / PostgreSQL / Redis / JUnit 5。

---

## 一、已确定的设计

### API 契约

- 所有移动端 action 使用 `POST /customer/core/rpc/{reqName}`。
- reqName 保持现状，例如 `q_ai_findMyScans`、`m_auth_login`，减少前后端重命名成本。
- 成功和失败统一返回 `Envelope<T> = { code, msg, data }`。
- HTTP status 必须等于 `code` 前三位；成功为 `200000` / HTTP 200。
- 不再返回 GraphQL 的 `data + errors`、partial error 或 `errors[0]`。
- Swagger 的 `operationId` 使用 reqName，前端从 OpenAPI 生成客户端。

### 分层与命名

```text
XxxController
  ├─ command → 现有 XxxFacade / GlobalTxRunner
  └─ query   → XxxQueryService → Facade → Handler → Repository
```

- 聚合层命名为 `XxxQueryService`，不继续叫 Fetcher：`Fetcher` 已同时被 DGS DataFetcher 和 Jimmer Fetcher 使用，继续复用会造成歧义。
- `XxxQueryService` 只处理读取、批量加载和响应聚合，不承担 mutation。
- Controller、QueryService 都不能直接访问 Repository；跨模块聚合只调用模块 Facade。
- 单一简单查询可由 Controller 直接调用 Facade，不强制经过 QueryService。

### ActionContext 与数据访问策略

- 新增协议无关的 `ActionContextFactory`，直接从 `HttpServletRequest` 和 action spec 构造上下文。
- 每个 endpoint 用服务端 `ActionSpec` 声明：是否需要 project、actor 类型、locale/country/currency，以及 query/mutation 属性。
- 数据访问策略放在服务端上下文中：
  - `preferReader`：优先 reader 或强制 writer；
  - `cacheRead`：是否允许读取 Redis；
  - `cachePopulate`：DB 回源后是否允许回填 Redis。
- 这些字段不允许由客户端 body/header 控制，避免客户端绕过一致性策略或制造缓存压力。
- Mutation 默认 writer + 不读缓存 + 不回填缓存；普通 Query 默认 reader + 读缓存 + 回填缓存；写后立即读使用 writer。

### DTO 与转换

- Entity 视图和常规 create input 优先使用 Jimmer DTO language，统一命名 `XxxDto`、`XxxDetailDto`、`CreateXxxInput`。
- `set + unsetFields`、多源聚合 response、外部服务 request 等不适合 Jimmer DTO 的结构继续手写 Kotlin data class。
- 默认不引入 Konvert：Jimmer 生成 DTO 已自带 `Entity -> DTO` 构造器和 `toEntity()`，再加 Konvert 属于重复能力。
- 多实体聚合使用小型显式 mapper/extension；只有迁移完成后确认存在大量纯字段搬运样板，才单独评估 Konvert。

### Jimmer 关联与聚合

- 已声明且边界稳定的同模块关联可以使用 Jimmer association + Fetcher/DTO 抓取。
- 没有 Jimmer 关联的逻辑外键不能假装自动抓取；Todo/TodoItem、ScanRecord/DeepResearch、跨模块 customerId 等仍先使用 `findByIds`/`findByXxxIds` 批量加载。
- 不为了迁移协议一次性把所有逻辑外键改成 ORM 关联。只有确认属于同一模块、同一生命周期且不会破坏 project/owner 过滤时才增加关联。
- 分页聚合始终先查询根 page，再收集 IDs 批量查询关联，最后按 Map 组装，禁止循环 `findById`。

---

## 二、实施阶段

### 阶段 1：建立 RPC 基础设施

主要修改：

- `infra/http/ActionContext.kt`
- 新增 `infra/http/ActionContextFactory.kt`、`ActionSpec.kt`
- `infra/auth/RequestParser.kt`
- `infra/http/Envelope.kt`、`GlobalExceptionHandler.kt`
- `infra/db/ModuleCtxFactory.kt`
- `infra/redis/CacheAside.kt`、`infra/service/CrudServiceOps.kt`

工作内容：

1. 将 `preferReader/cacheRead/cachePopulate` 明确建模到 ActionContext。
2. 新增 HTTP ActionContextFactory，覆盖匿名、customer actor、可选 header 和写后读场景。
3. 拆分 Redis 读与回填策略；修复 `loadMany` 返回顺序，使结果严格按传入 ID 顺序排列。
4. 增加错误码前缀与 HTTP status 一致性测试。
5. 增加最小 RPC 测试端点或测试 Controller，验证 header → context → envelope → exception handler 全链路。

验收：RPC 基础设施测试通过，现有 GraphQL 暂时仍可编译运行。

### 阶段 2：解除业务层对 GraphQL generated types 的依赖

主要范围：

- `infra/repo/FilterGroupResolver.kt`
- `infra/repo/CrudRepoTemplate.kt`
- `modules/ai/**`
- `modules/demo/**`
- `modules/media/**`
- `entity/demo/TodoRecommend.kt`
- 新增 `src/main/dto/**/*.dto` 与必要的手写 `dto/**/*.kt`

工作内容：

1. 将 CommonFindOptions、FilterGroup、update set/unset、AI/media input 从 DGS generated types 迁到协议无关 DTO。
2. 为稳定实体视图新增 Jimmer DTO 定义，并验证 KSP 生成类型和 OpenAPI schema。
3. 保持 unset 优先于 set、枚举 Int 透传、DateTime ISO-8601 等现有语义。
4. 编译确认 Facade/Handler/Repository 不再 import `com.ifmix.core.api.generated.types`。

验收：业务层不依赖 GraphQL codegen；GraphQL Fetcher 如需过渡，只在 BFF 层做旧 input 到新 DTO 的转换。

### 阶段 3：迁移低风险模块，固化 Controller 模式

按以下顺序迁移：

1. media：presign upload/download；
2. cs：feedback/support request；
3. demo：Todo CRUD、分页和 items/count 聚合。

工作内容：

- 在 `bff/rpc/customer/{module}/` 新增 Controller。
- 每个方法使用原 reqName 路径和 OpenAPI operationId。
- Mutation 保留当前 `GlobalTxRunner` 边界。
- 新增 `DemoQueryService`，用 Jimmer 关联或 batch-by-ids 替代 Todo DataLoader。
- 为每个 action 增加 MockMvc/WebTestClient 合约测试，校验 HTTP status、Envelope、header 和 response JSON。

验收：这三个模块 RPC 功能与 persisted document 的固定响应形状一致，且无 N+1。

### 阶段 4：迁移身份与支付链路

迁移模块：install、customer、auth、pay。

重点：

- login/refresh/createAnonymous/createInstall 继续允许匿名或 install token，其余 action 保持 customer actor 要求。
- login、refresh、logout、createAnonymous、createInstall/updateInstall 保留现有全局事务。
- Customer 创建的 IP 限流、install/customer 绑定、token iid、账号合并行为不得改变。
- IAP verify 和 webhook 继续使用各自现有事务与验签路径；webhook URL 不改。
- 增加认证失败、token expired、actor type、跨 project token 和事务回滚测试。

验收：完整身份引导和支付验证通过 RPC E2E；现有 webhook 测试继续通过。

### 阶段 5：迁移 AI 与复杂聚合

新增 `AiController`、`AiQueryService`，迁移 scan、deep research、collection actions。

必须保留：

- createScan：AI 调用在事务外，保存结果在事务内；
- runDeepResearch：图片更新独立事务 → AI 调用事务外 → 成功结果写入事务；
- updateScan 写后需要返回详情时从 writer 读取；
- collection item 页面先分页 item，再批量加载 ScanRecord/DeepResearch；
- 列表视图不读取不需要的 JSONB 大字段。

响应采用固定 DTO，不建设任意 `include` DSL。确有昂贵可选字段时，只增加 endpoint-specific 布尔字段或 `SUMMARY/DETAIL` 枚举。

验收：AI 成功、部分成功、不可用、配额、限流、事务拆分和 collection 聚合测试全部通过。

### 阶段 6：OpenAPI 契约与前端接入

1. 为所有 RPC action 设置唯一 operationId、request/response schema 和认证/header 描述。
2. 本地启用 `/core/api-docs/json` 和 Swagger UI。
3. 增加 OpenAPI JSON smoke/snapshot 检查：34 个 reqName 均存在且无重复 operationId。
4. 移动端从 OpenAPI 生成客户端，并删除 trusted-document manifest 生成流程。
5. 做一次 GraphQL 与 RPC 固定用例的差异测试，确认字段、null、错误码和分页游标一致。

### 阶段 7：删除 GraphQL

在所有 RPC 验收完成后一次性删除：

- `bff/graphql/**`
- `infra/graphql/**`
- `resources/schema/**`
- `resources/graphql/persisted-queries/**`
- trusted-document 和 GraphQlHttpStatusFilter 测试
- DGS starter、DGS codegen、GraphQL client 依赖和 Gradle codegen 配置
- application.yml 中 GraphQL/DGS/GraphiQL/trusted-document 配置

同时：

- 确认源码中不存在 `com.netflix.graphql`、`generated.types`、`/greq/`。
- 更新 `AGENTS.md`、`docs/ARCHITECTURE.md`、`docs/CODING_GUIDE.md` 和 trusted-document 文档索引。
- 运行 GitNexus `detect-changes --scope all`，检查结果不得为 partial/truncated。

---

## 三、测试与验收

每个阶段至少运行：

```bash
./gradlew :core-api:compileKotlin
./gradlew :core-api:test
```

最终验收：

- 34 个 action 均可通过 `/customer/core/rpc/{reqName}` 调用。
- Swagger/OpenAPI 完整列出所有 action、header、request、Envelope response 和错误状态。
- HTTP status 与六位 code 前三位一致。
- 业务层无 GraphQL generated type。
- 所有 mutation 事务边界与迁移前一致，AI 外部 IO 不进入 DB 事务。
- 聚合查询无循环 `findById`，批量结果顺序稳定。
- GraphQL/DGS/trusted documents 依赖与代码全部删除。

## 四、主要风险

- `ActionContext` 为 HIGH 风险核心类型（GitNexus：75 upstream / 24 direct），必须兼容式演进，不能与删除 GraphQL 同一步完成。
- Spring/DGS 注解调用在静态图中显示 UNKNOWN，删除前必须同时核对 Fetcher 注解、schema、persisted query 和端到端测试清单。
- Jimmer association 只能加载真实声明的关系；逻辑外键仍需批量查询。
- CacheAside 当前批量命中结果不保证输入顺序，RPC 聚合启用缓存前必须先修复。
- 全量改 ORM 关系、引入通用 include、引入 Konvert 都不属于本次迁移必需范围。

## 五、建议提交边界

建议每个阶段独立提交：RPC infra、协议 DTO、media/cs/demo、auth/install/customer/pay、AI、OpenAPI/front-end、删除 GraphQL、文档。任何阶段失败都可以在尚未删除 GraphQL 前回退到上一提交。