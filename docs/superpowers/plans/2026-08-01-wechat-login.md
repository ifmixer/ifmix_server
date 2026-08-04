# 微信登录集成 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在现有 Google/Apple 社交登录基础上新增微信登录，客户端传 authorization code，后端换取 UnionID 完成身份验证。

**架构：** 新增 `WechatVerifier` 实现现有 `ProviderVerifier` 接口，内部通过 `RestClient` 调用微信 API (`/sns/oauth2/access_token` + `/sns/userinfo`)。复用现有 identity 落地 → token 签发流程，改动集中在 6 个文件。

**技术栈：** Kotlin / Spring Boot 4.1 / RestClient / Jimmer / PostgreSQL / JUnit 5 + WireMock

**规格文档：** `docs/superpowers/specs/2026-08-01-wechat-login-design.md`

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `core-api/src/main/kotlin/com/ifmix/api/core/entity/appconfig/AppConfig.kt` | 修改 | 新增 `WechatConfigValue` data class + entity 增加 `wechatConfig` JSONB 字段 |
| `core-api/src/main/kotlin/com/ifmix/api/core/service/appconfig/AppConfig.kt` | 修改 | 扁平视图增加 `wechatAppId`, `wechatAppSecret` |
| `core-api/src/main/kotlin/com/ifmix/api/core/service/appconfig/AppConfigRepo.kt` | 修改 | `toFlat()` 映射 wechat 字段 |
| `core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthDtos.kt` | 修改 | `LoginReq.idToken` 改可空，新增 `code` 字段 |
| `core-api/src/main/kotlin/com/ifmix/api/core/service/auth/WechatVerifier.kt` | 新建 | `WechatVerifier` + 微信响应 DTO |
| `core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthConfig.kt` | 修改 | 注册 `wechatRestClient` bean + verifiers map 加 `"wechat"` |
| `core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthService.kt` | 修改 | `loginWithProvider()` credential 分发逻辑 |
| `core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerAuthController.kt` | 修改 | 新增 `/mutation/auth/wechat` 路由 |
| `core-api/src/main/resources/db/migration/V9__add_wechat_config.sql` | 新建 | AppConfig 表增加 `wechat_config` JSONB 列 |
| `core-api/src/test/kotlin/com/ifmix/api/core/modules/auth/WechatVerifierTest.kt` | 新建 | WechatVerifier 单元测试 |
| `core-api/src/test/kotlin/com/ifmix/api/core/e2e/WechatAuthE2eTest.kt` | 新建 | 微信登录 E2E 集成测试 |
| `core-api/src/test/kotlin/com/ifmix/api/core/e2e/support/TestFixtures.kt` | 修改 | seed 数据增加 wechat 配置 |

---

## 任务 1：数据库 migration + AppConfig entity 扩展

**文件：**
- 创建：`core-api/src/main/resources/db/migration/V9__add_wechat_config.sql`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/entity/appconfig/AppConfig.kt`

- [ ] **步骤 1：创建 Flyway migration**

创建文件 `core-api/src/main/resources/db/migration/V9__add_wechat_config.sql`：

```sql
-- V9: Add wechat_config JSONB column to core_app_config
ALTER TABLE core_app_config
    ADD COLUMN wechat_config JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN core_app_config.wechat_config IS '微信开放平台配置 {"appId":"wx...","appSecret":"..."}';
```

- [ ] **步骤 2：在 entity 中新增 WechatConfigValue + 字段**

修改 `core-api/src/main/kotlin/com/ifmix/api/core/entity/appconfig/AppConfig.kt`，在文件末尾 `IapConfigValue` 之后追加：

```kotlin
data class WechatConfigValue(
    val appId: String? = null,
    val appSecret: String? = null,
)
```

在 `AppConfig` interface 中（`iapConfig` 字段之后）新增：

```kotlin
    /** JSONB — 微信开放平台配置 */
    @Serialized
    val wechatConfig: WechatConfigValue
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/resources/db/migration/V9__add_wechat_config.sql \
        core-api/src/main/kotlin/com/ifmix/api/core/entity/appconfig/AppConfig.kt
git commit -m "feat(auth): add wechat_config JSONB column and entity field"
```

---

## 任务 2：AppConfig 扁平视图 + Repo 映射

**文件：**
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/service/appconfig/AppConfig.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/service/appconfig/AppConfigRepo.kt`

- [ ] **步骤 1：扁平视图新增 wechat 字段**

修改 `core-api/src/main/kotlin/com/ifmix/api/core/service/appconfig/AppConfig.kt`。

在 `AppConfig` data class 中（`iapEnv` 之后，`createdAt` 之前）新增：

```kotlin
    val wechatAppId: String?,
    val wechatAppSecret: String?,
```

在文件末尾新增：

```kotlin
data class WechatConfig(
    val appId: String? = null,
    val appSecret: String? = null,
)
```

在 `AppConfigPatch` data class 中新增：

```kotlin
    val wechat: WechatConfig? = null,
```

- [ ] **步骤 2：Repo 的 toFlat() 映射 wechat**

修改 `core-api/src/main/kotlin/com/ifmix/api/core/service/appconfig/AppConfigRepo.kt`。

在 `toFlat()` 方法中，在 `val iap = config.iapConfig` 之后新增：

```kotlin
        val wechat = config.wechatConfig
```

在 `AppConfig(...)` 构造中，在 `iapEnv = iap.env ?: "production",` 之后新增：

```kotlin
            wechatAppId = wechat.appId,
            wechatAppSecret = wechat.appSecret,
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/service/appconfig/AppConfig.kt \
        core-api/src/main/kotlin/com/ifmix/api/core/service/appconfig/AppConfigRepo.kt
git commit -m "feat(auth): extend AppConfig flat view with wechat fields"
```

---

## 任务 3：LoginReq 变更 + AuthService credential 分发

**文件：**
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthDtos.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthService.kt`

- [ ] **步骤 1：修改 LoginReq**

修改 `core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthDtos.kt`。

将：
```kotlin
data class LoginReq(
    val idToken: String,
    val deviceSecret: String? = null,
)
```

替换为：
```kotlin
data class LoginReq(
    val idToken: String? = null,
    val code: String? = null,
    val deviceSecret: String? = null,
)
```

- [ ] **步骤 2：修改 AuthService.loginWithProvider() 分发逻辑**

修改 `core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthService.kt`。

在 `loginWithProvider()` 方法中，找到：
```kotlin
        // 3. Verify id_token
        val verified = verifier.verify(config, ctx.clientPlatform, req.idToken!!)
```

替换为：
```kotlin
        // 3. Resolve credential (code for wechat, idToken for others)
        val credential = if (provider == "wechat") {
            req.code ?: throw ApiError(ErrorCode.INVALID_PARAM, "code required for wechat login")
        } else {
            req.idToken ?: throw ApiError(ErrorCode.INVALID_PARAM, "idToken required")
        }
        val verified = verifier.verify(config, ctx.clientPlatform, credential)
```

- [ ] **步骤 3：编译验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthDtos.kt \
        core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthService.kt
git commit -m "feat(auth): add code field to LoginReq and credential dispatch logic"
```

---

## 任务 4：WechatVerifier 实现

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/service/auth/WechatVerifier.kt`

- [ ] **步骤 1：创建 WechatVerifier.kt**

创建文件 `core-api/src/main/kotlin/com/ifmix/api/core/service/auth/WechatVerifier.kt`：

```kotlin
package com.ifmix.api.core.modules.auth

import com.fasterxml.jackson.annotation.JsonProperty
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ClientPlatform
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.modules.app.AppConfig
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * 微信开放平台 OAuth2 验证器。
 *
 * 流程：客户端传 authorization code → 后端用 code+appSecret 向微信换 access_token+unionid
 * → 再用 access_token 获取用户资料（昵称/头像）。
 */
class WechatVerifier(private val restClient: RestClient) : ProviderVerifier {
    override val provider = "wechat"

    override fun verify(config: AppConfig, platform: ClientPlatform?, credential: String): VerifiedProvider {
        val appId = config.wechatAppId
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING, "wechat appId not configured")
        val appSecret = config.wechatAppSecret
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING, "wechat appSecret not configured")

        // 1. Exchange code for access_token + openid + unionid
        val tokenRes = try {
            restClient.get()
                .uri("/sns/oauth2/access_token?appid={appid}&secret={secret}&code={code}&grant_type=authorization_code",
                    appId, appSecret, credential)
                .retrieve()
                .body(WechatTokenResponse::class.java)
        } catch (e: RestClientException) {
            throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "wechat api error: ${e.message}")
        }

        if (tokenRes == null || (tokenRes.errcode != null && tokenRes.errcode != 0)) {
            throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "wechat: ${tokenRes?.errmsg ?: "empty response"}")
        }

        val unionId = tokenRes.unionid
            ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "wechat: unionid missing, ensure app is bound to open platform")

        // 2. Fetch user profile
        val userRes = try {
            restClient.get()
                .uri("/sns/userinfo?access_token={token}&openid={openid}",
                    tokenRes.accessToken, tokenRes.openid)
                .retrieve()
                .body(WechatUserResponse::class.java)
        } catch (e: RestClientException) {
            // User info fetch failure is non-fatal — proceed without profile
            null
        }

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

/** 微信 /sns/oauth2/access_token 响应 */
data class WechatTokenResponse(
    @JsonProperty("access_token") val accessToken: String? = null,
    val openid: String? = null,
    val unionid: String? = null,
    @JsonProperty("expires_in") val expiresIn: Int? = null,
    val errcode: Int? = null,
    val errmsg: String? = null,
)

/** 微信 /sns/userinfo 响应 */
data class WechatUserResponse(
    val nickname: String? = null,
    val headimgurl: String? = null,
    val openid: String? = null,
    val unionid: String? = null,
    val sex: Int? = null,
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val errcode: Int? = null,
    val errmsg: String? = null,
)
```

- [ ] **步骤 2：编译验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/service/auth/WechatVerifier.kt
git commit -m "feat(auth): implement WechatVerifier with code-based OAuth flow"
```

---

## 任务 5：AuthConfig bean 注册 + Controller 路由

**文件：**
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthConfig.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerAuthController.kt`

- [ ] **步骤 1：新增 wechatRestClient bean**

修改 `core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthConfig.kt`。

在 import 区域新增：
```kotlin
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration
```

在 `appleJwtDecoder()` bean 之后、`providerVerifiers()` 之前新增：

```kotlin
    @Bean
    fun wechatRestClient(): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(30))
        }
        return RestClient.builder()
            .baseUrl("https://api.weixin.qq.com")
            .requestFactory(factory)
            .build()
    }
```

- [ ] **步骤 2：修改 providerVerifiers bean 注册微信**

将现有 `providerVerifiers` bean 方法签名和实现改为：

```kotlin
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

- [ ] **步骤 3：新增 /mutation/auth/wechat 路由**

修改 `core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerAuthController.kt`。

在 `apple()` 方法之后新增：

```kotlin
    @Operation(summary = "微信登录", description = "提交微信 authorization code 换取平台 JWT。免鉴权。")
    @PostMapping("/mutation/auth/wechat")
    fun wechat(ctx: RequestContext, @Valid @RequestBody req: LoginReq): LoginRes =
        authService.loginWithProvider(ctx, "wechat", req)
```

- [ ] **步骤 4：编译验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthConfig.kt \
        core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerAuthController.kt
git commit -m "feat(auth): register wechat RestClient bean and add /auth/wechat route"
```

---

## 任务 6：WechatVerifier 单元测试

**文件：**
- 创建：`core-api/src/test/kotlin/com/ifmix/api/core/modules/auth/WechatVerifierTest.kt`

- [ ] **步骤 1：编写测试文件**

创建文件 `core-api/src/test/kotlin/com/ifmix/api/core/modules/auth/WechatVerifierTest.kt`：

```kotlin
package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.modules.app.AppConfig
import com.ifmix.api.core.modules.app.GoogleClientIds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate

private fun wechatCfg(appId: String? = "wx_test_id", appSecret: String? = "wx_test_secret") = AppConfig(
    id = "c", appId = "app1", authTenantId = "t1", revision = 1,
    appleBundleId = null, androidPackageName = null,
    appleAppAppleId = null, appleIssuerId = null, appleKeyId = null, applePrivateKey = null,
    appleServicesId = null,
    googleServiceAccount = null, googleClientIds = GoogleClientIds(),
    productTierMap = emptyMap(), iapEnv = "production",
    wechatAppId = appId, wechatAppSecret = appSecret,
    createdAt = null, updatedAt = null,
)

@DisplayName("WechatVerifier")
class WechatVerifierTest {

    private lateinit var mockServer: MockRestServiceServer
    private lateinit var verifier: WechatVerifier

    @BeforeEach
    fun setup() {
        val restTemplate = RestTemplate()
        mockServer = MockRestServiceServer.createServer(restTemplate)
        val restClient = RestClient.builder(restTemplate)
            .baseUrl("https://api.weixin.qq.com")
            .build()
        verifier = WechatVerifier(restClient)
    }

    @Nested
    @DisplayName("Happy path")
    inner class HappyPath {

        @Test
        fun `returns VerifiedProvider with unionId on success`() {
            mockServer.expect(requestTo { it.path.contains("/sns/oauth2/access_token") })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                    {"access_token":"at_123","openid":"oid_abc","unionid":"uid_xyz","expires_in":7200}
                """.trimIndent(), MediaType.APPLICATION_JSON))

            mockServer.expect(requestTo { it.path.contains("/sns/userinfo") })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                    {"nickname":"张三","headimgurl":"https://wx.qlogo.cn/abc","openid":"oid_abc","unionid":"uid_xyz"}
                """.trimIndent(), MediaType.APPLICATION_JSON))

            val result = verifier.verify(wechatCfg(), null, "auth_code_123")

            assertThat(result.accountId).isEqualTo("uid_xyz")
            assertThat(result.email).isNull()
            assertThat(result.emailVerified).isFalse()
            assertThat(result.userMetadata["name"]).isEqualTo("张三")
            assertThat(result.userMetadata["picture"]).isEqualTo("https://wx.qlogo.cn/abc")
            assertThat(result.userMetadata["openid"]).isEqualTo("oid_abc")

            mockServer.verify()
        }

        @Test
        fun `succeeds even when userinfo call fails`() {
            mockServer.expect(requestTo { it.path.contains("/sns/oauth2/access_token") })
                .andRespond(withSuccess("""
                    {"access_token":"at_123","openid":"oid_abc","unionid":"uid_xyz","expires_in":7200}
                """.trimIndent(), MediaType.APPLICATION_JSON))

            mockServer.expect(requestTo { it.path.contains("/sns/userinfo") })
                .andRespond(withServerError())

            val result = verifier.verify(wechatCfg(), null, "auth_code_123")

            assertThat(result.accountId).isEqualTo("uid_xyz")
            assertThat(result.userMetadata["name"]).isNull()

            mockServer.verify()
        }
    }

    @Nested
    @DisplayName("Error cases")
    inner class ErrorCases {

        @Test
        fun `throws AUTH_PROVIDER_FAILED when errcode is non-zero`() {
            mockServer.expect(requestTo { it.path.contains("/sns/oauth2/access_token") })
                .andRespond(withSuccess("""
                    {"errcode":40029,"errmsg":"invalid code"}
                """.trimIndent(), MediaType.APPLICATION_JSON))

            assertThatThrownBy { verifier.verify(wechatCfg(), null, "bad_code") }
                .isInstanceOf(ApiError::class.java)
                .satisfies({ err ->
                    assertThat((err as ApiError).errorCode).isEqualTo(ErrorCode.AUTH_PROVIDER_FAILED)
                })
        }

        @Test
        fun `throws AUTH_PROVIDER_FAILED when unionid is missing`() {
            mockServer.expect(requestTo { it.path.contains("/sns/oauth2/access_token") })
                .andRespond(withSuccess("""
                    {"access_token":"at_123","openid":"oid_abc","expires_in":7200}
                """.trimIndent(), MediaType.APPLICATION_JSON))

            assertThatThrownBy { verifier.verify(wechatCfg(), null, "code_no_union") }
                .isInstanceOf(ApiError::class.java)
                .satisfies({ err ->
                    assertThat((err as ApiError).errorCode).isEqualTo(ErrorCode.AUTH_PROVIDER_FAILED)
                })
        }

        @Test
        fun `throws AUTH_PROVIDER_FAILED when network error occurs`() {
            mockServer.expect(requestTo { it.path.contains("/sns/oauth2/access_token") })
                .andRespond(withServerError())

            assertThatThrownBy { verifier.verify(wechatCfg(), null, "code") }
                .isInstanceOf(ApiError::class.java)
        }

        @Test
        fun `throws APP_CONFIG_MISSING when wechat appId is null`() {
            assertThatThrownBy { verifier.verify(wechatCfg(appId = null), null, "code") }
                .isInstanceOf(ApiError::class.java)
                .satisfies({ err ->
                    assertThat((err as ApiError).errorCode).isEqualTo(ErrorCode.APP_CONFIG_MISSING)
                })
        }

        @Test
        fun `throws APP_CONFIG_MISSING when wechat appSecret is null`() {
            assertThatThrownBy { verifier.verify(wechatCfg(appSecret = null), null, "code") }
                .isInstanceOf(ApiError::class.java)
                .satisfies({ err ->
                    assertThat((err as ApiError).errorCode).isEqualTo(ErrorCode.APP_CONFIG_MISSING)
                })
        }
    }
}
```

- [ ] **步骤 2：运行测试验证通过**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.modules.auth.WechatVerifierTest" -v`
预期：所有 7 个测试 PASS

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/test/kotlin/com/ifmix/api/core/modules/auth/WechatVerifierTest.kt
git commit -m "test(auth): add WechatVerifier unit tests"
```

---

## 任务 7：更新现有 ProviderVerifierTest 适配 AppConfig 变更

**文件：**
- 修改：`core-api/src/test/kotlin/com/ifmix/api/core/modules/auth/ProviderVerifierTest.kt`

- [ ] **步骤 1：更新 cfg() 工厂方法**

修改 `core-api/src/test/kotlin/com/ifmix/api/core/modules/auth/ProviderVerifierTest.kt`。

`cfg()` 函数的 `AppConfig(...)` 调用中，在 `iapEnv = "production"` 之后、`createdAt = null` 之前新增：

```kotlin
    wechatAppId = null, wechatAppSecret = null,
```

- [ ] **步骤 2：运行现有测试验证不破坏**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.modules.auth.ProviderVerifierTest" -v`
预期：所有 5 个现有测试 PASS

- [ ] **步骤 3：Commit**

```bash
git add core-api/src/test/kotlin/com/ifmix/api/core/modules/auth/ProviderVerifierTest.kt
git commit -m "test(auth): update ProviderVerifierTest for AppConfig wechat fields"
```

---

## 任务 8：E2E 集成测试（WireMock 模拟微信 API）

**文件：**
- 修改：`core-api/src/test/kotlin/com/ifmix/api/core/e2e/support/TestFixtures.kt`
- 创建：`core-api/src/test/kotlin/com/ifmix/api/core/e2e/WechatAuthE2eTest.kt`

- [ ] **步骤 1：更新 TestFixtures seed 数据**

修改 `core-api/src/test/kotlin/com/ifmix/api/core/e2e/support/TestFixtures.kt`。

在 `seedMinimal()` 的 `AppConfig` INSERT 语句中，增加 `wechat_config` 列。将原 SQL：

```sql
INSERT INTO core_app_config (id, app_id, auth_tenant_id, apple_bundle_id, android_package_name,
                             apple_config, google_config, iap_config, revision, created_at, updated_at)
VALUES (?::uuid, ?::uuid, ?::uuid, 'com.ifmix.test', 'com.ifmix.test',
        '...'::jsonb, '...'::jsonb, '...'::jsonb,
        1, now(), now())
```

改为包含 `wechat_config`：

```sql
INSERT INTO core_app_config (id, app_id, auth_tenant_id, apple_bundle_id, android_package_name,
                             apple_config, google_config, iap_config, wechat_config, revision, created_at, updated_at)
VALUES (?::uuid, ?::uuid, ?::uuid, 'com.ifmix.test', 'com.ifmix.test',
        '{"appAppleId":"123","issuerId":"iss","keyId":"kid","privateKey":"pk","servicesId":"sid"}'::jsonb,
        '{"serviceAccount":"sa","clientIds":{"ios":"ios-id","android":"android-id","web":"web-id"}}'::jsonb,
        '{"productTierMap":{"pro_monthly":"PRO"},"env":"sandbox"}'::jsonb,
        '{"appId":"wx_test_id","appSecret":"wx_test_secret"}'::jsonb,
        1, now(), now())
ON CONFLICT DO NOTHING
```

- [ ] **步骤 2：确认 WireMock 依赖存在**

检查 `core-api/build.gradle.kts` 是否包含 WireMock 测试依赖。如果不存在，在 `dependencies` 中添加：

```kotlin
testImplementation("org.wiremock:wiremock-standalone:3.12.1")
```

- [ ] **步骤 3：创建 WechatAuthE2eTest**

创建文件 `core-api/src/test/kotlin/com/ifmix/api/core/e2e/WechatAuthE2eTest.kt`：

```kotlin
package com.ifmix.api.core.e2e

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.ifmix.api.core.e2e.support.E2eTestBase
import com.ifmix.api.core.e2e.support.TestFixtures
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 微信登录 E2E 测试。
 *
 * 使用 WireMock 模拟微信 API，验证完整登录流程：
 * code → access_token → userinfo → identity 创建 → token 签发
 */
@DisplayName("Wechat Auth E2E")
class WechatAuthE2eTest : E2eTestBase() {

    @Autowired
    lateinit var fixtures: TestFixtures

    companion object {
        private val wireMock = WireMockServer(wireMockConfig().dynamicPort())

        @BeforeAll
        @JvmStatic
        fun startWireMock() {
            wireMock.start()
        }

        @AfterAll
        @JvmStatic
        fun stopWireMock() {
            wireMock.stop()
        }

        @DynamicPropertySource
        @JvmStatic
        fun configureWechat(registry: DynamicPropertyRegistry) {
            // 这里需要覆盖 wechatRestClient 的 baseUrl 为 WireMock
            // 方式：通过一个可配置属性，或者在 E2E profile 中用条件 bean
        }
    }

    @BeforeEach
    fun setup() {
        fixtures.seedMinimal()
        wireMock.resetAll()
    }

    @Nested
    @DisplayName("POST /customer/core/mutation/auth/wechat")
    inner class WechatLogin {

        @Test
        fun `successful login creates identity and returns tokens`() {
            // Stub token exchange
            wireMock.stubFor(get(urlPathEqualTo("/sns/oauth2/access_token"))
                .willReturn(okJson("""
                    {"access_token":"at_mock","openid":"oid_test","unionid":"uid_test","expires_in":7200}
                """.trimIndent())))

            // Stub userinfo
            wireMock.stubFor(get(urlPathEqualTo("/sns/userinfo"))
                .willReturn(okJson("""
                    {"nickname":"测试用户","headimgurl":"https://wx.qlogo.cn/test","openid":"oid_test","unionid":"uid_test"}
                """.trimIndent())))

            post("/customer/core/mutation/auth/wechat")
                .bodyValue(mapOf("code" to "valid_code"))
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.data.accessToken").isNotEmpty
                .jsonPath("$.data.refreshToken").isNotEmpty
                .jsonPath("$.data.deviceSecret").isNotEmpty
                .jsonPath("$.data.user.id").isNotEmpty
        }

        @Test
        fun `second login with same unionid reuses identity`() {
            wireMock.stubFor(get(urlPathEqualTo("/sns/oauth2/access_token"))
                .willReturn(okJson("""
                    {"access_token":"at_mock","openid":"oid_test","unionid":"uid_test","expires_in":7200}
                """.trimIndent())))
            wireMock.stubFor(get(urlPathEqualTo("/sns/userinfo"))
                .willReturn(okJson("""
                    {"nickname":"测试用户","headimgurl":"https://wx.qlogo.cn/test","openid":"oid_test","unionid":"uid_test"}
                """.trimIndent())))

            // First login
            val firstUserId = post("/customer/core/mutation/auth/wechat")
                .bodyValue(mapOf("code" to "code1"))
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.data.user.id").returnResult().responseBody

            // Second login — same unionid
            post("/customer/core/mutation/auth/wechat")
                .bodyValue(mapOf("code" to "code2"))
                .exchange()
                .expectStatus().isOk
                .expectBody()
                .jsonPath("$.data.user.id").isEqualTo(firstUserId.toString())
        }

        @Test
        fun `missing code field returns 400`() {
            post("/customer/core/mutation/auth/wechat")
                .bodyValue(mapOf("idToken" to "xxx"))
                .exchange()
                .expectStatus().isBadRequest
        }

        @Test
        fun `invalid code returns AUTH_PROVIDER_FAILED`() {
            wireMock.stubFor(get(urlPathEqualTo("/sns/oauth2/access_token"))
                .willReturn(okJson("""{"errcode":40029,"errmsg":"invalid code"}""")))

            post("/customer/core/mutation/auth/wechat")
                .bodyValue(mapOf("code" to "invalid"))
                .exchange()
                .expectStatus().isUnauthorized
                .expectBody()
                .jsonPath("$.msg").value<String> { msg ->
                    assert(msg.contains("wechat"))
                }
        }
    }
}
```

**注意：** E2E 测试中需要将 `wechatRestClient` 的 baseUrl 指向 WireMock。实现方式为在 `AuthConfig` 中将 baseUrl 抽为可配置属性：

在 `AuthConfig.kt` 中将 `wechatRestClient()` 改为：
```kotlin
    @Bean
    fun wechatRestClient(
        @Value("\${app.auth.wechat-api-url:https://api.weixin.qq.com}") baseUrl: String,
    ): RestClient {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(30))
        }
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .build()
    }
```

然后在 E2E `DynamicPropertySource` 中设置：
```kotlin
        @DynamicPropertySource
        @JvmStatic
        fun configureWechat(registry: DynamicPropertyRegistry) {
            registry.add("app.auth.wechat-api-url") { wireMock.baseUrl() }
        }
```

- [ ] **步骤 4：运行 E2E 测试**

运行：`./gradlew :core-api:test --tests "com.ifmix.api.core.e2e.WechatAuthE2eTest" -v`
预期：所有 4 个测试 PASS

- [ ] **步骤 5：Commit**

```bash
git add core-api/src/test/kotlin/com/ifmix/api/core/e2e/WechatAuthE2eTest.kt \
        core-api/src/test/kotlin/com/ifmix/api/core/e2e/support/TestFixtures.kt \
        core-api/src/main/kotlin/com/ifmix/api/core/service/auth/AuthConfig.kt
git commit -m "test(auth): add wechat login E2E tests with WireMock"
```

---

## 任务 9：全量测试 + 最终验证

**文件：** 无新增/修改

- [ ] **步骤 1：运行全量测试套件**

运行：`./gradlew :core-api:test`
预期：BUILD SUCCESSFUL，所有现有测试 + 新增测试全部 PASS

- [ ] **步骤 2：验证编译无警告**

运行：`./gradlew :core-api:compileKotlin --warning-mode all`
预期：无新增编译警告

- [ ] **步骤 3：确认 git 状态干净**

```bash
git status
```
预期：`nothing to commit, working tree clean`

---

## 自检结果

### 规格覆盖度

| 规格章节 | 对应任务 |
|----------|----------|
| §1 AppConfig 扩展 | 任务 1 + 任务 2 |
| §2 接口契约 (LoginReq + 分发) | 任务 3 |
| §3 WechatVerifier 实现 | 任务 4 |
| §4 Bean 注册 | 任务 5 |
| §5 数据库 & 身份落地 | 任务 1（migration） + 任务 4（逻辑复用现有分支） |
| §6 错误处理 & 安全 | 任务 4（实现） + 任务 6（测试验证） |
| §7 测试策略 | 任务 6（单元） + 任务 8（E2E） |
| §8 变更清单 | 全部任务覆盖 |
| §9 客户端集成指引 | 任务 5（路由暴露） |

✓ 所有规格章节已覆盖。

### 占位符扫描

✓ 无 TODO、待定、"后续实现" 等占位符。

### 类型一致性

- `AppConfig` 新增字段名：`wechatAppId`, `wechatAppSecret` — 在任务 2（定义）、任务 4（使用）、任务 6（测试）中一致 ✓
- `LoginReq.code` — 在任务 3（定义）、任务 5（路由传入）、任务 8（E2E 调用）中一致 ✓
- `WechatVerifier` 类名 — 在任务 4（创建）、任务 5（注册）、任务 6（测试）中一致 ✓
- `WechatTokenResponse`, `WechatUserResponse` — 在任务 4 中定义，任务 6 中 mock 返回的 JSON 字段与之匹配 ✓
- `ErrorCode.AUTH_PROVIDER_FAILED`, `ErrorCode.APP_CONFIG_MISSING` — 复用现有枚举，任务 4 和任务 6 一致 ✓
