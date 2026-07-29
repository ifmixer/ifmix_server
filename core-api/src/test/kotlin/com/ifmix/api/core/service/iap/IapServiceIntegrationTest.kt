package com.ifmix.api.core.service.iap

import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.support.AbstractJimmerTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID

/**
 * IAP 服务集成测试，包含 verifyPurchase 幂等性、通知处理、状态转换等核心逻辑。
 */
@SpringBootTest(classes = [com.ifmix.api.CoreApplication::class])
@TestPropertySource(properties = ["spring.profiles.active=test"])
class IapServiceIntegrationTest : AbstractJimmerTest() {

    @Autowired
    private lateinit var iapService: IapService

    @Autowired
    private lateinit var subscriptionRepo: com.ifmix.api.core.repository.iap.SubscriptionRepository

    @Autowired
    private lateinit var storeNotificationRepo: com.ifmix.api.core.repository.iap.StoreNotificationRepository

    @Autowired
    private val appConfigRepo: com.ifmix.api.core.service.appconfig.AppConfigRepo = mockAppConfigRepo()

    private val ctx = RequestContext(appId = "test-app-id", userId = "test-user")

    private fun mockAppConfigRepo(): com.ifmix.api.core.service.appconfig.AppConfigRepo {
        // In a real test, we'd inject a real repo with test data
        // For simplicity, we'll rely on actual database setup
        return mockAppConfig()
    }

    private fun mockAppConfig(): com.ifmix.api.core.service.appconfig.AppConfig {
        return com.ifmix.api.core.service.appconfig.AppConfig(
            id = "config-1",
            appId = "test-app-id",
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
            productTierMap = mapOf("premium_monthly" to "PRO", "basic" to "FREE"),
            iapEnv = "test",
            createdAt = null,
            updatedAt = null,
        )
    }

    @BeforeEach
    fun setUp() {
        // Clean up test data before each test
        subscriptionRepo.findAll().forEach { subscription ->
            if (subscription.appId == ctx.appId.toUUIDOrNull()) {
                // Soft delete
                subscription.deletedAt = Instant.now()
            }
        }
        storeNotificationRepo.findAll().forEach { notif ->
            if (notif.appId == ctx.appId.toUUIDOrNull()) {
                notif.deletedAt = Instant.now()
            }
        }
    }

    /**
     * Test verifyPurchase upsert idempotency: same purchase token should not create duplicate subscription.
     */
    @Test
    fun `verifyPurchase - should return same subscription for same purchase token (idempotent)`() {
        // Arrange
        val productId = "premium_monthly"
        val purchaseToken = "test-purchase-token-123"
        val req = VerifyReq(
            platform = "GOOGLE",
            purchaseToken = purchaseToken,
            productId = productId,
        )

        // Act - first verification
        val res1 = iapService.verifyPurchase(ctx, req)

        // Assert - subscription created
        assertThat(res1.subscriptionPxid).isNotNull
        assertThat(res1.active).isTrue
        assertThat(res1.state).isEqualTo(SubscriptionState.ACTIVE)

        // Act - second verification with same token
        val res2 = iapService.verifyPurchase(ctx, req)

        // Assert - same subscriptionPxid returned (idempotent)
        assertThat(res2.subscriptionPxid).isEqualTo(res1.subscriptionPxid)
        assertThat(res2.active).isTrue
        assertThat(res2.state).isEqualTo(SubscriptionState.ACTIVE)

        // Verify only one subscription exists in DB
        val allSubs = subscriptionRepo.findAll().filter { it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null }
        assertThat(allSubs).hasSize(1)
        assertThat(allSubs[0].subscriptionPxid).isEqualTo(res1.subscriptionPxid)
    }

    /**
     * Test notification idempotency: duplicate notification should not be processed again.
     */
    @Test
    fun `handleNotification - should skip duplicate notification by platform and purchaseToken`() {
        // Arrange: First create a subscription via verifyPurchase
        val productId = "basic"
        val purchaseToken = "test-notification-token"
        iapService.verifyPurchase(
            ctx,
            VerifyReq(platform = "APPLE", purchaseToken = purchaseToken, productId = productId)
        )

        // Create a decoded notification
        val decoded = com.ifmix.api.core.service.iap.DecodedNotification(
            subscriptionPxid = subscriptionRepo.findAll()
                .firstOrNull { it.appId == ctx.appId.toUUIDOrNull() }?.subscriptionPxid ?: "",
            originalTransactionId = null,
            productId = productId,
            type = com.ifmix.api.core.service.iap.NotificationType.CANCELLED,
            timestamp = Instant.now(),
        )

        val payload = StoreNotificationPayload(
            platform = "APPLE",
            subscriptionPxid = decoded.subscriptionPxid,
            purchaseToken = purchaseToken,
            notificationType = decoded.type,
            rawPayload = "{\"type\":\"CANCELLED\"}"
        )

        // Act - first handling
        iapService.handleNotification(ctx, payload)

        // Act - second handling (duplicate)
        iapService.handleNotification(ctx, payload)

        // Assert - notification should exist and be processed
        val notif = storeNotificationRepo.findAll()
            .firstOrNull { it.platform == "APPLE" && it.purchaseToken == purchaseToken && it.processed }
        assertThat(notif).isNotNull
        assertThat(notif!!.processed).isTrue

        // Assert - subscription should be cancelled only once (state unchanged after second call)
        val sub = subscriptionRepo.findActiveByPxid(ctx.appId.toUUIDOrNull()!!, decoded.subscriptionPxid)!!
        assertThat(sub.subStatus).isEqualTo("CANCELLED")
        assertThat(sub.active).isFalse
    }

    /**
     * Test subscription state transition for REFUNDED notification.
     */
    @Test
    fun `handleNotification REFUNDED - should deactivate subscription and clear expiry`() {
        // Arrange: Create a subscription
        val productId = "premium_monthly"
        val purchaseToken = "refund-test-token"
        iapService.verifyPurchase(
            ctx,
            VerifyReq(platform = "GOOGLE", purchaseToken = purchaseToken, productId = productId)
        )

        val sub = subscriptionRepo.findAll().firstOrNull { it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null }!!
        val subPxid = sub.subscriptionPxid!!

        // Create refund notification
        val payload = StoreNotificationPayload(
            platform = "GOOGLE",
            subscriptionPxid = subPxid,
            purchaseToken = purchaseToken,
            notificationType = StoreNotificationPayload.NotificationType.REFUNDED,
            rawPayload = "{\"type\":\"REFUNDED\"}"
        )

        // Act
        iapService.handleNotification(ctx, payload)

        // Assert: subscription should be inactive, REFUNDED status, no expiry
        val updated = subscriptionRepo.findActiveByPxid(ctx.appId.toUUIDOrNull()!!, subPxid)
        // After refund, subscription may still be found but with active=false
        val refetched = subscriptionRepo.findAll().firstOrNull { it.subscriptionPxid == subPxid && it.deletedAt == null }!!
        assertThat(refetched.active).isFalse
        assertThat(refetched.subStatus).isEqualTo("REFUNDED")
        assertThat(refetched.expiryDate).isNull()
    }

    /**
     * Test subscription state transition for CANCELLED notification.
     */
    @Test
    fun `handleNotification CANCELLED - should deactivate subscription with CANCELLEd status`() {
        // Arrange: Create a subscription
        val productId = "basic"
        val purchaseToken = "cancel-test-token"
        iapService.verifyPurchase(
            ctx,
            VerifyReq(platform = "APPLE", purchaseToken = purchaseToken, productId = productId)
        )

        val sub = subscriptionRepo.findAll().firstOrNull { it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null }!!
        val subPxid = sub.subscriptionPxid!!

        val payload = StoreNotificationPayload(
            platform = "APPLE",
            subscriptionPxid = subPxid,
            purchaseToken = purchaseToken,
            notificationType = StoreNotificationPayload.NotificationType.CANCELLED,
            rawPayload = "{\"type\":\"CANCELLED\"}"
        )

        // Act
        iapService.handleNotification(ctx, payload)

        // Assert
        val refetched = subscriptionRepo.findAll().firstOrNull { it.subscriptionPxid == subPxid && it.deletedAt == null }!!
        assertThat(refetched.active).isFalse
        assertThat(refetched.subStatus).isEqualTo("CANCELLED")
    }

    /**
     * Test subscription state transition for EXPIRED notification.
     */
    @Test
    fun `handleNotification EXPIRED - should deactivate subscription with EXPIRED status`() {
        // Arrange: Create a subscription
        val productId = "premium_monthly"
        val purchaseToken = "expire-test-token"
        iapService.verifyPurchase(
            ctx,
            VerifyReq(platform = "GOOGLE", purchaseToken = purchaseToken, productId = productId)
        )

        val sub = subscriptionRepo.findAll().firstOrNull { it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null }!!
        val subPxid = sub.subscriptionPxid!!

        val payload = StoreNotificationPayload(
            platform = "GOOGLE",
            subscriptionPxid = subPxid,
            purchaseToken = purchaseToken,
            notificationType = StoreNotificationPayload.NotificationType.EXPIRED,
            rawPayload = "{\"type\":\"EXPIRED\"}"
        )

        // Act
        iapService.handleNotification(ctx, payload)

        // Assert
        val refetched = subscriptionRepo.findAll().firstOrNull { it.subscriptionPxid == subPxid && it.deletedAt == null }!!
        assertThat(refetched.active).isFalse
        assertThat(refetched.subStatus).isEqualTo("EXPIRED")
    }

    /**
     * Test subscription state transition for RENEWED notification - should remain active.
     */
    @Test
    fun `handleNotification RENEWED - subscription should remain active with RENEWED status`() {
        // Arrange: Create a subscription
        val productId = "premium_monthly"
        val purchaseToken = "renew-test-token"
        iapService.verifyPurchase(
            ctx,
            VerifyReq(platform = "APPLE", purchaseToken = purchaseToken, productId = productId)
        )

        val sub = subscriptionRepo.findAll().firstOrNull { it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null }!!
        val subPxid = sub.subscriptionPxid!!

        val payload = StoreNotificationPayload(
            platform = "APPLE",
            subscriptionPxid = subPxid,
            purchaseToken = purchaseToken,
            notificationType = StoreNotificationPayload.NotificationType.RENEWED,
            rawPayload = "{\"type\":\"RENEWED\"}"
        )

        // Act
        iapService.handleNotification(ctx, payload)

        // Assert
        val refetched = subscriptionRepo.findAll().firstOrNull { it.subscriptionPxid == subPxid && it.deletedAt == null }!!
        assertThat(refetched.active).isTrue
        assertThat(refetched.subStatus).isEqualTo("RENEWED")
    }

    /**
     * Test verifyPurchase returns correct state based on expiry date.
     */
    @Test
    fun `verifyPurchase - should return ACTIVE state when expiry is in future`() {
        // Arrange: Use a product with future expiry from stub verifier
        val productId = "premium_monthly"
        val req = VerifyReq(
            platform = "GOOGLE",
            purchaseToken = "future-expiry-token",
            productId = productId,
        )

        // Act
        val res = iapService.verifyPurchase(ctx, req)

        // Assert: subscription should be active
        assertThat(res.active).isTrue
        assertThat(res.state).isEqualTo(SubscriptionState.ACTIVE)
        assertThat(res.expiryDate).isNotNull
        assertThat(res.expiryDate!).isAfter(java.time.Instant.now())
    }

    /**
     * Test verifyPurchase creates subscription with correct productId and platform info.
     */
    @Test
    fun `verifyPurchase - should persist subscription with correct properties`() {
        // Arrange
        val productId = "basic"
        val req = VerifyReq(
            platform = "APPLE",
            purchaseToken = "prod-check-token",
            productId = productId,
        )

        // Act
        iapService.verifyPurchase(ctx, req)

        // Assert: subscription exists with correct values
        val sub = subscriptionRepo.findAll().firstOrNull { it.appId == ctx.appId.toUUIDOrNull() && it.productId == productId && it.deletedAt == null }
        assertThat(sub).isNotNull
        assertThat(sub!!.productId).isEqualTo(productId)
        assertThat(sub!!.platform).isEqualTo("APPLE")
        assertThat(sub!!.active).isTrue
        assertThat(sub!!.subscriptionPxid).isNotNull
    }
}
