package com.ifmix.api.core.service.iap

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.repository.iap.SubscriptionRepository
import com.ifmix.api.core.repository.iap.StoreNotificationRepository
import com.ifmix.api.core.entity.iap.Subscription
import com.ifmix.api.core.entity.iap.SubscriptionDraft
import com.ifmix.api.core.entity.iap.StoreNotification
import com.ifmix.api.core.service.appconfig.AppConfigRepo
import com.ifmix.api.core.service.iap.Platform
import com.ifmix.api.core.service.iap.PurchaseVerifier
import com.ifmix.api.core.service.iap.VerifyInput
import com.ifmix.api.core.service.iap.VerifyResult
import com.ifmix.api.core.service.iap.SubStatus
import com.ifmix.api.core.service.iap.NotificationType
import com.ifmix.api.core.service.iap.statusFromExpiry
import com.ifmix.api.core.service.iap.tierOf
import com.ifmix.api.core.service.iap.DecodedNotification
import com.ifmix.api.core.infra.db.UuidV7
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * IAP 服务：购买验证、订阅状态管理、商店通知处理。
 */
@org.springframework.stereotype.Service
open class IapService(
    @org.springframework.beans.factory.annotation.Qualifier("appleVerifier")
    private val appleVerifier: PurchaseVerifier,
    @org.springframework.beans.factory.annotation.Qualifier("googleVerifier")
    private val googleVerifier: PurchaseVerifier,
    private val subscriptionRepo: SubscriptionRepository,
    private val storeNotificationRepo: StoreNotificationRepository,
    private val appConfigRepo: AppConfigRepo,
) {

    // Map of platform name to verifier
    private val verifierMap: Map<String, PurchaseVerifier> = hashMapOf(
        "APPLE" to appleVerifier,
        "GOOGLE" to googleVerifier
    )

    /**
     * 验证一笔购买并写入订阅记录。
     */
    @Transactional
    fun verifyPurchase(ctx: RequestContext, req: VerifyReq): VerifyRes {
        val appId = ctx.appId.toUUIDOrNull() ?: throw ApiError(ErrorCode.INVALID_REQUEST)

        // 1. Select verifier based on platform (already an enum, no parsing needed)
        val verifier = verifierMap[req.platform.name] ?: throw ApiError(ErrorCode.INVALID_REQUEST, "Unknown platform: ${req.platform}")

        // 2. Verify purchase using the selected verifier
        val purchaseToken = req.purchaseToken ?: req.signedTransaction
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "purchaseToken or signedTransaction required")
        val input = VerifyInput(
            platform = req.platform,
            purchaseToken = purchaseToken,
            productId = req.productId,
            appId = appId.toString()
        )
        val verifyResult = verifier.verify(input)

        // 3. Map productId to product tier from AppConfig
        val config = appConfigRepo.getByAppId(appId.toString()) ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val productTierMap = config.productTierMap
        val tier = tierOf(req.productId, productTierMap) ?: com.ifmix.api.core.infra.ratelimit.Tier.FREE

        // 4. Determine subscription PxID - use originalTransactionId or generate one
        val subscriptionPxid = verifyResult.originalTransactionId ?: run {
            "pxid-${req.platform.name}-${UuidV7.generate()}"
        }

        // Check for existing active subscription with same pxid (idempotency)
        val existingSub = subscriptionRepo.findActiveByPxid(appId, subscriptionPxid)
        if (existingSub != null) {
            return VerifyRes(
                expiresAt = existingSub.expiryDate?.toEpochMilli(),
                state = statusFromExpiry(existingSub.expiryDate),
                productId = existingSub.productId ?: req.productId,
                tier = tier
            )
        }

        // 5. Map subStatus from VerifyResult to internal storage format
        val subStatus = when (verifyResult.subStatus) {
            SubStatus.ACCEPTED -> "accepted"
            SubStatus.INCOMPLETE -> "incomplete"
            SubStatus.INTRO_PRICE -> "intro_price"
            SubStatus.TRIAL_PERIOD -> "trial_period"
            SubStatus.RESUBSCRIBED -> "resubscribed"
            SubStatus.PENDING -> "pending"
            SubStatus.CANCELLED -> "cancelled"
            SubStatus.EXPIRED -> "expired"
            SubStatus.GRACE_PERIOD -> "grace_period"
            SubStatus.BILLING_RETRY -> "billing_retry"
            else -> "active"
        }

        // 6. Construct and upsert Subscription entity
        val now = Instant.now()
        val subscription = Subscription {
            id = UuidV7.generate()
            this.appId = appId
            this.subscriptionPxid = subscriptionPxid
            this.originalTransactionId = verifyResult.originalTransactionId
            this.productId = req.productId
            this.platform = req.platform.name
            this.active = true
            this.subStatus = subStatus
            this.expiryDate = verifyResult.expiryDate
            this.purchaseToken = purchaseToken
            this.rawResponse = mapOf(
                "original_transaction_id" to verifyResult.originalTransactionId,
                "product_id" to req.productId,
                "expiry_date" to verifyResult.expiryDate?.toString(),
                "sub_status" to verifyResult.subStatus.name,
                "platform" to req.platform.name
            )
            createdAt = now
            updatedAt = now
        }

        val savedSub = subscriptionRepo.upsertSubscription(subscription)

        // 7. Return result
        return VerifyRes(
            expiresAt = savedSub.expiryDate?.toEpochMilli(),
            state = statusFromExpiry(savedSub.expiryDate),
            productId = savedSub.productId ?: req.productId,
            tier = tier
        )
    }

    /**
     * Handle Apple Server Notifications.
     */
    fun handleAppleNotification(ctx: RequestContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "APPLE")
    }

    /**
     * Handle Google Play notification.
     */
    fun handleGoogleNotification(ctx: RequestContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "GOOGLE")
    }

    /**
     * Internal unified notification handling logic.
     */
    @Transactional
    fun handleNotification(ctx: RequestContext, rawPayload: String, decoder: NotificationDecoder, platform: String) {
        val appId = ctx.appId.toUUIDOrNull() ?: return

        // Decode the notification
        val decodedPlatform = if (platform == "APPLE") Platform.APPLE else Platform.GOOGLE
        val decoderResult = decoder.decode(rawPayload, decodedPlatform)

        // Skip if no subscriptionPxid found
        if (decoderResult.subscriptionPxid.isNullOrEmpty()) {
            return
        }

        // Check idempotency using platform + subscriptionPxid as key
        if (storeNotificationRepo.existsByPlatformAndToken(platform, decoderResult.subscriptionPxid)) {
            // Already processed, skip
            return
        }

        // Find the subscription by subscriptionPxid
        var subscription = subscriptionRepo.findActiveByPxid(appId, decoderResult.subscriptionPxid)
        if (subscription == null) {
            // Try finding any (including inactive/deleted but not physically deleted) subscription
            subscription = subscriptionRepo.findByPxid(appId, decoderResult.subscriptionPxid)
        }

        // If subscription doesn't exist at all, create a minimal record or just log
        if (subscription == null) {
            // Log the notification without subscription reference
            createStoreNotification(platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
            return
        }

        // Update subscription based on notification type
        when (decoderResult.type) {
            NotificationType.REFUNDED -> updateSubscription(subscription) {
                active = false
                subStatus = "refunded"
                expiryDate = null
            }
            NotificationType.CANCELLED -> updateSubscription(subscription) {
                active = false
                subStatus = "cancelled"
            }
            NotificationType.RENEWED -> updateSubscription(subscription) {
                active = true
                subStatus = "renewed"
                // Use timestamp from decoded notification or add duration
                expiryDate = decoderResult.timestamp.plus(Duration.ofDays(30))
            }
            NotificationType.BILLING_RETRY -> updateSubscription(subscription) {
                // Check billing retry outcome - keep state as-is but update timestamp
            }
            NotificationType.GRACE_PERIOD_EXPIRED,
            NotificationType.EXPIRED -> updateSubscription(subscription) {
                active = false
                subStatus = decoderResult.type.name.lowercase()
            }
            else -> updateSubscription(subscription) {
                // For other types like SUBSCRIBED, WARNING, OK, acknowledge but don't change state drastically
            }
        }

        // Create/store the notification record as processed
        createStoreNotification(platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
    }

    /**
     * Helper to create an updated copy of a Subscription using Jimmer's immutable draft pattern.
     */
    private fun updateSubscription(sub: Subscription, block: SubscriptionDraft.() -> Unit): Subscription {
        val updated = Subscription {
            id = sub.id
            appId = sub.appId
            subscriptionPxid = sub.subscriptionPxid
            originalTransactionId = sub.originalTransactionId
            productId = sub.productId
            platform = sub.platform
            active = sub.active
            subStatus = sub.subStatus
            expiryDate = sub.expiryDate
            purchaseToken = sub.purchaseToken
            rawResponse = sub.rawResponse
            createdAt = sub.createdAt
            updatedAt = Instant.now()
            block()
        }
        return subscriptionRepo.upsertSubscription(updated)
    }

    /**
     * Helper to create or update a StoreNotification record.
     */
    private fun createStoreNotification(
        platform: String,
        subscriptionPxid: String,
        rawPayload: String,
        notificationType: NotificationType,
        appId: UUID,
        processed: Boolean = false
    ) {
        val notif = StoreNotification {
            id = UuidV7.generate()
            this.appId = appId
            this.platform = platform
            this.subscriptionPxid = subscriptionPxid
            this.purchaseToken = subscriptionPxid // fallback: use subscriptionPxid as purchase token identifier
            this.notificationType = notificationType.name
            this.rawPayload = rawPayload
            this.processed = processed
            this.processedAt = if (processed) Instant.now() else null
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        // Note: BaseCrudRepository has save() method that works for upsert
        storeNotificationRepo.save(notif)
    }

    // Extension to convert string to UUID safely
    private fun String.toUUIDOrNull(): UUID? {
        return try { UUID.fromString(this) } catch (e: Exception) { null }
    }
}

// Request/Response DTOs
data class VerifyReq(
    val platform: Platform,                    // 必填枚举（不是可空 string）
    val signedTransaction: String? = null,     // iOS StoreKit 2 JWS
    val purchaseToken: String? = null,         // Google purchase token
    val productId: String,                     // 必填
)

data class VerifyRes(
    val expiresAt: Long?,                      // epoch millis（与前端约定）
    val state: SubscriptionState,
    val productId: String,
    val tier: com.ifmix.api.core.infra.ratelimit.Tier,  // 必填枚举 FREE/PRO
)
