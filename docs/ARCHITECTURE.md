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
| API | GraphQL (Netflix DGS) | 12.0.1 |
| ORM | Jimmer (KSP) | 0.11.5 |
| 数据库 | PostgreSQL (读写分离) | — |
| 缓存 | Redis + CacheAside | — |
| 对象存储 | S3 兼容 (AWS/R2/MinIO) | — |
| AI | Spring AI 2.0 (OpenAI-compatible) | — |
| 认证 | EdDSA(Ed25519) JWT + IDP OAuth2 | — |
| 构建 | Gradle 9.6.1 + KSP | — |
| 序列化 | Jackson 3 (tools.jackson) | — |
| GraphQL codegen | DGS codegen 8.6.0 | schema → input/payload/enum |

## 分层架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  BFF — GraphQL (DGS DataFetcher) + REST                              │
│  POST /customer/core/gql  (主 API)                                   │
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
│  Jimmer interface + @MappedSuperclass · KSP 生成扩展属性 · 直出 GQL  │
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
| `core-api` | Spring Boot Web 服务 | 主 API（GraphQL + REST + Webhook），业务全部在此 | 不依赖另两者 |
| `core-job` | Spring Boot（非 web，Spring Batch） | 定时/批处理任务：匿名 customer 清理等 | `core-common` |

> `core-api` 与 `core-job` 各自是独立可启动的 Spring Boot 应用，共享同一 PostgreSQL；跨模块只通过数据库（逻辑外键 UUID）协作，不互相编译依赖。
> 数据库迁移不再随应用启动执行，统一用 `./gradlew :core-api:flywayMigrate` 手动跑（见「构建与测试」）。

### 分层约束

```
DataFetcher  →  只注入 Facade + GlobalTxRunner + OperationContextProvider
Facade       →  只注入 AggHandler + ModuleCtxFactory + 其他模块 Facade（跨模块）
Handler      →  只注入 Repo + CacheAside + 同模块 infra service
Repo         →  持有 CrudRepoTemplate（companion object）
```

**禁止跨级：**
- DataFetcher 不能 import handler/repo 包
- Facade 不能 import repo 包
- Handler 不能 import facade 包（可注入其他模块的 Facade）
- DataLoader/Resolver 通过 Facade 调用，不直接注入 repo

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

> 匿名 Customer 清理等批处理任务在 **core-job**（Spring Batch），不在 core-api。
> 认证详细设计见 [AUTH_DESIGN.md](AUTH_DESIGN.md)

## 目录结构

```
core-api/src/main/kotlin/com/ifmix/core/api/
├── CoreApplication.kt
├── bff/
│   ├── graphql/customer/       # DGS DataFetcher
│   │   ├── ai/                 # AiFetcher + DataLoaders
│   │   ├── auth/               # AuthFetcher
│   │   ├── cs/                 # CsFetcher
│   │   ├── customer/           # CustomerFetcher
│   │   ├── demo/               # DemoFetcher + TodoItemsResolver
│   │   ├── pay/                # PayFetcher
│   │   └── media/              # MediaFetcher
│   ├── webhooks/               # WebhookController (Apple/Google IAP REST)
│   └── wellknown/              # JwksController
├── entity/                      # Jimmer interface entity (直出 GraphQL)
│   ├── common/                 # 基类 + 跨模块枚举: BaseEntity, BaseProjectEntity, UUIDProps, MutableProps, SoftDeletableProps, ProjectScopedProps, CustomerOwnedProps, Platforms, Tiers
│   ├── ai/                     # ScanRecord, ScanCollection, ScanCollectionItem, ScanDeepResearch, AgnesKey, ImageRef
│   ├── auth/                   # Idp, IdpIdentity, AuthIdentity, AuthIdentityIdpRelation, ProjectToIdpRelation, RefreshToken
│   ├── customer/               # Customer
│   ├── pay/                    # Subscription, StoreNotification
│   ├── demo/                   # Todo, TodoItem, TodoRecommend
│   ├── project/                    # ProjectConfigRevision, ProjectInfo, ConfigTypes
│   ├── cs/                     # Feedback
│   └── media/                  # UploadRecord
├── modules/
│   ├── auth/
│   │   ├── AuthFacade.kt
│   │   ├── handler/AuthAggHandler.kt
│   │   ├── repo/               # IdpRepository, IdpIdentityRepository, AuthIdentityRepository, AuthIdentityIdpRelationRepository, ProjectToIdpRelationRepository, RefreshTokenRepository
│   │   ├── AuthConfig.kt, ProviderVerifier.kt
│   │   ├── AuthLoggedInEvent.kt
│   │   └── MergeOnLoginListener.kt
│   ├── customer/
│   │   ├── CustomerFacade.kt
│   │   ├── handler/            # CustomerMergeHandler
│   │   └── repo/CustomerRepository.kt
│   ├── ai/
│   │   ├── AiFacade.kt, ScanCollectionFacade.kt
│   │   ├── handler/ScanAggHandler.kt, ScanCollectionAggHandler.kt
│   │   ├── repo/
│   │   └── service/            # AI infra（SpringAiScanRunner, AgnesKeyStore, AgnesChatClientFactory）
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
├── dto/                         # 共享 DTO (Page, OperationResult, CursorQueryInput)
├── infra/
│   ├── db/                     # ModuleCtx, ModuleCtxFactory, ClusterRouter, ClusterSqlPair, UuidV7
│   ├── tx/                     # TxRunner, GlobalTxRunner, TxPropagation
│   ├── jimmer/                 # ClusterRegistry, ClusterProperties, JimmerConfig
│   │                           # ReadWriteRoutingDataSource, ProjectScopedFilter, TimestampDraftInterceptor
│   │                           # OperationContextHolder
│   ├── repo/                   # CrudRepoTemplate, ProjectCrudRepoTemplate, FilterGroupResolver
│   ├── codec/                  # Base58 (UUID ↔ 22-char URL-safe)
│   ├── graphql/                # OperationContextProvider, GraphQLExceptionHandler, EndpointConfig, scalars/
│   ├── http/                   # OperationContext, RequestContext, ApiError, ErrorCode, Envelope, Interceptors
│   ├── auth/                   # AuthInterceptor, AuthJwtService, AuthJwtKeys, Hashing
│   ├── redis/                  # CacheAside, RedisConfig
│   ├── ratelimit/              # RateLimiter, TierResolver, RateLimitConfig
│   ├── storage/                # ObjectStorage (interface), S3ObjectStorage, StorageConfig
│   └── config/                 # WebConfig, JacksonConfig, TransactionConfig
└── resources/
    ├── schema/common/          # GraphQL 公共 scalars + CommonFindOptions
    ├── schema/customer/        # GraphQL Customer schema (auth, ai, demo, pay, media, cs)
    ├── db/migration/           # Flyway V1-V5（手动：./gradlew :core-api:flywayMigrate）
    ├── prompts/                # AI scan prompts
    └── application.yml + application-local.yml
```

## GraphQL 设计

- **Endpoint**: `POST /customer/core/gql`（需 `x-project-id` header）
- **GraphiQL**: `/apidocs/core/customer/gql`
- **Operation 命名**: `${q|m}_${module}_${action}`（如 `q_demo_findTodos`, `m_auth_login`）
- **DateTime**: ISO-8601 UTC 字符串（输入接受 ISO 或 epoch millis）
- **input 全链路透传**: Fetcher→Facade→Handler 直传 input 对象
- **Update 语义**: set/unset 防 null vs undefined 歧义
- **Mutation 返回**: `XxxResult { success, xxx? }`
- **DataLoader**: caching=false，通过 Facade 调用
- **CommonFindOptions**: 通用列表查询（filter + cursor + sortBy + sortDirection + limit）

### DGS Codegen

- 从 `.graphqls` 生成 Kotlin input/payload/enum types
- output types 通过 `typeMapping` 映射到 Jimmer entity（entity 直出）
- 生成代码包: `com.ifmix.core.api.generated`

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
| 1 | GraphQL (DGS) 替代 REST | 移动端按需取字段、DataLoader 解决 N+1 |
| 2 | Jimmer 替代 jOOQ | Interface entity + KSP + Draft DSL + 直出 GraphQL |
| 3 | GlobalTxRunner 在 DataFetcher 层 | 显式事务边界，整个 mutation field 一个事务 |
| 4 | Facade 只构造 mc + 转发 | 不做 cache/tx，保持 thin |
| 5 | CrudRepoTemplate 分两类 | `CrudRepoTemplate`(全局) + `ProjectCrudRepoTemplate`(强制 projectId)，类型安全 |
| 6 | Template 单条返回 Boolean，batch 返回 Int | 语义清晰 |
| 7 | Template 必须提供 batch 方法 | findByIds/existsByIds/batchSave/deleteByIds |
| 8 | Entity 直出 GraphQL | 零 DTO 转换 |
| 9 | DataLoader caching=false | 防 mutation 间脏读 |
| 10 | DataLoader/Resolver 通过 Facade | 不直接注入 repo |
| 11 | GraphQL input 全链路透传 | Fetcher→Facade→Handler 直传 input 对象 |
| 12 | JSONB 值对象 = data class + @Serialized | toDomain() 放同文件 |
| 13 | set/unset Update 语义 | 防 null vs undefined 歧义 |
| 14 | AuthInterceptor 非阻塞 | 支持匿名+认证混合接口 |
| 15 | 枚举全链路 Int 透传 | GraphQL 不用 enum，灰度安全 |
| 16 | DateTime 统一 ISO-8601 字符串 | GraphQL 输出 + JSONB 存储一致 |
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

## API 约定

- GraphQL: `/customer/core/gql`（`x-project-id` 必填）
- Webhook: `POST /webhooks/iap/*`（JWS 验签）
- JWKS: `GET /.well-known/jwks`
- REST 响应: `Envelope<T>` (`{code, msg, data}`)

### 请求头

| Header | 格式 | 说明 |
|--------|------|------|
| `x-project-id` | UUID | 应用 ID（必填） |
| `x-install-id` | UUID | 设备安装 ID |
| `x-locale` | IETF BCP 47 | 用户语言偏好。归一到受支持集，不支持则视为未提供（null）。支持 10 种：`en`, `zh-CN`, `zh-TW`, `ja`, `fr`, `es`, `pt`, `de`, `it`, `nl`（归一规则见下方「locale 归一」） |
| `x-country` | ISO 3166-1 alpha-2, 大写 | 用户所在国家，如 `US`, `GB`, `JP`, `MY`, `SG`, `CN` |
| `x-currency` | ISO 4217, 大写 | 用户货币偏好，如 `USD`, `EUR`, `GBP`, `JPY`, `CNY`, `MYR`, `SGD` |
| `x-client-platform` | `ios` \| `android` | 客户端平台 |
| `x-native-version` | 字符串 | 原生版本号 |
| `x-js-version` | 字符串 | JS Bundle 版本号 |

#### locale 归一

`x-locale` 在 `RequestParser.parseLocale` 入口归一到受支持集，落库/透传的一律是规范值或 `null`（不支持不抛错，视为未提供，由下游各自兜底）。以后加语言只改 `RequestParser.normalizeLocale`。

支持集（10 种）：`en`, `zh-CN`, `zh-TW`, `ja`, `fr`, `es`, `pt`, `de`, `it`, `nl`

- 非中文按 language subtag 归并：`en-US`/`en-GB` → `en`，`pt-BR` → `pt`，`ja-JP` → `ja`，依此类推。
- 中文按 script/region 分简繁：
  - 简体：`zh` / `zh-Hans*` / `zh-CN` / `zh-SG` / `zh-MY` → `zh-CN`（裸 `zh` 默认简体）
  - 繁体：`zh-TW` / `zh-HK` / `zh-MO` / `zh-Hant*` → `zh-TW`
- 其它语言（`ko`/`ru`/…）或无法解析 → `null`

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
| `PORT` | 端口 | `3001` |

## 详细文档

| 文档 | 内容 |
|------|------|
| [编码指南](CODING_GUIDE.md) | Context 模型、事务管理、分层示例代码、Entity 设计、CrudRepoTemplate |
| [认证设计](AUTH_DESIGN.md) | IDP 模型、AuthIdentity、登录判定表、idpType |
| [数据库约定](DATABASE.md) | 表清单、命名规则、UUID、枚举、FilterGroup、游标分页 |
| [GraphQL Trusted Documents](GRAPHQL_TRUSTED_DOCUMENTS.md) | persisted query allowlist、x-api-name 契约、PreparsedDocumentProvider |

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
