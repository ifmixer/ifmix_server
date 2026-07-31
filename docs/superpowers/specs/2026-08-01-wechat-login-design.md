# 微信登录集成设计

- 日期：2026-08-01
- 状态：已定案
- 落点：`ifmix_server` core-api，`service/auth` 模块内新增 `WechatVerifier`
- 依赖：现有认证架构（见 `2026-07-27-auth-social-login-design.md`）

## 0. 概述与范围

在现有 Google/Apple 社交登录基础上，新增微信登录支持。客户端通过微信原生 SDK 获取
authorization code，后端使用 code 向微信 API 换取 access_token + UnionID，完成身份验证后
复用现有的 identity 落地 → AppUser 创建 → token 签发流程。

**范围（v1）：**
- 新增 `POST /customer/core/mutation/auth/wechat` 登录端点
- `WechatVerifier` 实现 `ProviderVerifier` 接口
- `AppConfig` 扩展微信凭证配置
- `LoginReq` 新增 `code` 字段

**不包含：**
- 微信小程序登录（`wx.login` 的 code2Session 流程）
- 微信公众号网页授权
- 微信扫码登录（PC 端）
- access_token 刷新（`/sns/oauth2/refresh_token`）— 微信 access_token 仅用于一次性获取用户信息

## 1. 平台与应用配置

**支持平台：** iOS + Android（微信原生 SDK）

**微信开放平台应用：** iOS/Android 共用一个应用（同一 AppID + AppSecret）

**凭证存放：** `AppConfig` 数据库表（JSONB），与 Google/Apple 凭证管理方式一致。

### AppConfig 扩展

新增 `WechatConfig` 及扁平视图字段：

```kotlin
data class WechatConfig(
    val appId: String? = null,
    val appSecret: String? = null,
)

// AppConfig 扁平视图新增
data class AppConfig(
    // ... 现有字段 ...
    val wechatAppId: String?,
    val wechatAppSecret: String?,
)

// AppConfigPatch 新增
data class AppConfigPatch(
    // ... 现有字段 ...
    val wechat: WechatConfig? = null,
)
```

数据库侧无需新 migration — JSONB 自然兼容新字段，代码层面解析即可。

## 2. 接口契约

### LoginReq 变更

```kotlin
data class LoginReq(
    val idToken: String? = null,    // Google/Apple 使用
    val code: String? = null,       // 微信使用（authorization code）
    val deviceSecret: String? = null,
)
```

**校验规则：**
- `wechat` provider：要求 `code` 非空
- `google`/`apple` provider：要求 `idToken` 非空
- 对应字段为空 → `INVALID_PARAM(400)`

### AuthService 分发

```kotlin
val credential = if (provider == "wechat") {
    req.code ?: throw ApiError(ErrorCode.INVALID_PARAM, "code required for wechat")
} else {
    req.idToken ?: throw ApiError(ErrorCode.INVALID_PARAM, "idToken required")
}
val verified = verifier.verify(config, ctx.clientPlatform, credential)
```

`ProviderVerifier.verify()` 的第三个参数语义从 "idToken" 扩展为 "provider credential"。
对 Google/Apple 是 id_token，对微信是 authorization code。接口签名不变。

## 3. WechatVerifier 实现

```kotlin
class WechatVerifier(private val restClient: RestClient) : ProviderVerifier {
    override val provider = "wechat"

    override fun verify(config: AppConfig, platform: ClientPlatform?, code: String): VerifiedProvider {
        // 校验配置
        val appId = config.wechatAppId
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING, "wechat appId not configured")
        val appSecret = config.wechatAppSecret
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING, "wechat appSecret not configured")

        // 1. code 换 access_token + openid + unionid
        val tokenRes = restClient.get()
            .uri("/sns/oauth2/access_token?appid={appid}&secret={secret}&code={code}&grant_type=authorization_code",
                 appId, appSecret, code)
            .retrieve()
            .body(WechatTokenResponse::class.java)

        if (tokenRes?.errcode != null && tokenRes.errcode != 0)
            throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "wechat: ${tokenRes.errmsg}")

        val unionId = tokenRes!!.unionid
            ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "wechat: unionid missing")

        // 2. 获取用户资料
        val userRes = restClient.get()
            .uri("/sns/userinfo?access_token={token}&openid={openid}",
                 tokenRes.accessToken, tokenRes.openid)
            .retrieve()
            .body(WechatUserResponse::class.java)

        return VerifiedProvider(
            accountId = unionId,
            email = null,
            emailVerified = false,
            phone = null,
            userMetadata = mapOf(
                "name" to userRes?.nickname,
                "picture" to userRes?.headimgurl,
                "openid" to tokenRes.openid,
            ),
        )
    }
}
```

### 配套 DTO

```kotlin
data class WechatTokenResponse(
    @JsonProperty("access_token") val accessToken: String? = null,
    val openid: String? = null,
    val unionid: String? = null,
    @JsonProperty("expires_in") val expiresIn: Int? = null,
    val errcode: Int? = null,
    val errmsg: String? = null,
)

data class WechatUserResponse(
    val nickname: String? = null,
    val headimgurl: String? = null,
    val openid: String? = null,
    val unionid: String? = null,
    val errcode: Int? = null,
    val errmsg: String? = null,
)
```

### 关键设计点

- 使用 Spring `RestClient`（同步，Virtual Threads 下无阻塞问题）
- 无 JWKS 验签 — 信任微信服务器响应（标准 OAuth code 流程）
- code 一次性（微信 API 保证同一 code 只能用一次，天然防重放）
- UnionID 作为 `providerAccountId`，openid 存入 `userMetadata` 备用

## 4. Bean 注册

```kotlin
// AuthConfig.kt

@Bean
fun wechatRestClient(): RestClient = RestClient.builder()
    .baseUrl("https://api.weixin.qq.com")
    .defaultHeaders { it.accept = listOf(MediaType.APPLICATION_JSON) }
    .requestFactory(ClientHttpRequestFactoryBuilder.simple()
        .withConnectTimeout(Duration.ofSeconds(5))
        .withReadTimeout(Duration.ofSeconds(30))
        .build())
    .build()

@Bean
fun providerVerifiers(
    googleJwtDecoder: JwtDecoder,
    appleJwtDecoder: JwtDecoder,
    wechatRestClient: RestClient,
): Map<String, ProviderVerifier> = mapOf(
    "google" to GoogleVerifier(googleJwtDecoder),
    "apple" to AppleVerifier(appleJwtDecoder),
    "wechat" to WechatVerifier(wechatRestClient),
)
```

- RestClient 单例，连接池复用
- 微信 API baseUrl 集中配置，未来加代理只需改 bean（如加 `ClientHttpRequestInterceptor` 或改 baseUrl）
- 无额外依赖（RestClient 是 Spring Boot 4 自带）

## 5. 数据库 & 身份落地

**无需新建表或改表结构。**

`core_auth_provider_identity` 表字段在微信登录时的映射：

| 字段 | 值 |
|------|-----|
| `provider` | `"wechat"` |
| `provider_account_id` | UnionID |
| `email` | `null` |
| `email_verified` | `false` |
| `user_metadata` | `{"name": "昵称", "picture": "头像URL", "openid": "xxx"}` |

**身份落地逻辑（复用现有 AuthService "No email" 分支）：**
1. 按 `(tenantId, provider="wechat", providerAccountId=UnionID)` 查找已有 ProviderIdentity
2. 有 → 复用其 AuthIdentity
3. 没有 → 创建新 AuthIdentity（email=null, displayName=微信昵称）+ 新 ProviderIdentity

**用户资料回填：** 微信返回的 `nickname` 写入 `AuthIdentity.displayName`（仅首次创建时；
后续登录不覆盖，避免微信改默认昵称后丢失用户自设的昵称）。

**Flyway migration：** 不需要。AppConfig 已是 JSONB 嵌套结构，新增 `wechat` 块代码层面解析即可。

## 6. 错误处理 & 安全

### 错误场景

| 场景 | 处理 |
|------|------|
| code 无效/过期 | 微信返回 errcode≠0 → `AUTH_PROVIDER_FAILED` |
| 微信 API 网络超时 | RestClient 30s 超时 → 异常捕获 → `AUTH_PROVIDER_FAILED` |
| unionid 缺失 | `AUTH_PROVIDER_FAILED("wechat: unionid missing")` |
| AppConfig 缺 wechat 配置 | `APP_CONFIG_MISSING` |
| code 重放 | 微信 API 返回错误 → errcode≠0 分支 |
| wechat provider 但 code 为空 | `INVALID_PARAM("code required for wechat")` |

### 安全考量

- **AppSecret 不出后端：** 仅在 WechatVerifier 服务端调用时使用，不返回给客户端
- **code 一次性：** 微信保证同一 code 只能换一次 token，天然防重放
- **日志脱敏：** 不在日志中打印 code、access_token、appSecret 原文
- **超时保护：** RestClient 配置 connect timeout 5s + read timeout 30s（海外到微信延迟考量）
- **无状态鉴权不变：** 后续请求仍使用 app 自身的 EdDSA access JWT，不依赖微信 token

## 7. 测试策略

### 单元测试（无网络）

- **WechatVerifier 正常流程：** mock RestClient 返回正常 tokenRes + userRes → 验证 VerifiedProvider 字段正确
- **WechatVerifier errcode：** mock errcode≠0 → 验证抛 AUTH_PROVIDER_FAILED
- **WechatVerifier unionid 缺失：** mock unionid=null → 验证抛错
- **WechatVerifier config 缺失：** wechatAppId/appSecret 为空 → 验证抛 APP_CONFIG_MISSING
- **AuthService 分发：** provider="wechat" + code → 调用 WechatVerifier；provider="wechat" + code=null → 400
- **身份落地 "No email" 分支：** 微信用户正确创建 identity（email=null, displayName 来自微信）

### 集成测试（Testcontainers PostgreSQL + WireMock）

- **完整 wechat 登录：** WireMock 模拟微信 HTTP → 验证 DB 中 AuthIdentity + ProviderIdentity + AppUser + RefreshToken 正确创建
- **同一 UnionID 二次登录：** 复用 identity，不重复创建
- **wechat 登录后 refresh/logout：** 正常工作（复用现有流程）

### 不做

- 不做真实微信 API 端到端测试
- 不测微信 SDK 客户端侧逻辑

## 8. 变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `service/appconfig/AppConfig.kt` | 修改 | 新增 `wechatAppId`, `wechatAppSecret`, `WechatConfig` |
| `service/appconfig/AppConfigRepo.kt` | 修改 | 解析 JSONB `wechat` 块 |
| `service/auth/AuthDtos.kt` | 修改 | `LoginReq.idToken` 改可空，新增 `code` 字段 |
| `service/auth/WechatVerifier.kt` | 新增 | `WechatVerifier` + 微信响应 DTO |
| `service/auth/AuthConfig.kt` | 修改 | 新增 `wechatRestClient` bean，注册到 verifiers map |
| `service/auth/AuthService.kt` | 修改 | credential 分发逻辑（code vs idToken） |
| 测试 | 新增 | `WechatVerifierTest` + 集成测试 wechat 场景 |

**不改的：**
- 数据库 migration
- `AuthProviderIdentity` 实体定义
- identity 落地核心流程
- token 签发 / refresh / logout 流程
- 现有 Google/Apple 任何代码

**新增依赖：** 无

## 9. 客户端集成指引（供移动端参考）

客户端调用流程：
1. 调用微信 SDK `SendAuth.Req`，scope="snsapi_userinfo"
2. 在回调中拿到 `code`
3. 调用后端 `POST /customer/core/mutation/auth/wechat`，body: `{ "code": "<code>", "deviceSecret": "<可选>" }`
4. 后端返回 `{ accessToken, refreshToken, refreshExpiresAt, deviceSecret, expiresIn, user }`
5. 后续使用 accessToken 正常鉴权

## 10. 实现计划拆分建议

建议 writing-plans 时按以下顺序拆分（每步独立可测）：
1. AppConfig 扩展（WechatConfig + 解析）
2. LoginReq 变更 + AuthService 分发逻辑
3. WechatVerifier 实现 + RestClient bean 注册
4. 单元测试
5. 集成测试（WireMock 模拟微信 API）
