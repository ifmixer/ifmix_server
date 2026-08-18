package com.ifmix.api.core.modules.payment.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.modules.payment.NotificationDecoder
import com.ifmix.api.core.modules.payment.NotificationType
import com.ifmix.api.core.modules.payment.repo.StoreNotificationRepository
import com.ifmix.api.core.modules.payment.repo.SubscriptionRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class PaymentWebhookHandler(
    private val subscriptionRepo: SubscriptionRepository,
    private val storeNotificationRepo: StoreNotificationRepository,
) {
    fun handleAppleNotification(sc: SvcCtx, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(sc, rawPayload, decoder, "APPLE")
    }

    fun handleGoogleNotification(sc: SvcCtx, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(sc, rawPayload, decoder, "GOOGLE")
    }

    fun handleNotification(sc: SvcCtx, rawPayload: String, decoder: NotificationDecoder, platform: String) {
        val ctx = sc.op
        val appId = ctx.appId ?: return

        val decodedPlatform = if (platform == "APPLE") com.ifmix.api.core.entity.shared.Platforms.APPLE else com.ifmix.api.core.entity.shared.Platforms.GOOGLE
        val decoderResult = decoder.decode(rawPayload, decodedPlatform)

        if (decoderResult.subscriptionPxid.isNullOrEmpty()) return

        if (storeNotificationRepo.existsByPlatformAndToken(sc, platform, decoderResult.subscriptionPxid)) return

        var subscription = subscriptionRepo.findActiveByPxid(sc, appId, decoderResult.subscriptionPxid)
        if (subscription == null) {
            subscription = subscriptionRepo.findByPxid(sc, appId, decoderResult.subscriptionPxid)
        }

        if (subscription == null) {
            createStoreNotification(sc, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
            return
        }

        when (decoderResult.type) {
            NotificationType.REFUNDED -> updateSubscription(sc, subscription, active = false, subStatus = "refunded", expiryDate = null)
            NotificationType.CANCELLED -> updateSubscription(sc, subscription, active = false, subStatus = "cancelled")
            NotificationType.RENEWED -> updateSubscription(sc, subscription, active = true, subStatus = "renewed", expiryDate = decoderResult.timestamp.plus(Duration.ofDays(30)))
            NotificationType.BILLING_RETRY -> {}
            NotificationType.GRACE_PERIOD_EXPIRED,
            NotificationType.EXPIRED -> updateSubscription(sc, subscription, active = false, subStatus = decoderResult.type.name.lowercase())
            else -> {}
        }

        createStoreNotification(sc, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
    }

    private fun updateSubscription(
        sc: SvcCtx,
        sub: com.ifmix.api.core.entity.iap.Subscription,
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
        subscriptionRepo.upsertSubscription(sc, updated)
    }

    private fun createStoreNotification(
        sc: SvcCtx,
        platform: String,
        subscriptionPxid: String,
        rawPayload: String,
        notificationType: NotificationType,
        appId: UUID,
        processed: Boolean = false,
    ) {
        val now = Instant.now()
        val notif = com.ifmix.api.core.entity.iap.StoreNotification(
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
        storeNotificationRepo.insert(sc, notif)
    }
}
