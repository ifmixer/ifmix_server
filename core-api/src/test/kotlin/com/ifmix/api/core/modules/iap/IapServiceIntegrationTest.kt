package com.ifmix.api.core.modules.iap

import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.iap.IapService
import com.ifmix.api.core.service.iap.Platform
import com.ifmix.api.core.service.iap.VerifyReq
import com.ifmix.api.core.service.iap.VerifyRes
import com.ifmix.api.core.service.iap.SubscriptionState
import com.ifmix.api.core.service.iap.StubPurchaseVerifier
import com.ifmix.api.core.service.iap.StubNotificationDecoder
import com.ifmix.api.core.entity.iap.Subscription
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID

/**
 * IAP 服务集成测试，包含 verifyPurchase 幂等性、通知处理、状态转换等核心逻辑。
 */
@SpringBootTest(classes = [com.ifmix.api.CoreApplication::class])
@TestPropertySource(properties = ["spring.profiles.active=test"])
class IapServiceIntegrationTest {

    @Autowired
    private lateinit var iapService: IapService

    @Autowired
    private lateinit var subscriptionRepo: com.ifmix.api.core.repository.iap.SubscriptionRepository

    @Autowired
    private lateinit var storeNotificationRepo: com.ifmix.api.core.repository.iap.StoreNotificationRepository

    private val ctx = RequestContext(appId = "test-app-id", userId = "test-user")

    /** 清理测试数据 */
    private fun cleanTestData() {
        subscriptionRepo.findAll().forEach { subscription ->
            if (subscription.appId == ctx.appId.toUUIDOrNull()) {
                subscription.deletedAt = Instant.now()
            }
        }
        storeNotificationRepo.findAll().forEach { notif ->
            if (notif.appId == ctx.appId.toUUIDOrNull()) {
                notif.deletedAt = Instant.now()
            }
        }
    }

    @BeforeEach
    fun setUp() {
        cleanTestData()
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
            productId = productId,
            purchaseToken = purchaseToken,
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
        val allSubs = subscriptionRepo.findAll().filter { 
            it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null 
        }
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
            VerifyReq(platform = "APPLE", productId = productId, purchaseToken = purchaseToken)
        )

        val subPxid = subscriptionRepo.findAll()
            .firstOrNull { it.appId == ctx.appId.toUUIDOrNull() }?.subscriptionPxid ?: ""

        // Create decoded notification (simulating what decoder produces)
        val decoded = com.ifmix.api.core.service.iap.DecodedNotification(
            subscriptionPxid = subPxid,
            originalTransactionId = null,
            productId = productId,
            type = NotificationType.CANCELLED,
            timestamp = Instant.now(),
        )

        // Act - first handling
        iapService.handleAppleNotification(ctx, "{"type":"CANCELLED"}", StubNotificationDecoder())

        // Act - second handling (duplicate)
        iapService.handleAppleNotification(ctx, "{"type":"CANCELLED"}", StubNotificationDecoder())

        // Assert - notification should exist and be processed
        val notif = storeNotificationRepo.findAll()
            .firstOrNull { it.platform == "APPLE" && it.purchaseToken == purchaseToken && it.processed }
        assertThat(notif).isNotNull
        assertThat(notif!!.processed).isTrue

        // Assert - subscription should be cancelled only once (state unchanged after second call)
        val sub = subscriptionRepo.findActiveByPxid(ctx.appId.toUUIDOrNull()!!, subPxid) ?: 
                  subscriptionRepo.findAll().firstOrNull { it.subscriptionPxid == subPxid && it.deletedAt == null }
        assertThat(sub).isNotNull
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
            VerifyReq(platform = "GOOGLE", productId = productId, purchaseToken = purchaseToken)
        )

        val sub = subscriptionRepo.findAll().firstOrNull { 
            it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null 
        }!!
        val subPxid = sub.subscriptionPxid!!

        // Act
        iapService.handleGoogleNotification(ctx, "{"type":"REFUNDED"}", StubNotificationDecoder())

        // Assert: subscription should be inactive, REFUNDED status, no expiry
        val refetched = subscriptionRepo.findAll().firstOrNull { 
            it.subscriptionPxid == subPxid && it.deletedAt == null 
        }!!
        assertThat(refetched.active).isFalse
        assertThat(refetched.subStatus).isEqualTo("refunded")
        assertThat(refetched.expiryDate).isNull()
    }

    /**
     * Test subscription state transition for CANCELLED notification.
     */
    @Test
    fun `handleNotification CANCELLED - should deactivate subscription with CANCELLED status`() {
        // Arrange: Create a subscription
        val productId = "basic"
        val purchaseToken = "cancel-test-token"
        iapService.verifyPurchase(
            ctx,
            VerifyReq(platform = "APPLE", productId = productId, purchaseToken = purchaseToken)
        )

        val sub = subscriptionRepo.findAll().firstOrNull { 
            it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null 
        }!!
        val subPxid = sub.subscriptionPxid!!

        // Act
        iapService.handleAppleNotification(ctx, "{"type":"CANCELLED"}", StubNotificationDecoder())

        // Assert
        val refetched = subscriptionRepo.findAll().firstOrNull { 
            it.subscriptionPxid == subPxid && it.deletedAt == null 
        }!!
        assertThat(refetched.active).isFalse
        assertThat(refetched.subStatus).isEqualTo("cancelled")
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
            VerifyReq(platform = "GOOGLE", productId = productId, purchaseToken = purchaseToken)
        )

        val sub = subscriptionRepo.findAll().firstOrNull { 
            it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null 
        }!!
        val subPxid = sub.subscriptionPxid!!

        // Act
        iapService.handleGoogleNotification(ctx, "{"type":"EXPIRED"}", StubNotificationDecoder())

        // Assert
        val refetched = subscriptionRepo.findAll().firstOrNull { 
            it.subscriptionPxid == subPxid && it.deletedAt == null 
        }!!
        assertThat(refetched.active).isFalse
        assertThat(refetched.subStatus).isEqualTo("expired")
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
            VerifyReq(platform = "APPLE", productId = productId, purchaseToken = purchaseToken)
        )

        val sub = subscriptionRepo.findAll().firstOrNull { 
            it.appId == ctx.appId.toUUIDOrNull() && it.deletedAt == null 
        }!!
        val subPxid = sub.subscriptionPxid!!

        // Act
        iapService.handleAppleNotification(ctx, "{"type":"RENEWED"}", StubNotificationDecoder())

        // Assert
        val refetched = subscriptionRepo.findAll().firstOrNull { 
            it.subscriptionPxid == subPxid && it.deletedAt == null 
        }!!
        assertThat(refetched.active).isTrue
        assertThat(refetched.subStatus).isEqualTo("renewed")
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
            productId = productId,
            purchaseToken = "future-expiry-token",
        )

        // Act
        val res = iapService.verifyPurchase(ctx, req)

        // Assert: subscription should be active
        assertThat(res.active).isTrue
        assertThat(res.state).isEqualTo(SubscriptionState.ACTIVE)
        assertThat(res.expiryDate).isGreaterThan(Instant.now())
    }

    /**
     * Helper: Convert String to UUID safely
     */
    private fun String.toUUIDOrNull(): UUID? {
        return try { UUID.fromString(this) } catch (e: Exception) { null }
    }
}
