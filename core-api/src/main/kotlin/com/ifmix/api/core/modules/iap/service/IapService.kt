package com.ifmix.api.core.modules.iap.service

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.iap.repo.SubscriptionRepository
import com.ifmix.api.core.modules.iap.repo.StoreNotificationRepository
import com.ifmix.api.core.entity.iap.Subscription
import com.ifmix.api.core.entity.iap.SubscriptionDraft
import com.ifmix.api.core.entity.iap.StoreNotification
import com.ifmix.api.core.modules.app.repo.AppConfigRevisionRepository
import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.ratelimit.Tier
import com.ifmix.api.core.modules.iap.dto.VerifyReq
import com.ifmix.api.core.modules.iap.dto.VerifyRes
import com.ifmix.api.core.modules.iap.NotificationDecoder
import com.ifmix.api.core.modules.iap.NotificationType
import com.ifmix.api.core.modules.iap.dto.Platform
import com.ifmix.api.core.modules.iap.PurchaseVerifier
import com.ifmix.api.core.modules.iap.dto.SubStatus
import com.ifmix.api.core.modules.iap.VerifyInput
import com.ifmix.api.core.modules.iap.dto.statusFromExpiry
import com.ifmix.api.core.modules.iap.dto.tierOf
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
open class IapService(
    @Qualifier("appleVerifier")
    private val appleVerifier: PurchaseVerifier,
    @Qualifier("googleVerifier")
    private val googleVerifier: PurchaseVerifier,
    private val subscriptionRepo: SubscriptionRepository,
    private val storeNotificationRepo: StoreNotificationRepository,
    private val appConfigRepo: AppConfigRevisionRepository,
) {

    private val verifierMap: Map<String, PurchaseVerifier> = hashMapOf(
        "APPLE" to appleVerifier,
        "GOOGLE" to googleVerifier
    )

    @Transactional
    fun verifyPurchase(ctx: OperationContext, req: VerifyReq): VerifyRes {
        val rc = ctx.repoCtx
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST)

        val verifier = verifierMap[req.platform.name] ?: throw ApiError(ErrorCode.INVALID_REQUEST, "Unknown platform: ${req.platform}")

        val purchaseToken = req.purchaseToken ?: req.signedTransaction
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "purchaseToken or signedTransaction required")
        val input = VerifyInput(
            platform = req.platform,
            purchaseToken = purchaseToken,
            productId = req.productId,
            appId = appId.toString()
        )
        val verifyResult = verifier.verify(input)

        val config = appConfigRepo.mustFindCurrentRevision(rc, appId)
        val productTierMap = config.content.iap.productTierMap
        val tier = tierOf(req.productId, productTierMap) ?: Tier.FREE

        val subscriptionPxid = verifyResult.originalTransactionId ?: run {
            "pxid-${req.platform.name}-${UuidV7.generate()}"
        }

        val existingSub = subscriptionRepo.findActiveByPxid(rc, appId, subscriptionPxid)
        if (existingSub != null) {
            return VerifyRes(
                expiresAt = existingSub.expiryDate?.toEpochMilli(),
                state = statusFromExpiry(existingSub.expiryDate),
                productId = existingSub.productId ?: req.productId,
                tier = tier
            )
        }

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

        val now = Instant.now()
        val subscription = Subscription {
            id = UuidV7.generate()
            this.appId = appId
            this.subscriptionPxid = subscriptionPxid
            this.originalTransactionId = verifyResult.originalTransactionId
            this.productId = req.productId
            this.platform = req.platform
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

        val savedSub = subscriptionRepo.upsertSubscription(rc, subscription)

        return VerifyRes(
            expiresAt = savedSub.expiryDate?.toEpochMilli(),
            state = statusFromExpiry(savedSub.expiryDate),
            productId = savedSub.productId ?: req.productId,
            tier = tier
        )
    }

    fun handleAppleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "APPLE")
    }

    fun handleGoogleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "GOOGLE")
    }

    @Transactional
    fun handleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder, platform: String) {
        val rc = ctx.repoCtx
        val appId = ctx.appId ?: return

        val decodedPlatform = if (platform == "APPLE") Platform.APPLE else Platform.GOOGLE
        val decoderResult = decoder.decode(rawPayload, decodedPlatform)

        if (decoderResult.subscriptionPxid.isNullOrEmpty()) {
            return
        }

        if (storeNotificationRepo.existsByPlatformAndToken(rc, platform, decoderResult.subscriptionPxid)) {
            return
        }

        var subscription = subscriptionRepo.findActiveByPxid(rc, appId, decoderResult.subscriptionPxid)
        if (subscription == null) {
            subscription = subscriptionRepo.findByPxid(rc, appId, decoderResult.subscriptionPxid)
        }

        if (subscription == null) {
            createStoreNotification(rc, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
            return
        }

        when (decoderResult.type) {
            NotificationType.REFUNDED -> updateSubscription(rc, subscription) {
                active = false
                subStatus = "refunded"
                expiryDate = null
            }
            NotificationType.CANCELLED -> updateSubscription(rc, subscription) {
                active = false
                subStatus = "cancelled"
            }
            NotificationType.RENEWED -> updateSubscription(rc, subscription) {
                active = true
                subStatus = "renewed"
                expiryDate = decoderResult.timestamp.plus(Duration.ofDays(30))
            }
            NotificationType.BILLING_RETRY -> updateSubscription(rc, subscription) {}
            NotificationType.GRACE_PERIOD_EXPIRED,
            NotificationType.EXPIRED -> updateSubscription(rc, subscription) {
                active = false
                subStatus = decoderResult.type.name.lowercase()
            }
            else -> updateSubscription(rc, subscription) {}
        }

        createStoreNotification(rc, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
    }

    private fun updateSubscription(rc: RepoContext, sub: Subscription, block: SubscriptionDraft.() -> Unit): Subscription {
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
        return subscriptionRepo.upsertSubscription(rc, updated)
    }

    private fun createStoreNotification(
        rc: RepoContext,
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
            this.purchaseToken = subscriptionPxid
            this.notificationType = notificationType.name
            this.rawPayload = rawPayload
            this.processed = processed
            this.processedAt = if (processed) Instant.now() else null
            this.createdAt = Instant.now()
            this.updatedAt = Instant.now()
        }
        storeNotificationRepo.save(rc, notif)
    }

    private fun String.toUUIDOrNull(): UUID? {
        return try { UUID.fromString(this) } catch (e: Exception) { null }
    }
}
