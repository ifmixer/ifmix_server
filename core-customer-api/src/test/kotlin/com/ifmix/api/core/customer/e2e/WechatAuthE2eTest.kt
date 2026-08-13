package com.ifmix.api.core.customer.e2e

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.ifmix.api.core.customer.e2e.support.E2eTestBase
import com.ifmix.api.core.customer.e2e.support.TestFixtures
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired

/**
 * 微信登录 E2E 测试。
 *
 * 使用 WireMock 模拟微信开放平台 OAuth2 API，验证完整登录流程：
 * HTTP 请求 → 微信 API mock → 数据库写入 → token 签发。
 */
@DisplayName("Wechat Auth E2E")
class WechatAuthE2eTest : E2eTestBase() {

    @Autowired
    lateinit var fixtures: TestFixtures

    private val wireMock get() = wireMockServer

    @BeforeEach
    fun setup() {
        fixtures.seedMinimal()
        wireMock.resetAll()
    }

    private fun stubWechatTokenSuccess(
        code: String = "valid_code",
        accessToken: String = "wx_access_token_123",
        openid: String = "wx_openid_abc",
        unionid: String = "wx_unionid_xyz",
    ) {
        wireMock.stubFor(
            get(urlPathEqualTo("/sns/oauth2/access_token"))
                .withQueryParam("appid", equalTo("wx_test_id"))
                .withQueryParam("secret", equalTo("wx_test_secret"))
                .withQueryParam("code", equalTo(code))
                .withQueryParam("grant_type", equalTo("authorization_code"))
                .willReturn(
                    okJson(
                        """
                        {
                            "access_token": "$accessToken",
                            "openid": "$openid",
                            "unionid": "$unionid",
                            "expires_in": 7200
                        }
                        """.trimIndent()
                    )
                )
        )
    }

    private fun stubWechatUserInfo(
        accessToken: String = "wx_access_token_123",
        openid: String = "wx_openid_abc",
        nickname: String = "测试用户",
        headimgurl: String = "https://wx.qlogo.cn/test.jpg",
    ) {
        wireMock.stubFor(
            get(urlPathEqualTo("/sns/userinfo"))
                .withQueryParam("access_token", equalTo(accessToken))
                .withQueryParam("openid", equalTo(openid))
                .willReturn(
                    okJson(
                        """
                        {
                            "openid": "$openid",
                            "nickname": "$nickname",
                            "headimgurl": "$headimgurl",
                            "sex": 1,
                            "country": "CN",
                            "province": "Beijing",
                            "city": "Haidian"
                        }
                        """.trimIndent()
                    )
                )
        )
    }

    private fun stubWechatTokenError(code: String = "invalid_code") {
        wireMock.stubFor(
            get(urlPathEqualTo("/sns/oauth2/access_token"))
                .withQueryParam("code", equalTo(code))
                .willReturn(
                    okJson(
                        """
                        {
                            "errcode": 40029,
                            "errmsg": "invalid code"
                        }
                        """.trimIndent()
                    )
                )
        )
    }

    @Test
    fun `successful login creates identity and returns tokens`() {
        stubWechatTokenSuccess()
        stubWechatUserInfo()

        post("/customer/mutation/core/auth/wechat")
            .bodyValue(mapOf("code" to "valid_code"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.accessToken").isNotEmpty
            .jsonPath("$.data.refreshToken").isNotEmpty
            .jsonPath("$.data.deviceSecret").isNotEmpty
            .jsonPath("$.data.user.id").isNotEmpty
            .jsonPath("$.data.expiresIn").isNumber
    }

    @Test
    fun `second login with same unionid reuses identity`() {
        stubWechatTokenSuccess(code = "code_1")
        stubWechatUserInfo()

        // First login
        val firstResult = post("/customer/mutation/core/auth/wechat")
            .bodyValue(mapOf("code" to "code_1"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.user.id").isNotEmpty
            .returnResult()

        val firstBody = String(firstResult.responseBody!!)

        // Second login with same unionid (different code but same mock response)
        stubWechatTokenSuccess(code = "code_2")

        val secondResult = post("/customer/mutation/core/auth/wechat")
            .bodyValue(mapOf("code" to "code_2"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.data.user.id").isNotEmpty
            .returnResult()

        val secondBody = String(secondResult.responseBody!!)

        // Extract user IDs from JSON and verify they match
        val userIdRegex = """"id"\s*:\s*"([^"]+)"""".toRegex()
        val firstUserId = userIdRegex.find(firstBody)?.groupValues?.get(1)
        val secondUserId = userIdRegex.find(secondBody)?.groupValues?.get(1)

        Assertions.assertNotNull(firstUserId, "first login should return user.id")
        Assertions.assertNotNull(secondUserId, "second login should return user.id")
        Assertions.assertEquals(firstUserId, secondUserId, "same unionid should reuse identity")
    }

    @Test
    fun `missing code field returns 400`() {
        // Send idToken instead of code — wechat requires 'code'
        post("/customer/mutation/core/auth/wechat")
            .bodyValue(mapOf("idToken" to "some_token"))
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `invalid code returns AUTH_PROVIDER_FAILED`() {
        stubWechatTokenError(code = "invalid_code")

        post("/customer/mutation/core/auth/wechat")
            .bodyValue(mapOf("code" to "invalid_code"))
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody()
            .jsonPath("$.msg").value<String> { msg ->
                assert(msg.contains("wechat")) { "error should mention wechat: $msg" }
            }
    }
}
