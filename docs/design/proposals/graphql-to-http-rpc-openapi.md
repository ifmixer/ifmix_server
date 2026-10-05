# GraphQL 迁移 HTTP RPC + OpenAPI 实施计划

> **状态：未实施（proposal）** —— 2026-09-30 制定，截至 2026-10-05 未开始迁移；移动端上线前若决定执行，按阶段直接实现。执行时遵守 `AGENTS.md` 分层与 GitNexus 规则。
>
> **2026-10-06 讨论定稿**：去 GraphQL 的决策依据、请求信封 `{meta, input}` 与命名（§一）、header 留守原则与限流职责边界（§一）、与 wire v3 的合并执行顺序（§六）。
>
> **试点实施**：demo 模块作为首个迁移对象，实现任务已拆分给前后端 agent——服务端见 [rpc-pilot-server](rpc-pilot-server.md)、客户端见 [rpc-pilot-client](rpc-pilot-client.md)（试点通过 review 前不迁移其他模块、不删 GraphQL）。URL 决策：不带 resourceId，统一 `POST /customer/core/rpc/{reqName}`；DTO 分界规则见 §一「DTO 与转换」。

**目标：** 在移动端上线前，将 `core-api` 的 34 个 DGS GraphQL action 迁移为 HTTP RPC，并以 OpenAPI/Swagger 作为唯一移动端 API 契约。

**架构：** 使用 `POST /customer/core/rpc/{reqName}`，保留现有 `q_`/`m_` reqName。Controller 负责路由、按 action 校验 header、构造 `ActionContext`、开启必要事务和包装 `Envelope`；复杂读取由 `XxxQueryService` 批量查询并聚合；现有 Facade → Handler → Repository 分层继续保留。

**技术栈：** Kotlin / Spring MVC / springdoc-openapi / Jimmer DTO + Fetcher / PostgreSQL / Redis / JUnit 5。

---

## 一、已确定的设计

### 去 GraphQL 的决策依据（2026-10-06 讨论补充）

- Persisted documents（trusted documents）模式下客户端未使用字段级灵活性，GraphQL 事实上已是"每个 query 一个固定 RPC"，只是外面套了一层执行引擎（schema 解析、validation、DataLoader 调度、DGS codegen、trusted-document manifest 流程），全部在为用不到的灵活性付费。
- 迁移不是新增聚合成本：DataLoader 本来就是手写的（如 Todo DataLoader），GraphQL 只提供自动 dispatch。迁移把它变成"手写 batch + 显式组装"，净复杂度下降，且消除 N+1 的隐蔽失效风险。
- 多服务器网关聚合场景目前不存在（`core-job` 走共享 PG 协作，无服务间 API）。GraphQL federation 的价值区间是"多后端服务 + 多团队 + 多种客户端"，若未来真的出现多服务，按规模递进处理且不影响客户端 RPC 契约：路径分流（CF/nginx）→ 服务端手写聚合（与 `XxxQueryService` 同一模式）→ 真到几十服务多团队再评估 federation（放在 RPC 后面，客户端契约不变）。

### API 契约

- 所有移动端 action 使用 `POST /customer/core/rpc/{reqName}`。
- **URL 不带 resourceId**（2026-10-06 定稿）：边缘/WAF 规则是模式级的，action 粒度由 reqName 承载，具体资源 ID 是攻击者可轮换的高基数字段、对边缘决策无价值；且 ID 属业务载荷，应留在加密 body 内。路径统一一种形状，ActionSpec 路由/测试/OpenAPI 均单套处理。
- reqName 保持现状，例如 `q_ai_findMyScans`、`m_auth_login`，减少前后端重命名成本。
- 成功和失败统一返回 `Envelope<T> = { code, msg, data }`。
- HTTP status 必须等于 `code` 前三位；成功为 `200000` / HTTP 200。
- 不再返回 GraphQL 的 `data + errors`、partial error 或 `errors[0]`。
- Swagger 的 `operationId` 使用 reqName，前端从 OpenAPI 生成客户端。
- URL 的 `customer` 受众段保留：token type 已含 manager(20)，将来管理端 API 用 `/manager/rpc/…` 独立命名空间，CF 规则与源站路由可按受众切分。

### 请求信封与 meta（2026-10-06 定稿）

- 加密前的请求 JSON 载荷统一为 `{"meta": {…}, "input": {…}}` 两段结构：`input` 为业务入参（每个 action 的 `XxxRequest`），`meta` 为非业务语义的请求属性（所有 action 共用的 `RequestMeta` OpenAPI 组件，不进各 action 的类型）。
- 不沿用 GraphQL 的 `variables`：那是"模板替换参数"的词汇，RPC 没有 query 模板，载荷本身就是请求；客户端从 OpenAPI 重新生成，保留旧名零收益。命名采用业界通行的 `meta`（gRPC metadata、JSON:API 先例）；不用 `header`（与 HTTP header 纠缠）、不用 `context`（与服务端 ActionContext 冲突，两层必须不同名）。
- 响应维持 `Envelope{code, msg, data}`，`data` 为各 action 的 `XxxResponse`；Envelope 预留 `meta` 字段暂不启用，首个候选是 `serverTime`（客户端对时，wire §9 ts 时效决策的前置条件）。
- meta 在 Kotlin 侧为 typed `RequestMeta` data class，不做 `Map<String, String>` 透传；字段全可空，必填性由 `ActionSpec` 按 action 声明；新增字段属协议变更，走设计评审。

**RequestMeta 字段清单**（2026-10-06 讨论定稿）：

| 字段 | 说明 |
|---|---|
| `reqId` | 客户端自供、缺省服务端补 UUID（对齐现有 `x-req-id` 语义）；与 CF 自动注入的 `cf-ray` 互补——后者边缘可见，负责 CF↔源站日志关联，服务端日志两者都打 |
| `projectId` | 客户端声明操作哪个 project，服务端校验访问权 |
| `accessToken` | 唯一凭证字段，纯 token 字符串（不带 `Bearer` 前缀）；type claim 自描述 install(5)/customer(10)/manager(20)，沿用现有单 token 模型（拆 install/customer 两个字段会与 type claim 形成双信源，且覆盖不了第三种类型） |
| `locale` / `currency` / `country` | 用户偏好；平铺，不加前缀不嵌套（typed 对象本身就是命名空间）。`meta.country` 是用户设置，与 CF 从 IP 推导的 `cf-ipcountry`（header）并存不冗余 |
| `userTz` | 可选，IANA 时区名（如 `Asia/Shanghai`），不用 float offset（丢 DST 规则且 offset 不唯一定位时区）；无具体服务端消费方（如通知按本地时间发送）前可不实现 |
| `appVersion` / `otaVersion` / `clientPlatform` / `deviceModel` / `osVersion` | 遥测/诊断；服务端逻辑与分析以这些结构化字段为权威信源，UA 头只作边缘 advisory，不解析 UA 字符串 |

明确**不进 meta** 的：

- `installId`：服务端从 token 的 `iid` claim 解出（RequestParser 现状），客户端声称的身份不是身份；服务端解出后自打日志。
- `refreshToken`：维持现状走 `m_auth_refreshToken` / `m_auth_logout` 的业务 input——全 API 只有两个 action 消费它，放进 meta 等于每次请求多带一份长期凭证，徒增暴露面。
- `ts`：wire v3 明文头部已有 `ts_ms`（仅遥测）。
- 签名/校验和：AEAD 已保证完整性。

**meta 与 ActionContext 是两个层次，安全属性不同**：

- `meta` = 客户端自供、未认证的原料（信封上写的地址）；
- `ActionContext` = `ActionContextFactory` 消费 meta + 信封外 header + 路径，解析验证后的产物（服务端核对后的收件人）——actor 类型、customerId、installId、project、locale/currency、数据访问策略。
- customerId/installId 等身份字段不出现在 meta（客户端声称的身份不是身份，见 wire-encryption.md §9 结论）；业务代码只读 ActionContext。

meta 字段对未来的边缘网关**不可见，且这是设计使然**（wire 私钥永不下发中间层）：需要解密前做的决策走 header advisory，可信决策一律在服务端解密后做；将来网关若确需某 meta 字段做路由/降级，按 header 留守原则加明文 advisory 头副本。

### header 留守原则与限流职责边界（2026-10-06 定稿）

header 只保留以下几类，其余全部进 meta：

1. **信封标记**：`x-wirep-version`、`Content-Type`——服务端须先看到才知道要解密（鸡生蛋）。版本头定名 `x-wirep-version`（wire protocol）：它标记整个信封协议的版本（加密 + 信封格式），不只是加密算法；v2 未发布，由 `x-proto-version` 改名零成本；缺省/无头 = 明文 v1。
2. **边缘注入信号**：CF 注入的真实 IP、`cf-ray`、bot-score、ipcountry——边缘限流与风控的依据。
3. **标准 `User-Agent`**：CF bot 检测消费；可选塞紧凑摘要（如 `ifmix/1.0.6 (iOS 18.2; iPhone15,3)`），只作 advisory，服务端不解析（结构化信源在 meta）。
4. **按需后加的 advisory 提示字段**：仅当出现"解密前必须决策、且可容忍伪造"的具体需求（如按 app version 拦极老版本）时再加。低基数、明文可伪造、只作边缘卫生规则，服务端不得采信。

业务语义字段（Authorization、project_id、country、locale）一律进 meta，依据：

- 边缘（CF/WAF）做安全决策的信号——IP、路径、TLS 指纹、bot-score——header 进 body 后一个不少。wire 加密上线时 body 对 WAF 已不可见，header 进 body 的边际损失为零。
- country 由 CF 从 IP 推导（ipcountry），客户端上报版本可伪造且冗余，不进 header。
- Authorization 不为 WAF 留守：边缘不持有验签密钥，token 对它是攻击者可控的不透明字符串，按 token 分桶限流是高基数陷阱（随机假 token 可打爆限流器基数或稀释额度）；且攻击者可批量注册账号轮换 token，per-token 边缘限流对真实威胁（attest 后的真机农场）无效。token 相关决策全部发生在解密后；header **存在性**检查同样可被塞垃圾值绕过，无价值。

**限流职责分工**：边缘限 IP（解密前，信号全在 header），服务端限身份/项目（解密后，锚点 customerId/projectId 读自 ActionContext）。两层各用各的锚点，互不缺信号。

### 分层与命名

```text
XxxController
  ├─ command → 现有 XxxFacade / GlobalTxRunner
  └─ query   → XxxQueryService → Facade → Handler → Repository
```

- Controller 包按 URL 受众段镜像：`bff/rpc/customer/{module}/`；`bff/rpc/manager/{module}/` 预留（token type 已含 manager）。公共的 ActionContextFactory/Envelope/异常处理留在 `infra/http/`，不下放受众包。
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

分界规则（2026-10-06 定稿）：**字段照抄实体的用 Jimmer DTO 语言，其余手写**——Jimmer DTO 的收益是省字段搬运样板，代价是类型与实体结构耦合；耦合在"字段几乎照抄实体"时无害（实体演进带动契约，由 OpenAPI snapshot 捕获），在"对外契约需独立演进"时是纯负担。

- 用 Jimmer DTO language：单实体读视图 `XxxDto` / `XxxDetailDto`（含边界稳定的关联抓取）、简单 create input（实体字段子集、无部分更新语义）。
- 手写 data class：update input（`set` + `unsetFields` 部分更新语义）、多源聚合 response（页面级 DTO）、协议类型（`RequestMeta`、`Envelope`——不是任何实体的投影）、外部服务 request/response、跨模块响应（逻辑外键、不与单一实体绑定）。
- 默认不引入 Konvert：Jimmer 生成 DTO 已自带 `Entity -> DTO` 构造器和 `toEntity()`，再加 Konvert 属于重复能力。

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
3. 请求体从一开始定义为 `{"meta": {}, "input": {}}` 结构：meta 初期只放遥测字段（requestId / client version 等），敏感字段仍在 header；wire v3 落地后由 meta 接管（§六）。`ActionContextFactory` 统一从 meta + header 两处读取，避免协议形状二次变更。
4. 拆分 Redis 读与回填策略；修复 `loadMany` 返回顺序，使结果严格按传入 ID 顺序排列。
5. 增加错误码前缀与 HTTP status 一致性测试。
6. 增加最小 RPC 测试端点或测试 Controller，验证 header/meta → context → envelope → exception handler 全链路。

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

1. 为所有 RPC action 设置唯一 operationId、request/response schema 和认证 header 与 meta 字段描述。
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
- 更新 `AGENTS.md`、`docs/guide/ARCHITECTURE.md`、`docs/guide/CODING_GUIDE.md` 和 trusted-document 文档索引。
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
- `{meta, input}` 信封、`RequestMeta` 类型与 ActionContext 解析有合约测试；敏感 header 读取在 meta 接管后删除（§六第 3 步）。
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

---

## 六、与 wire v3 的合并执行顺序（2026-10-06 定稿）

去 GraphQL、wire v3（HPKE）、meta 信封本质是**一次协议定稿**，不是三个独立演进。执行顺序：

1. **RPC 迁移**（本文件阶段 1–6）：请求体已采用 `{meta, input}` 结构，meta 仅放遥测字段；auth 等敏感信息暂留 header。
2. **wire v3**（wire-encryption.md §10）：与协议形态无关（只关心 octet-stream 信封，不关心里面是 GraphQL 还是 RPC），可与步骤 1 并行实施。
3. **meta 接管敏感字段**：客户端与服务端同版本切换，token 等敏感字段移入 meta，服务端删除敏感 header 读取——不做长期并存（与 v3 直接取代 v2 的决策一致，均无线上兼容负担）。如需灰度，可临时并存读取 header，客户端全量后删除。
4. **删 GraphQL**（阶段 7）。

依据：v2 从未上线、v3 直接取代、明确不做线上兼容——若按 wire 文档原 v2.1 方案（基于 GraphQL payload 的 `{meta, query, variables}`）先实现一遍，RPC 落地后即成一次性中间产物。meta-in-body 直接定义在 RPC 协议上，避免同一机制实现两遍。

**文档同步（待办）**：wire-encryption.md §9「已规划：v2.1 header 进 body」需改写为「由 RPC 协议承接，meta 定义见本文件 §一」；header 留守原则与限流职责分工也应回写至该文档 §9；版本头 `x-proto-version` → `x-wirep-version` 改名涉及该文档全文引用与 `RequestHeaders.PROTO_VERSION` 常量。