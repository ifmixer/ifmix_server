package com.ifmix.api.core.modules.auth

import tools.jackson.module.kotlin.jacksonObjectMapper
import com.ifmix.api.core.entity.appconfig.ConfigContent
import com.ifmix.api.core.entity.appconfig.WechatConfigValue
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ClientPlatform
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.model.AppConfigRevision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate
import java.time.Instant
import java.util.UUID

private val mapper = jacksonObjectMapper()

private fun wechatCfg(appId: String? = "wx_test_id", appSecret: String? = "wx_test_secret") = AppConfigRevision(
    id = UUID.randomUUID(),
    appId = UUID.randomUUID(),
    authTenantId = UUID.randomUUID(),
    appleBundleId = null,
    androidPackageName = null,
    revisionNumber = 1,
    enabled = true,
    slug = "test",
    note = "test",
    createdAt = Instant.now(),
    content = mapper.writeValueAsString(
        ConfigContent(
            wechat = WechatConfigValue(appId = appId, appSecret = appSecret),
        )
    ),
)

class WechatVerifierTest {

    private val restTemplate = RestTemplate()
    private val mockServer = MockRestServiceServer.createServer(restTemplate)
    private val restClient = RestClient.builder(restTemplate)
        .baseUrl("https://api.weixin.qq.com")
        .build()
    private val verifier = WechatVerifier(restClient)

    @Test
    fun `happy path - returns VerifiedProvider with unionId and userMetadata`() {
        // token endpoint
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/oauth2/access_token")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"access_token":"at_123","openid":"oid_1","unionid":"uid_1","expires_in":7200}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        // userinfo endpoint
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/userinfo")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"nickname":"张三","headimgurl":"https://img.wx.qq.com/pic.jpg","openid":"oid_1","unionid":"uid_1"}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        val result = verifier.verify(wechatCfg(), ClientPlatform.IOS, "auth_code_123")

        assertThat(result.accountId).isEqualTo("uid_1")
        assertThat(result.email).isNull()
        assertThat(result.emailVerified).isFalse()
        assertThat(result.userMetadata).containsEntry("name", "张三")
        assertThat(result.userMetadata).containsEntry("picture", "https://img.wx.qq.com/pic.jpg")
        assertThat(result.userMetadata).containsEntry("openid", "oid_1")

        mockServer.verify()
    }

    @Test
    fun `userinfo failure is non-fatal - returns success with null username`() {
        // token endpoint succeeds
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/oauth2/access_token")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"access_token":"at_123","openid":"oid_1","unionid":"uid_1","expires_in":7200}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        // userinfo endpoint fails
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/userinfo")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withServerError())

        val result = verifier.verify(wechatCfg(), ClientPlatform.IOS, "auth_code_123")

        assertThat(result.accountId).isEqualTo("uid_1")
        assertThat(result.userMetadata).containsEntry("name", null)
        assertThat(result.userMetadata).containsEntry("picture", null)
        assertThat(result.userMetadata).containsEntry("openid", "oid_1")

        mockServer.verify()
    }

    @Test
    fun `throws AUTH_PROVIDER_FAILED when errcode is non-zero`() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/oauth2/access_token")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"errcode":40029,"errmsg":"invalid code"}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        assertThatThrownBy { verifier.verify(wechatCfg(), ClientPlatform.IOS, "bad_code") }
            .isInstanceOf(ApiError::class.java)
            .satisfies({ err ->
                assertThat((err as ApiError).errorCode).isEqualTo(ErrorCode.AUTH_PROVIDER_FAILED)
            })
    }

    @Test
    fun `throws AUTH_PROVIDER_FAILED when unionid is missing`() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/oauth2/access_token")))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"access_token":"at_123","openid":"oid_1","expires_in":7200}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        assertThatThrownBy { verifier.verify(wechatCfg(), ClientPlatform.IOS, "auth_code_123") }
            .isInstanceOf(ApiError::class.java)
            .satisfies({ err ->
                assertThat((err as ApiError).errorCode).isEqualTo(ErrorCode.AUTH_PROVIDER_FAILED)
            })
    }

    @Test
    fun `throws AUTH_PROVIDER_FAILED on network error`() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/sns/oauth2/access_token")))
            .andExpect(method(HttpMethod.GET))
            .andRespond { _ -> throw java.io.IOException("connection refused") }

        assertThatThrownBy { verifier.verify(wechatCfg(), ClientPlatform.IOS, "auth_code_123") }
            .isInstanceOf(ApiError::class.java)
            .satisfies({ err ->
                assertThat((err as ApiError).errorCode).isEqualTo(ErrorCode.AUTH_PROVIDER_FAILED)
            })
    }

    @Test
    fun `throws APP_CONFIG_MISSING when wechat appId is null`() {
        assertThatThrownBy { verifier.verify(wechatCfg(appId = null), ClientPlatform.IOS, "code") }
            .isInstanceOf(ApiError::class.java)
            .satisfies({ err ->
                assertThat((err as ApiError).errorCode).isEqualTo(ErrorCode.APP_CONFIG_MISSING)
            })
    }

    @Test
    fun `throws APP_CONFIG_MISSING when wechat appSecret is null`() {
        assertThatThrownBy { verifier.verify(wechatCfg(appSecret = null), ClientPlatform.IOS, "code") }
            .isInstanceOf(ApiError::class.java)
            .satisfies({ err ->
                assertThat((err as ApiError).errorCode).isEqualTo(ErrorCode.APP_CONFIG_MISSING)
            })
    }
}
