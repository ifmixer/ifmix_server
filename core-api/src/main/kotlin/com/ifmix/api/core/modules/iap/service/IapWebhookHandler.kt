package com.ifmix.api.core.modules.iap.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.model.iap.Subscription
import com.ifmix.api.core.model.iap.StoreNotification
import com.ifmix.api.core.model.shared.Platforms
import com.ifmix.api.core.modules.iap.NotificationDecoder
import com.ifmix.api.core.modules.iap.NotificationType
import com.ifmix.api.core.modules.iap.repo.StoreNotificationRepository
import com.ifmix.api.core.modules.iap.repo.SubscriptionRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class IapWebhookHandler(
    private val subscriptionRepo: SubscriptionRepository,
    private val storeNotificationRepo: StoreNotificationRepository,
    private val tx: TxRunner,
) {
    fun handleAppleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "APPLE")
    }

    fun handleGoogleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "GOOGLE")
    }

    fun handleNotification(ctx: OperationContext, rawPayload: String, decoder: NotificationDecoder, platform: String) {
        val svc = SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl)
        val appId = ctx.appId ?: return

        val decodedPlatform = if (platform == "APPLE") Platforms.APPLE else Platforms.GOOGLE
        val decoderResult = decoder.decode(rawPayload, decodedPlatform)

        if (decoderResult.subscriptionPxid.isNullOrEmpty()) return

        if (storeNotificationRepo.existsByPlatformAndToken(svc, platform, decoderResult.subscriptionPxid)) return

        var subscription = subscriptionRepo.findActiveByPxid(svc, appId, decoderResult.subscriptionPxid)
        if (subscription == null) {
            subscription = subscriptionRepo.findByPxid(svc, appId, decoderResult.subscriptionPxid)
        }

        if (subscription == null) {
            tx.withTx(SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl)) { txCtx ->
                createStoreNotification(txCtx, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
            }
            return
        }

        when (decoderResult.type) {
            NotificationType.REFUNDED -> updateSubscription(ctx, subscription, active = false, subStatus = "refunded", expiryDate = null)
            NotificationType.CANCELLED -> updateSubscription(ctx, subscription, active = false, subStatus = "cancelled")
            NotificationType.RENEWED -> updateSubscription(ctx, subscription, active = true, subStatus = "renewed", expiryDate = decoderResult.timestamp.plus(Duration.ofDays(30)))
            NotificationType.BILLING_RETRY -> {}
            NotificationType.GRACE_PERIOD_EXPIRED,
            NotificationType.EXPIRED -> updateSubscription(ctx, subscription, active = false, subStatus = decoderResult.type.name.lowercase())
            else -> {}
        }

        tx.withTx(SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl)) { txCtx ->
            createStoreNotification(txCtx, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
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
        subscriptionRepo.upsertSubscription(SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl), updated)
    }

    private fun createStoreNotification(
        svc: SvcCtx,
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
        storeNotificationRepo.insert(svc, notif)
    }
}
