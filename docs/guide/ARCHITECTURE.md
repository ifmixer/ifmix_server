# ifmix_server 架构文档

> 最后更新: 2026-09-03

## 项目概述

面向移动端（iOS/Android）的后端 API 服务：古物扫描 + AI 图像识别 + 收藏管理 + 社交登录 + IAP。

## 技术栈

| 层级 | 选型 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.10 |
| 运行时 | JDK 25 (Virtual Threads) | — |
| 框架 | Spring Boot | 4.1.0 |
| API | HTTP RPC（`POST /api/customer/core/{actionName}` + springdoc OpenAPI） | — |
| ORM | Jimmer (KSP) | 0.11.5 |
| 数据库 | PostgreSQL (读写分离) | — |
| 缓存 | Redis + CacheAside | — |
| 对象存储 | S3 兼容 (AWS/R2/MinIO) | — |
| AI | Spring AI 2.0 (OpenAI-compatible) | — |
| 认证 | EdDSA(Ed25519) JWT + IDP OAuth2 | — |
| 构建 | Gradle 9.6.1 + KSP | — |
| 序列化 | Jackson 3 (tools.jackson) | — |

## 分层架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  BFF — HTTP RPC Controller + REST                                    │
│  POST /api/customer/core/{actionName}  (主 API · {meta, input} 信封) │
│  POST /webhooks/iap/*     (Apple/Google 回调)                        │
│  GET  /.well-known/jwks                                              │
├─────────────────────────────────────────────────────────────────────┤
│  Facade Layer (modules/*/XxxFacade.kt)                               │
│  构造 ModuleCtx + 简单转发 · 不含事务逻辑                           │
├─────────────────────────────────────────────────────────────────────┤
│  Handler Layer (modules/*/handler/XxxAggHandler.kt)                  │
│  纯业务逻辑 · 接收 ModuleCtx · 不注入 TxRunner                      │
├─────────────────────────────────────────────────────────────────────┤
│  Repository Layer (modules/*/repo/)                                   │
│  纯数据访问 · CrudRepoTemplate 组合 · 接收 ModuleCtx                │
├─────────────────────────────────────────────────────────────────────┤
│  Model (entity/)                                                      │
│  Jimmer interface + @MappedSuperclass · KSP 生成扩展属性             │
├─────────────────────────────────────────────────────────────────────┤
│  Infra (infra/)                                                      │
│  Jimmer/CacheAside/GlobalTxRunner/Auth/RateLimit/Storage             │
├─────────────────────────────────────────────────────────────────────┤
│  Data: PostgreSQL (Writer + Reader) | Redis | S3                     │
│  Flyway V1-V5（手动 flywayMigrate task）| UUIDv7 时间有序 ID         │
└─────────────────────────────────────────────────────────────────────┘
```

## 多模块结构

项目为 Gradle 三模块：

| 模块 | 类型 | 职责 | 依赖 |
|------|------|------|------|
| `core-common` | 纯 Kotlin 库（无 Spring） | 跨模块共享：`UuidV7`、`ClusterProperties` 等 | — |
| `core-api` | Spring Boot Web 服务 | 主 API（HTTP RPC + Webhook），业务全部在此 | 不依赖另两者 |
| `core-job` | Spring Boot（非 web，Spring Batch） | 定时/批处理任务：匿名 customer 清理等 | `core-common` |

> `core-api` 与 `core-job` 各自是独立可启动的 Spring Boot 应用，共享同一 PostgreSQL；跨模块只通过数据库（逻辑外键 UUID）协作，不互相编译依赖。
> 数据库迁移不再随应用启动执行，统一用 `./gradlew :core-api:flywayMigrate` 手动跑（见「构建与测试」）。

### 分层约束

```
Controller   →  只注入 Facade + GlobalTxRunner + ActionContextFactory（query 聚合经 XxxQueryService）
Facade       →  只注入 AggHandler + ModuleCtxFactory + 其他模块 Facade（跨模块）
Handler      →  只注入 Repo + CacheAside + 同模块 infra service
Repo         →  持有 CrudRepoTemplate（companion object）
```

**禁止跨级：**
- Controller 不能 import handler/repo 包
- Facade 不能 import repo 包
- Handler 不能 import facade 包（可注入其他模块的 Facade）
- QueryService 聚合只经 Facade 批量查询，不直接注入 repo；聚合禁循环 findById

> 详细编码示例和 Context 模型见 [编码指南](CODING_GUIDE.md)

## 模块职责

| 模块 | 功能 |
|------|------|
| auth | IDP 登录(Apple/Google)、AuthIdentity 账号中枢、AuthIdentity↔IdpIdentity 绑定(M:N 关系表)、Refresh Token 轮转、Access Token(EdDSA) |
| customer | Customer CRUD、匿名先行 / 登录转正 / 跨设备合并 |
| ai | 古物扫描、AI 识别(Spring AI 多模态)、Key 轮换+模型 fallback、收藏管理 |
| demo | Todo 清单 CRUD、嵌入 items、游标分页、FilterGroup 示例 |
| pay | Apple/Google 购买验证、订阅管理、Webhook(JWS 验签)、Tier 映射 |
| cs | 用户反馈（customer support） |
| media | 预签名上传/下载 |
| project | ProjectConfig 版本管理、ProjectInfo |

> customer / install 已并入 auth 模块（`modules/auth/customer/`、`modules/auth/install/`，2026-10-06），
> 表中的 customer 行为其子模块；install（install token / attestation / install↔customer 关系）同在 auth 下。
> 匿名 Customer 清理等批处理任务在 **core-job**（Spring Batch），不在 core-api。
> 认证详细设计见 [AUTH_DESIGN.md](AUTH_DESIGN.md)

## 目录结构

```
core-api/src/main/kotlin/com/ifmix/core/api/
├── CoreApplication.kt
├── bff/
│   ├── api/customer/           # RPC Controller（@RestController，POST /api/customer/core/{actionName}）
│   │   ├── ai/                 # AiController + AiQueryService（列表聚合）
│   │   ├── auth/               # AuthApiController / CustomerController / InstallApiController
│   │   ├── cs/                 # CsController
│   │   ├── demo/               # DemoController + DemoQueryService
│   │   ├── media/              # MediaController
│   │   └── pay/                # PayController
│   ├── webhooks/               # WebhookController (Apple/Google IAP REST)
│   └── wellknown/              # JwksController
├── entity/                      # Jimmer interface entity
│   ├── common/                 # 基类 + 跨模块枚举: BaseEntity, BaseProjectEntity, UUIDProps, MutableProps, SoftDeletableProps, ProjectScopedProps, CustomerOwnedProps, Platforms, Tiers
│   ├── ai/                     # ScanRecord, ScanCollection, ScanCollectionItem, ScanDeepResearch, AiApiKey, ImageRef
│   ├── auth/                   # Idp, IdpIdentity, AuthIdentity, AuthIdentityIdpRelation, ProjectToIdpRelation, RefreshToken
│   │   ├── customer/           # Customer, DeletionReasons
│   │   └── install/            # Install, InstallAttestation, InstallCustomerRelation
│   ├── pay/                    # Subscription, StoreNotification
│   ├── demo/                   # Todo, TodoItem, TodoRecommend
│   ├── project/                # ProjectConfigRevision, ProjectInfo, ConfigTypes
│   ├── cs/                     # Feedback
│   └── media/                  # UploadRecord
├── modules/
│   ├── auth/                   # 认证/身份中枢（customer、install 并入此模块，2026-10-06）
│   │   ├── AuthFacade.kt, AuthConfig.kt, ProviderVerifier.kt, AuthLoggedInEvent.kt, MergeOnLoginListener.kt
│   │   ├── AuthRequests.kt     # LoginReq/RefreshReq/LogoutReq（Facade 层请求记录）
│   │   ├── handler/AuthAggHandler.kt
│   │   ├── repo/               # Idp/IdpIdentity/AuthIdentity/AuthIdentityIdpRelation/ProjectToIdpRelation/RefreshToken 共 6 个 repo
│   │   ├── customer/           # CustomerFacade + handler/CustomerMergeHandler + repo/CustomerRepository
│   │   └── install/            # InstallFacade + handler/InstallAggHandler + repo/（Install/InstallAttestation/InstallCustomerRelation）
│   ├── ai/
│   │   ├── AiFacade.kt, ScanCollectionFacade.kt
│   │   ├── handler/ScanAggHandler.kt, ScanCollectionAggHandler.kt
│   │   ├── repo/
│   │   └── service/            # AI infra（SpringAiScanRunner, AiApiKeyStore, AiChatClientFactory）
│   ├── notification/           # FCM push 基座（scan/DR 完成通知）
│   ├── pay/
│   │   ├── PayFacade.kt
│   │   ├── handler/PayAggHandler.kt, PayWebhookHandler.kt
│   │   └── repo/
│   ├── cs/
│   │   ├── CsFacade.kt
│   │   ├── handler/FeedbackAggHandler.kt
│   │   └── repo/
│   ├── media/
│   │   ├── StorageFacade.kt
│   │   ├── handler/StorageAggHandler.kt
│   │   └── repo/
│   ├── project/
│   │   ├── ProjectConfigFacade.kt
│   │   ├── handler/ProjectConfigAggHandler.kt
│   │   └── repo/
│   └── demo/
│       ├── DemoFacade.kt
│       ├── handler/TodoAggHandler.kt
│       └── repo/
├── dto/                         # 协议 DTO：common（Page/ActionResult/CommonFindOptions/FilterGroup）+ 各模块 wire input/res（ai/auth/cs/demo/payment/storage/notification）
├── infra/
│   ├── db/                     # ModuleCtx, ModuleCtxFactory, ClusterRouter, ClusterSqlPair, UuidV7
│   ├── tx/                     # TxRunner, GlobalTxRunner, TxPropagation
│   ├── jimmer/                 # ClusterRegistry, ClusterProperties, JimmerConfig
│   │                           # ReadWriteRoutingDataSource, ProjectScopedFilter, TimestampDraftInterceptor
│   │                           # ActionContextHolder
│   ├── repo/                   # CrudRepoTemplate, ProjectCrudRepoTemplate, FilterGroupResolver
│   ├── codec/                  # Base58 (UUID ↔ 22-char URL-safe)
│   ├── http/                   # RPC 协议层：ActionContext + ActionContextFactory, RequestMeta, ApiRequestBody, Envelope,
│   │                           # GlobalExceptionHandler, ClientPlatform/ClientIpResolver, LogContext, RequestLoggingFilter,
│   │                           # wire 加密（WireCrypto/WireCryptoFilter）、DevRpcHeaderAdapter（local profile）
│   ├── auth/                   # AuthJwtService, AuthJwtKeys, Actor, Locales（locale 归一）, EmailNormalize, Hashing
│   ├── redis/                  # CacheAside, RedisConfig
│   ├── ratelimit/              # RateLimiter, TierResolver, RateLimitConfig, ScanQuotaConfig
│   ├── storage/                # ObjectStorage (interface), S3ObjectStorage, StorageConfig
│   └── config/                 # WebConfig, JacksonConfig, TransactionConfig
└── resources/
    ├── db/migration/           # Flyway V1-V6+（手动：./gradlew :core-api:flywayMigrate）
    ├── prompts/                # AI scan prompts
    └── application.yml + application-local.yml + application-prod.yml
``````

## RPC API 设计

> 协议唯一真相源：[api-protocol/archive/graphql-to-http-rpc-openapi.md](../design/api-protocol/archive/graphql-to-http-rpc-openapi.md) §一。
> GraphQL 引擎已删除（2026-10-06），历史设计看 git。

- **Endpoint**: `POST /api/customer/core/{actionName}`，全部 action 统一一种 URL 形状（不带 resourceId，ID 在加密 body 内）
- **actionName 四段结构**: `{q|m}_{module}_{resource}_{action}`（如 `q_demo_todo_getById`、`m_pay_iap_verify`）；
  `q/m` 表读/写意图，`action` 用标准动词（getById / getByIds / list / createOne / updateOne / …）。
  同一字符串即 OpenAPI 的 `operationId`（客户端从 OpenAPI 生成）
- **请求信封**: `{"meta": {...RequestMeta...}, "input": {...该 action 的输入...}}`；
  wire 加密时 body 是 octet-stream，WireCryptoFilter 解密后 controller 看到明文 JSON
- **响应**: `Envelope<T> = { reqId, code, msg, data }`；HTTP status = code 前三位（200000→200、429000→429）
- **凭证**: `meta.accessToken` 纯 token（type claim 自描述 install/customer/manager）；不进 HTTP header（对中间层不可见）
- **身份**: installId 等身份字段不出现在 meta，服务端从 token 的 `iid` claim 解出进 ActionContext
- **ActionContext**: 由 `ActionContextFactory.fromRpc` 单点构造（token 校验、aud 校验、meta 字段校验/归一），
  业务代码只读 ActionContext，不读原始 meta（除透传型遥测字段 `ctx.meta.xxx`）
- **DateTime**: ISO-8601 UTC 字符串；**枚举**: Int 全链路透传
- **Update 语义**: set/unset 防 null vs undefined 歧义
- **Mutation**: 包 `GlobalTxRunner`；AI 外部 IO 在事务外
- **聚合**: XxxQueryService 先分页根 → IDs 批量查 → Map 组装，禁循环 findById
- **限流**: 边缘限 IP（header 信号），服务端限身份/项目（ActionContext 锚点）；429000/429002 必带 retryAfterSec → Retry-After 头

## 缓存分层

```
DataLoader (per-request, batching only, caching=false)
  → 关联字段 N+1 批量加载
Redis CacheAside (跨 request, TTL 分钟级)
  → query: readCache=true → 走 cache
  → mutation: readCache=false → 跳过
  → 写后: evict
DB (via Jimmer KSqlClient)
  → query: preferReader=true → 从库
  → mutation: preferReader=false → 主库（通过 GlobalTxRunner 走 writer）
```

## 存储上传

- objectKey: `project/{base58_projectId}/{category}/install/{base58_installId}/{base58_mediaId}.{ext}`
- Base58 仅用于 objectKey（URL 场景）
- presignUpload 不要求登录
- 格式校验防路径遍历

## AI 扫描

- 模型 fallback: 主模型 → fallback 列表
- Key 重试: 每个模型遍历所有可用 key
- 预扣配额: 请求前扣减，失败归还

## 关键设计决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | HTTP RPC 替代 GraphQL (DGS) | persisted document 下字段级灵活性未被使用，RPC 免除引擎开销（2026-10-06，详见 proposal §一） |
| 2 | Jimmer 替代 jOOQ | Interface entity + KSP + Draft DSL |
| 3 | GlobalTxRunner 在 Controller 层 | 显式事务边界，整个 mutation action 一个事务 |
| 4 | Facade 只构造 mc + 转发 | 不做 cache/tx，保持 thin |
| 5 | CrudRepoTemplate 分两类 | `CrudRepoTemplate`(全局) + `ProjectCrudRepoTemplate`(强制 projectId)，类型安全 |
| 6 | Template 单条返回 Boolean，batch 返回 Int | 语义清晰 |
| 7 | Template 必须提供 batch 方法 | findByIds/existsByIds/batchSave/deleteByIds |
| 8 | 固定 Res DTO（无 include DSL） | 手写 companion factory + 出参无默认值；聚合批量组装 |
| 9 | hand-written batch 替代 DataLoader | 显式组装，消除 N+1 隐蔽失效风险 |
| 10 | QueryService 聚合只经 Facade | 不直接注入 repo；批量结果按输入顺序重排 |
| 11 | RPC input 全链路透传 | Controller→Facade→Handler 直传 input 对象 |
| 12 | JSONB 值对象 = data class + @Serialized | toDomain() 放同文件 |
| 13 | set/unset Update 语义 | 防 null vs undefined 歧义 |
| 14 | AuthInterceptor 非阻塞 | 支持匿名+认证混合接口 |
| 15 | 枚举全链路 Int 透传 | 灰度安全 |
| 16 | DateTime 统一 ISO-8601 字符串 | RPC 输出 + JSONB 存储一致 |
| 17 | IDP 模型替代 AuthTenant | 去掉 tenant 层，简化为 IDP + relation |
| 18 | 跨模块用逻辑外键 UUID | 不用 Jimmer `@ManyToOne`，保持模块独立 |
| 19 | BaseEntity / BaseProjectEntity 基类 | 减少样板：`id + createdAt + updatedAt`（+ projectId） |
| 20 | 表名带模块前缀 | `auth_idp`, `demo_todo`, `pay_subscription` |
| 21 | payment→pay, storage→media | 包名/表名统一短名 |
| 22 | @Service/@Component 直注册 | 不在 Config 间接注册 |
| 23 | presignUpload 不要求登录 | 后续通过行为验证增强 |
| 24 | AI ScanRunner 每个模型遍历所有 key | 不是只试一个就跳下一个模型 |
| 25 | 限流超限不删 Redis key | 让 key 自然 TTL 过期 |
| 26 | objectKey 强格式校验 | 防路径遍历 |
| 27 | Webhook 必须验签 | Apple JWS / Google 通过 packageName 反查 projectId |
| 28 | CacheAside 显式调用 | 不用 @Cacheable 魔法 |
| 29 | objectKey UUID 用 Base58 | URL 场景缩短路径 |
| 30 | CommonFindOptions + findByOptions | 通用分页查询模板（filter/cursor/sort/limit） |
| 31 | 三模块拆分 core-common/core-api/core-job | 批处理与 web 分离；共享库无 Spring 依赖 |
| 32 | Flyway 手动 flywayMigrate，不随启动 | 多实例部署避免并发 migrate 竞争，迁移显式可控 |
| 33 | AuthIdentity 账号中枢 + Customer/IdpIdentity 关系表 | 账号资料与 project 级用户分离；IdpIdentity↔AuthIdentity 用 M:N 关系表 |
| 34 | 匿名 Customer 清理迁到 core-job | 批处理任务不占 web 进程，Spring Batch 编排 |
| 35 | 不建设通用创建幂等 | 重复损害低，通用 key/状态机仍无法保证外部 AI Exactly Once；接受重复，以限流、配额、清理和监控兜底 |
| 36 | 新 Customer 必须来自 Install（目标态） | createAnonymous/login/refresh 携带 installToken，保证新 customer token 含 iid 并原子维护关系（legacy 兼容已移除，2026-10-06） |

## API 约定

- RPC: `POST /api/customer/core/{actionName}`（`{meta, input}` 信封；wire 加密时 `x-wirep-version: 2` + octet-stream）
- Webhook: `POST /webhooks/iap/*`（JWS 验签）
- JWKS: `GET /.well-known/jwks`
- 响应: `Envelope<T>` (`{reqId, code, msg, data}`)

### meta 字段（请求信封）

| 字段 | 格式 | 说明 |
|------|------|------|
| `reqId` | 字符串 | 客户端自供请求 id，缺省服务端补 UUID；响应 Envelope.reqId 回显 |
| `projectId` | slug | 项目标识（token aud 校验 + 访问权），小写字母开头 3-30 字符 |
| `accessToken` | 字符串 | 纯 token，无 `Bearer` 前缀；type claim 自描述 install(5)/customer(10)/manager(20) |
| `locale` / `currency` / `country` | BCP 47 / ISO 4217 / ISO 3166-1 | 用户偏好，平铺（归一规则见下「locale 归一」） |
| `userTz` | IANA 时区名 | 可选（如 `Asia/Shanghai`） |
| `appVersion` / `otaVersion` / `clientPlatform` / `deviceModel` / `osVersion` | 字符串 | 遥测/诊断；服务端日志与分析以这些结构化字段为权威信源 |

边缘注入信号（真实 IP、`cf-ray`、`cf-bot-score`）与信封标记（`Content-Type`、`x-wirep-version`）留在 header，
业务语义字段一律进 meta——详见 proposal §一「header 留守原则」。

#### 格式软校验（严格 / 宽松）

`locale` / `country` / `currency` 带了值但**格式非法**时的处理由 `app.header-validation.strict` 开关决定
（`ActionContextFactory`）：

| 环境 | `strict` | 行为 |
|------|----------|------|
| 测试 / 开发（默认） | `true` | 抛 `ApiError(INVALID_REQUEST)`，整个请求报错，尽早暴露客户端 bug |
| 线上 | `false` | 打 `warn` log 并当作未提供（`null`），请求照常处理 |

线上通过环境变量 `APP_HEADER_VALIDATION_STRICT=false` 切换。
注意：此开关只作用于「带了值但格式非法」的软校验；`projectId` 缺失、token 等硬校验**任何环境都抛**，不受影响。
`locale` 特例：合法 BCP 47 但不在支持集（如 `ko`/`ru`）**任何环境都返回 `null` 不抛**（不算格式 bug，见下方「locale 归一」）。

#### locale 归一

`meta.locale` 在 `ActionContextFactory.fromRpc` 入口经 `Locales.normalizeLocale` 归一到受支持集，
落库/透传的一律是规范值或 `null`（不支持不抛错，视为未提供，由下游各自兜底）。以后加语言只改 `Locales`。

支持集（10 种）：`en`, `zh-CN`, `zh-TW`, `ja`, `fr`, `es`, `pt`, `de`, `it`, `nl`

- 非中文按 language subtag 归并：`en-US`/`en-GB` → `en`，`pt-BR` → `pt`，`ja-JP` → `ja`，依此类推。
- 中文按 script/region 分简繁：
  - 简体：`zh` / `zh-Hans*` / `zh-CN` / `zh-SG` / `zh-MY` → `zh-CN`（裸 `zh` 默认简体）
  - 繁体：`zh-TW` / `zh-HK` / `zh-MO` / `zh-Hant*` → `zh-TW`
- 其它合法但不支持的语言（`ko`/`ru`/…）→ `null`（任何环境都不抛，视为未提供）
- 无法解析出 language subtag 的畸形输入（如 `!!bad`）→ 走「格式软校验」：`strict` 抛、线上 WARN

> 内部用 `Locales.normalizeLocaleResult` 区分 `Ok` / `Unsupported`（合法但不支持）/ `Malformed`（畸形）；
> `Locales.normalizeLocale` 是只关心是否命中支持集的薄封装。

## 环境变量

| 变量 | 用途 | 默认值 |
|------|------|--------|
| `PG_WRITER_URL` | PostgreSQL 主库 | `jdbc:postgresql://localhost:5432/ifmix_core_local` |
| `PG_READER_URL` | 从库 | 同主库 |
| `PG_USERNAME`/`PG_PASSWORD` | 凭证 | `postgres` |
| `REDIS_URL` | Redis | `redis://localhost:6379` |
| `STORAGE_TYPE` | 存储 | `none` |
| `SPRING_AI_OPENAI_API_KEY` | AI Key | placeholder |
| `AUTH_ISSUER` | JWT issuer | `ifmix` |
| `APP_HEADER_VALIDATION_STRICT` | header 格式软校验：`true` 非法抛错 / `false` 只 WARN | `true`（线上设 `false`） |
| `LOG_PATH` | 日志文件目录（logback-spring.xml） | `./logs` |
| `PORT` | 端口 | `3001` |

## 详细文档

| 文档 | 内容 |
|------|------|
| [编码指南](CODING_GUIDE.md) | Context 模型、事务管理、分层示例代码、Entity 设计、CrudRepoTemplate |
| [认证设计](AUTH_DESIGN.md) | IDP 模型、AuthIdentity、登录判定表、idpType |
| [数据库约定](DATABASE.md) | 表清单、命名规则、UUID、枚举、FilterGroup、游标分页 |
| ~~GraphQL Trusted Documents~~ | 已随 GraphQL 引擎删除（2026-10-06），git 历史可查 |

## 构建与测试

```bash
./gradlew :core-api:compileKotlin          # 编译 core-api（含 KSP）
./gradlew :core-job:compileKotlin          # 编译 core-job（批处理）
./gradlew :core-api:test                   # 全部测试
./gradlew :core-api:test --tests "*.e2e.*" # E2E
./gradlew :core-api:flywayMigrate          # 执行数据库迁移（手动，替代启动时自动 migrate）
./gradlew :core-api:bootRun                # 运行主服务 (需 PG + Redis)
./gradlew :core-job:bootRun                # 运行批处理任务 (需 PG)
```

> `flywayMigrate` 连接由环境变量 `DB_URL`/`DB_USER`/`DB_PASSWORD` 决定（默认本地 `core_api_local`）。

- **测试框架**: JUnit 5 + Mockito + assertk
- **集成测试**: Testcontainers (PostgreSQL + Redis)
- **E2E**: WebTestClient + Testcontainers

## 迁移历史

| 日期 | 事项 |
|------|------|
| 2026-08-19 | jOOQ → Jimmer |
| 2026-08-20 | 分层规范化（ModuleCtx + AggHandler + GlobalTx） |
| 2026-08-22 | CrudRepoTemplate 分两类 |
| 2026-08-22 | 表名重命名 + payment→pay, storage→media |
| 2026-08-22 | DateTime 统一 ISO-8601 |
| 2026-08-23 | Auth 重设计：IDP 模型 |
| 2026-08-23 | BaseEntity/BaseProjectEntity 基类 |
| 2026-08-23 | CommonFindOptions 通用查询 |
| 2026-09-02 | 三模块拆分（core-common / core-api / core-job） |
| 2026-09-02 | 身份模型重构：AuthIdentity 账号中枢 + Customer + M:N 关系表 |
| 2026-09-02 | Flyway 改手动 flywayMigrate task；匿名清理迁入 core-job |
| 2026-10-06 | GraphQL(DGS) → HTTP RPC + OpenAPI（{meta,input} 信封 / Envelope.reqId / wire 强制加密 v2）；customer/install 并入 auth 模块 |
