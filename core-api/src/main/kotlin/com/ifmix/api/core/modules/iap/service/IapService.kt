package com.ifmix.api.core.modules.iap.service

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.model.iap.Subscription
import com.ifmix.api.core.model.iap.StoreNotification
import com.ifmix.api.core.modules.iap.repo.StoreNotificationRepository
import com.ifmix.api.core.modules.iap.repo.SubscriptionRepository
import com.ifmix.api.core.modules.app.repo.AppConfigRepository
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.model.enums.Platform
import com.ifmix.api.core.model.enums.Tier
import com.ifmix.api.core.modules.iap.NotificationDecoder
import com.ifmix.api.core.modules.iap.NotificationType
import com.ifmix.api.core.modules.iap.PurchaseVerifier
import com.ifmix.api.core.modules.iap.VerifyInput
import com.ifmix.api.core.modules.iap.SubStatus
import com.ifmix.api.core.modules.iap.VerifyReq
import com.ifmix.api.core.modules.iap.VerifyRes
import com.ifmix.api.core.modules.iap.statusFromExpiry
import com.ifmix.api.core.modules.iap.tierOf
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
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
    private val appConfigRepo: AppConfigRepository,
    private val tx: TxRunner,
) {

    private val verifierMap: Map<String, PurchaseVerifier> = hashMapOf(
        "APPLE" to appleVerifier,
        "GOOGLE" to googleVerifier
    )

    fun verifyPurchase(ctx: OperationContext, req: VerifyReq): VerifyRes {
        val rc = ctx.repoCtx
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST)

        val platformEnum = Platform.fromCode(req.platform)
        val verifier = verifierMap[platformEnum.name] ?: throw ApiError(ErrorCode.INVALID_REQUEST, "Unknown platform: ${req.platform}")

        val purchaseToken = req.purchaseToken ?: req.signedTransaction
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "purchaseToken or signedTransaction required")
        val input = VerifyInput(
            platform = platformEnum,
            purchaseToken = purchaseToken,
            productId = req.productId,
            appId = appId.toString()
        )
        val verifyResult = verifier.verify(input)

        val config = appConfigRepo.mustFindCurrentRevision(rc, appId)
        val productTierMap = config.contentConfig.iap.productTierMap
        val tier = tierOf(req.productId, productTierMap) ?: Tier.FREE

        val subscriptionPxid = verifyResult.originalTransactionId ?: run {
            "pxid-${platformEnum.name}-${UuidV7.generate()}"
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
        val subscription = Subscription(
            id = UuidV7.generate(),
            appId = appId,
            subscriptionPxid = subscriptionPxid,
            originalTransactionId = verifyResult.originalTransactionId,
            productId = req.productId,
            platform = req.platform,
            active = true,
            subStatus = subStatus,
            expiryDate = verifyResult.expiryDate,
            purchaseToken = purchaseToken,
            rawResponse = tools.jackson.databind.ObjectMapper().writeValueAsString(
                mapOf(
                    "original_transaction_id" to verifyResult.originalTransactionId,
                    "product_id" to req.productId,
                    "expiry_date" to verifyResult.expiryDate?.toString(),
                    "sub_status" to verifyResult.subStatus.name,
                    "platform" to req.platform
                )
            ),
            createdAt = now,
            updatedAt = now,
        )

        return tx.withTx(ctx) { txCtx ->
            subscriptionRepo.upsertSubscription(txCtx.repoCtx, subscription)
            VerifyRes(
                expiresAt = verifyResult.expiryDate?.toEpochMilli(),
                state = statusFromExpiry(verifyResult.expiryDate),
                productId = req.productId,
                tier = tier
            )
        }
    }

    fun handleAppleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "APPLE")
    }

    fun handleGoogleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "GOOGLE")
    }

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
            tx.withTx(ctx) { txCtx ->
                createStoreNotification(txCtx.repoCtx, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
            }
            return
        }

        when (decoderResult.type) {
            NotificationType.REFUNDED -> updateSubscription(ctx, subscription, active = false, subStatus = "refunded", expiryDate = null)
            NotificationType.CANCELLED -> updateSubscription(ctx, subscription, active = false, subStatus = "cancelled")
            NotificationType.RENEWED -> updateSubscription(ctx, subscription, active = true, subStatus = "renewed", expiryDate = decoderResult.timestamp.plus(Duration.ofDays(30)))
            NotificationType.BILLING_RETRY -> {} // no change
            NotificationType.GRACE_PERIOD_EXPIRED,
            NotificationType.EXPIRED -> updateSubscription(ctx, subscription, active = false, subStatus = decoderResult.type.name.lowercase())
            else -> {} // no change
        }

        tx.withTx(ctx) { txCtx ->
            createStoreNotification(txCtx.repoCtx, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
        }
    }

    private fun updateSubscription(
        ctx: OperationContext,
        sub: Subscription,
        active: Boolean? = null,
        subStatus: String? = null,
        expiryDate: Instant? = null,
    ) {
        val now = Instant.now()
        val updated = sub.copy(
            active = active ?: sub.active,
            subStatus = subStatus ?: sub.subStatus,
            expiryDate = expiryDate ?: sub.expiryDate,
            updatedAt = now,
        )
        subscriptionRepo.upsertSubscription(ctx.repoCtx, updated)
    }

    private fun createStoreNotification(
        rc: RepoContext,
        platform: String,
        subscriptionPxid: String,
        rawPayload: String,
        notificationType: NotificationType,
        appId: UUID,
        processed: Boolean = false,
    ) {
        val now = Instant.now()
        val notif = StoreNotification(
            id = UuidV7.generate(),
            appId = appId,
            platform = platform,
            subscriptionPxid = subscriptionPxid,
            purchaseToken = subscriptionPxid,
            notificationType = notificationType.name,
            rawPayload = rawPayload,
            processed = processed,
            processedAt = if (processed) now else null,
            createdAt = now,
            updatedAt = now,
        )
        storeNotificationRepo.insert(rc, notif)
    }
}
