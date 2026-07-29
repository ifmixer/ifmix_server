package com.ifmix.api.core.service.iap

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.appconfig.AppConfigRepo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class IapServiceTest {

    private val appleVerifier = StubPurchaseVerifier()
    private val googleVerifier = StubPurchaseVerifier()
    private val subscriptionRepo = org.mockito.Mockito.mock(SubscriptionRepo::class.java)
    private val appConfigRepo = org.mockito.Mockito.mock(AppConfigRepo::class.java)

    private lateinit var service: IapService
    private val ctx = RequestContext(appId = "app-1")

    @BeforeEach
    fun init() {
        service = IapService(appleVerifier, googleVerifier, subscriptionRepo, appConfigRepo)
    }

    @Test
    fun `verifyPurchase - apple returns verified with tier`() {
        val config = mockAppConfig()
        whenever(appConfigRepo.getByAppId("app-1")).thenReturn(config)
        whenever(subscriptionRepo.upsert(any(), any())).thenReturn("stub-id")

        val res = service.verifyPurchase(
            ctx,
            VerifyReq(
                platform = Platform.APPLE,
                purchaseToken = "tok-123",
                productId = "premium_monthly",
            ),
        )

        assertThat(res.verified).isTrue()
        assertThat(res.productId).isEqualTo("premium_monthly")
        assertThat(res.tier).isEqualTo(com.ifmix.api.core.infra.ratelimit.Tier.PRO)
    }

    @Test
    fun `verifyPurchase - missing app config throws`() {
        whenever(appConfigRepo.getByAppId("app-1")).thenReturn(null)

        val error = assertThrows<ApiError> {
            service.verifyPurchase(
                ctx,
                VerifyReq(
                    platform = Platform.GOOGLE,
                    purchaseToken = "tok-456",
                    productId = "basic",
                ),
            )
        }
        assertThat(error.errorCode).isEqualTo(ErrorCode.APP_CONFIG_MISSING)
    }

    @Test
    fun `handleAppleNotification - decodes and does not crash when no subscription`() {
        val decoder = StubNotificationDecoder()
        val payload = """{"subscriptionPxid": "sub-abc", "type": "SUBSCRIBED"}"""

        // No existing subscription — handler should not crash
        whenever(subscriptionRepo.findActiveBySubject(any(), any()))
            .thenReturn(null)

        service.handleAppleNotification(ctx, payload, decoder)
    }

    @Test
    fun `handleGoogleNotification - decodes and does not crash when no subscription`() {
        val decoder = StubNotificationDecoder()
        val payload = """{"subscriptionPxid": "sub-xyz", "type": "RENEWED"}"""

        whenever(subscriptionRepo.findActiveBySubject(any(), any()))
            .thenReturn(null)

        service.handleGoogleNotification(ctx, payload, decoder)
    }

    private fun mockAppConfig(): com.ifmix.api.core.service.appconfig.AppConfig {
        return com.ifmix.api.core.service.appconfig.AppConfig(
            id = "app-config-1",
            appId = "app-1",
            authTenantId = null,
            revision = 1,
            appleBundleId = null,
            androidPackageName = null,
            appleAppAppleId = null,
            appleIssuerId = null,
            appleKeyId = null,
            applePrivateKey = null,
            appleServicesId = null,
            googleServiceAccount = null,
            googleClientIds = com.ifmix.api.core.service.appconfig.GoogleClientIds(),
            productTierMap = mapOf("premium_monthly" to "PRO"),
            iapEnv = "sandbox",
            createdAt = null,
            updatedAt = null,
        )
    }
}
