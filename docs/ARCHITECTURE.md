# ifmix_server 架构文档

> 最后更新: 2026-07-31

## 项目概述

ifmix 是一个面向移动端（iOS/Android）的后端 API 服务，核心功能围绕古物扫描、收藏管理和 AI 图像识别展开。

## 技术栈

| 层级 | 选型 | 版本 |
|------|------|------|
| 语言 | Kotlin | 2.3.10 |
| 运行时 | JDK 25 (Virtual Threads) | — |
| 框架 | Spring Boot | 4.1.0 |
| ORM | Jimmer | 0.11.5 |
| 数据库 | PostgreSQL (读写分离) | — |
| 缓存/限流 | Redis | — |
| 对象存储 | S3 兼容 (AWS/R2/MinIO) | — |
| AI | Spring AI 2.0 (OpenAI-compatible) | — |
| 认证 | EdDSA(Ed25519) JWT + OAuth2 | — |
| 构建 | Gradle 9.6.1 + KSP | — |
| 序列化 | Jackson 3 (tools.jackson) | — |

## 架构概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                          BFF Layer (Controllers)                      │
│  /customer/core/*   (业务 API, 需 appId header)                      │
│  /webhooks/iap/*    (商店回调, JWS 验签)                              │
│  /.well-known/jwks  (公钥暴露)                                       │
├─────────────────────────────────────────────────────────────────────┤
│                          Service Layer                                │
│  AuthService | IapService | AntiqueService | CollectionService       │
│  TodoService | FeedbackService | AppConfigRepo                       │
├─────────────────────────────────────────────────────────────────────┤
│                          Repository Layer (Jimmer)                    │
│  BaseCrudRepository → 各模块 Repository (@Repository)                │
│  SQL 游标分页 | AppScopedFilter (多租户)                              │
├─────────────────────────────────────────────────────────────────────┤
│                          Infrastructure Layer                        │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐              │
│  │Auth/JWT  │ │RateLimiter│ │ObjectStore│ │  AI/Scan │              │
│  │EdDSA     │ │Redis 日窗 │ │S3 Presign │ │Spring AI │              │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘              │
├─────────────────────────────────────────────────────────────────────┤
│                          Data Layer                                   │
│  PostgreSQL (Writer + Reader) | Redis | S3                           │
│  Flyway Migrations (V1-V7) | UUIDv7 时间有序 ID                      │
└─────────────────────────────────────────────────────────────────────┘
```

## 模块职责

### Auth 模块
- **社交登录**: Google / Apple id_token 验证 (JWKS)
- **设备密钥**: 365天有效，用于静默登录 (exchange 流程)
- **Refresh Token**: 30天有效，支持轮转 (旧 token 自动作废)
- **Access Token**: EdDSA 签名的短期 JWT (默认 900s)
- **多租户**: 通过 AuthTenant 隔离不同 app 的用户体系

### IAP 模块
- **购买验证**: Apple/Google 双平台，PurchaseVerifier 接口解耦
- **订阅管理**: 状态跟踪 (active/cancelled/expired/grace_period)
- **商店通知**: Webhook 接收 + JWS 签名验证 + 幂等处理
- **Tier 映射**: productId → Tier (FREE/PRO/ENTERPRISE)

### Antique 模块 (古物扫描)
- **扫描创建**: 限流 → 预签名上传 URL → ScanRecord
- **AI 识别**: Spring AI 多模态 → JSON 结构化结果
- **Key 轮换**: 多 API Key 加权选择 + 失败退避 + 模型 fallback

### Collection 模块 (收藏)
- **默认收藏夹**: 按 userId 优先、installId 次之的自动创建
- **幂等添加/批量移除**: 软删除
- **游标分页**: 关联 ScanRecord 数据

## 设计决策记录

### API 风格
- **查询用 PUT** (`/query/*`)、**修改用 POST** (`/mutation/*`) — 类似 tRPC 的 BFF 风格
- 所有响应统一包装为 `Envelope<T>` (`{code, msg, data}`)
- 请求头必带 `x-app-id` (UUID)，可选 `x-client-platform`

### 认证策略
- `AuthInterceptor` 是**非阻塞**的：有效 token → 填充 userId；无效/缺失 → 匿名继续
- 需要强认证的逻辑由 Service 层自行检查 `ctx.userId`
- 这是有意设计：支持匿名 + 认证混合的接口（如扫描列表可匿名浏览）

### 存储上传安全
- `presignUpload` **不要求登录**（后续可加行为验证）
- objectKey 强制格式: `app_{appId}/i_{installId}/...` 或 `app_{appId}/u_{userId}/...`
- 禁止路径遍历 (`..`)，强制 appId 一致性校验
- `presignDownload` 暂不做权限验证

### 限流
- UTC 日固定窗口，Redis INCR + EXPIRE
- 超限后**不删除** key（让 key 自然过期，防止重置绕过）
- 支持 refund（下游失败时归还配额）

### DI / 组装风格
- **Service 层**: `@Service` + 构造器注入（组合优于继承）
- **Repository 层**: `@Repository` + 构造器注入 `KSqlClient`
- **Config 类**: 仅创建基础设施 bean（JwtDecoder、Stub 实现、条件 bean）
- **条件加载**: `@ConditionalOnMissingBean` / `@ConditionalOnProperty` 实现 stub ↔ 真实实现切换

### 数据库
- **表名前缀**: 所有表使用 `core_` 前缀（如 `core_todo`, `core_app_user`）
- **读写分离**: `ReadWriteRoutingDataSource` 根据 `@Transactional(readOnly=true)` 自动路由
- **游标分页**: 基于 UUIDv7 (时间有序) 的 `id < cursor ORDER BY id DESC LIMIT n+1`
- **多租户过滤**: Jimmer `AppScopedFilter` 全局注入 `WHERE app_id = ?`
- **软删除**: Jimmer `@LogicalDeleted` 自动过滤
- **Flyway**: V1-V7 migration + V8 表名前缀重命名，不可回退

### 枚举设计规范

**全链路方案：PG SMALLINT ↔ Kotlin enum(code) ↔ JSON String**

1. **PG 层**: `SMALLINT NOT NULL DEFAULT xx`，存数字编码
2. **Kotlin Entity 层**: 用 `enum class` + 手动 `code: Int` 属性 + Jimmer `@EnumType(ORDINAL)` / `@EnumItem(ordinal=N)` 自动映射
3. **DTO / JSON 序列化层**: 对外输出枚举名称字符串（如 `"COMPLETED"`），**不暴露数字编码**
4. **前端**: 只看到字符串枚举名，不需要知道内部编码

**编码规则：**
- **0 保留不用**（不表示任何状态）
- 同组内连续用十位：100, 110, 120...
- 不同组间隔 100：10x, 20x, 30x...
- 方便后续在组内插入新值，不影响已有编码
- 可利用范围判断做分组：`code >= 20` 表示某类状态集合

**示例：**
```kotlin
@EnumType(EnumType.Strategy.ORDINAL)
enum class ScanStatus(val code: Int) {
    @EnumItem(ordinal = 100)
    PENDING(100),      // 待处理组 100~199

    @EnumItem(ordinal = 110)
    PROCESSING(110),

    @EnumItem(ordinal = 200)
    COMPLETED(200),    // 完成组 200~299

    @EnumItem(ordinal = 300)
    FAILED(300);       // 失败组 300~399

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): ScanStatus = byCode[code]
            ?: throw IllegalArgumentException("Unknown ScanStatus code: $code")
    }
}
```

**Jimmer 映射方式**：使用 `@EnumType(EnumType.Strategy.ORDINAL)` + `@EnumItem(ordinal = N)` 注解，
Jimmer 自动处理 Entity ↔ DB 的双向映射，无需 ScalarProvider 或 ValueConverter。
Entity 属性直接用枚举类型（如 `val status: ScanStatus`）。

```sql
-- PG migration
ALTER TABLE core_scan_record ALTER COLUMN status TYPE SMALLINT USING (
    CASE status
        WHEN 'PENDING' THEN 100
        WHEN 'PROCESSING' THEN 110
        WHEN 'COMPLETED' THEN 200
        WHEN 'FAILED' THEN 300
    END
);
ALTER TABLE core_scan_record ALTER COLUMN status SET NOT NULL;
ALTER TABLE core_scan_record ALTER COLUMN status SET DEFAULT 100;
```

**适用范围：**
- 业务枚举（状态、分类、平台等）全部用此方案
- 来自外部系统的不可控值（如商店 subStatus）保持 VARCHAR

**已定义的编码表：**

| 枚举 | 值 | 编码 |
|------|-----|------|
| ScanStatus.PENDING | 待处理 | 100 |
| ScanStatus.PROCESSING | 处理中 | 110 |
| ScanStatus.COMPLETED | 已完成 | 200 |
| ScanStatus.FAILED | 失败 | 300 |
| Tier.FREE | 免费 | 100 |
| Tier.PRO | 专业版 | 200 |
| Tier.ENTERPRISE | 企业版 | 300 |
| FeedbackCategory.LIKED | 喜欢 | 100 |
| FeedbackCategory.PRICE_TOO_HIGH | 价格过高 | 200 |
| FeedbackCategory.PRICE_TOO_LOW | 价格过低 | 210 |
| FeedbackCategory.PRICE_MISSING | 缺少价格 | 220 |
| FeedbackCategory.WRONG_IDENTIFICATION | 识别错误 | 300 |
| FeedbackCategory.FEATURE_REQUEST | 功能建议 | 400 |
| FeedbackCategory.MORE_RECOMMENDATIONS | 更多推荐 | 410 |
| Platform.APPLE | 苹果 | 100 |
| Platform.GOOGLE | 谷歌 | 200 |

### AI 扫描
- **模型 fallback**: 主模型 → fallback 列表，按顺序尝试
- **Key 重试**: 每个模型尝试所有可用 key（内层循环），不止一个
- **异常检测**: 通过 message 启发式匹配 429/timeout（Spring AI 不暴露 HTTP 状态码）
- **预扣配额**: 请求前扣减，成功确认，失败归还

## 目录结构

```
core-api/src/main/kotlin/com/ifmix/api/core/
├── CoreApplication.kt              # 启动入口
├── bff/                            # Controller 层 (BFF)
│   ├── customer/                   # 客户端 API
│   │   ├── CustomerAuthController
│   │   ├── CustomerAntiqueController
│   │   ├── CustomerCollectionController
│   │   ├── CustomerIapController
│   │   ├── CustomerStorageController
│   │   ├── CustomerTodoController
│   │   └── CustomerFeedbackController
│   ├── webhooks/                   # 商店回调
│   └── wellknown/                  # JWKS 暴露
├── service/                        # 业务层
│   ├── auth/                       # 认证 (AuthService, AuthConfig, ProviderVerifier)
│   ├── iap/                        # 内购 (IapService, PurchaseVerifier, NotificationDecoder)
│   ├── antique/                    # 古物扫描 (AntiqueService, ScanRunner)
│   ├── collection/                 # 收藏 (CollectionService)
│   ├── appconfig/                  # App 配置
│   ├── todo/                       # Todo
│   ├── feedback/                   # 反馈
│   └── base/                       # 基类 (BaseCrudService)
├── repository/                     # 数据访问层 (Jimmer)
│   ├── base/                       # 基类 (BaseCrudRepository)
│   ├── auth/                       # 认证相关 6 个 repo
│   ├── iap/                        # 订阅/通知 repo
│   ├── antique/                    # 扫描记录 repo
│   ├── collection/                 # 收藏 repo
│   ├── ai/                         # Agnes key repo
│   ├── appconfig/                  # App 配置 repo
│   ├── todo/                       # Todo repo
│   └── feedback/                   # Feedback repo
├── entity/                         # Jimmer 实体定义
│   ├── auth/                       # AppUser, AuthIdentity, ...
│   ├── iap/                        # Subscription, StoreNotification
│   ├── antique/                    # ScanRecord
│   ├── collection/                 # Collection, CollectionItem
│   ├── ai/                         # AgnesKey
│   ├── appconfig/                  # AppConfig, AppInfo
│   ├── todo/                       # Todo, TodoItem
│   └── feedback/                   # Feedback
└── infra/                          # 基础设施
    ├── auth/                       # JWT 签发/验签, Hashing, Email 规范化
    ├── ai/                         # Spring AI 集成, AgnesKeyStore, ScanPrompt
    ├── db/                         # UUIDv7, CursorQueryInput, Page, Ownership
    ├── http/                       # Envelope, ApiError, ErrorCode, Interceptors
    ├── jimmer/                     # Cluster 路由, 读写分离, AppScopedFilter
    ├── ratelimit/                  # RateLimiter, Tier, TierResolver
    ├── redis/                      # Redis 配置
    ├── storage/                    # ObjectStorage 接口 + S3/Noop 实现
    └── config/                     # WebConfig, JacksonConfig, OpenApiConfig
```

## 环境变量

| 变量 | 用途 | 默认值 |
|------|------|--------|
| `PG_WRITER_URL` | PostgreSQL 主库 | `jdbc:postgresql://localhost:5432/ifmix_core_local` |
| `PG_READER_URL` | PostgreSQL 从库 | 同主库 |
| `PG_USERNAME` / `PG_PASSWORD` | 数据库凭证 | `postgres` |
| `REDIS_URL` | Redis | `redis://localhost:6379` |
| `STORAGE_TYPE` | 存储类型 | `none` (启用 S3: `s3`) |
| `STORAGE_ENDPOINT` / `STORAGE_BUCKET` | S3 配置 | MinIO 本地 |
| `SPRING_AI_OPENAI_API_KEY` | AI API Key | placeholder |
| `SPRING_AI_OPENAI_BASE_URL` | AI 端点 | OpenAI |
| `AUTH_ISSUER` | JWT issuer | `ifmix` |
| `PORT` | 服务端口 | `3001` |

## 测试

- **框架**: JUnit 5 + Mockito + assertk
- **集成测试**: Testcontainers PostgreSQL
- **E2E 测试**: `WebTestClient` + Testcontainers (PostgreSQL + Redis)
  - 每次发布前运行: `./gradlew :core-api:test`
  - 单独运行 E2E: `./gradlew :core-api:test --tests "com.ifmix.api.core.e2e.*"`
  - 详见 [E2E 测试方案](E2E_TESTING.md)
- **路由测试**: H2 内存数据库
- **命令**: `./gradlew :core-api:test`

## 已知限制 / 后续计划

1. `TierResolver` 当前返回固定 `FREE`，需连接到 IAP 订阅查询
2. `ScanRecordRepository.findByScanId` 仍使用 findAll 过滤（低频，暂可接受）
3. Apple JWKS 在 WebhookController 中每次请求加载（生产应缓存）
4. Google Webhook 缺少 OAuth bearer token 验证（目前仅解析 packageName）
5. 缺少全局 CORS 配置（移动端不需要，Web 端需补充）
