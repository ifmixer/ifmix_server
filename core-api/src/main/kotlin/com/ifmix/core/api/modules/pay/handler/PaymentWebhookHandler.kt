package com.ifmix.core.api.modules.pay.handler

import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.modules.pay.NotificationDecoder
import com.ifmix.core.api.modules.pay.NotificationType
import com.ifmix.core.api.modules.pay.repo.StoreNotificationRepository
import com.ifmix.core.api.modules.pay.repo.SubscriptionRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class PaymentWebhookHandler(
    private val subscriptionRepo: SubscriptionRepository,
    private val storeNotificationRepo: StoreNotificationRepository,
) {
    fun handleAppleNotification(mc: ModuleCtx, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(mc, rawPayload, decoder, "APPLE")
    }

    fun handleGoogleNotification(mc: ModuleCtx, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(mc, rawPayload, decoder, "GOOGLE")
    }

    fun handleNotification(mc: ModuleCtx, rawPayload: String, decoder: NotificationDecoder, platform: String) {
        val ctx = mc.op
        val appId = ctx.appId ?: return

        val decodedPlatform = if (platform == "APPLE") com.ifmix.core.api.entity.common.Platforms.APPLE else com.ifmix.core.api.entity.common.Platforms.GOOGLE
        val decoderResult = decoder.decode(rawPayload, decodedPlatform)

        if (decoderResult.subscriptionPxid.isNullOrEmpty()) return

        if (storeNotificationRepo.existsByPlatformAndToken(mc, platform, decoderResult.subscriptionPxid)) return

        var subscription = subscriptionRepo.findActiveByPxid(mc, appId, decoderResult.subscriptionPxid)
        if (subscription == null) {
            subscription = subscriptionRepo.findByPxid(mc, appId, decoderResult.subscriptionPxid)
        }

        if (subscription == null) {
            createStoreNotification(mc, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
            return
        }

        when (decoderResult.type) {
            NotificationType.REFUNDED -> updateSubscription(mc, subscription, active = false, subStatus = "refunded", expiryDate = null)
            NotificationType.CANCELLED -> updateSubscription(mc, subscription, active = false, subStatus = "cancelled")
            NotificationType.RENEWED -> updateSubscription(mc, subscription, active = true, subStatus = "renewed", expiryDate = decoderResult.timestamp.plus(Duration.ofDays(30)))
            NotificationType.BILLING_RETRY -> {}
            NotificationType.GRACE_PERIOD_EXPIRED,
            NotificationType.EXPIRED -> updateSubscription(mc, subscription, active = false, subStatus = decoderResult.type.name.lowercase())
            else -> {}
        }

        createStoreNotification(mc, platform, decoderResult.subscriptionPxid, rawPayload, decoderResult.type, appId, processed = true)
    }

    private fun updateSubscription(
        mc: ModuleCtx,
        sub: com.ifmix.core.api.entity.pay.Subscription,
        active: Boolean? = null,
        subStatus: String? = null,
        expiryDate: Instant? = null,
    ) {
        val now = Instant.now()
        val updated = com.ifmix.core.api.entity.pay.Subscription(sub) {
            this.active = active ?: sub.active
            this.subStatus = subStatus ?: sub.subStatus
            this.expiryDate = expiryDate ?: sub.expiryDate
            this.updatedAt = now
        }
        subscriptionRepo.upsertSubscription(mc, updated)
    }

    private fun createStoreNotification(
        mc: ModuleCtx,
        platform: String,
        subscriptionPxid: String,
        rawPayload: String,
        notificationType: NotificationType,
        appId: UUID,
        processed: Boolean = false,
    ) {
        val now = Instant.now()
        val notif = com.ifmix.core.api.entity.pay.StoreNotification {
            this.id = UuidV7.generate()
            this.appId = appId
            this.platform = platform
            this.subscriptionPxid = subscriptionPxid
            this.purchaseToken = subscriptionPxid
            this.notificationType = notificationType.name
            this.rawPayload = mapOf("payload" to rawPayload)
            this.processed = processed
            this.processedAt = if (processed) now else null
            this.createdAt = now
            this.updatedAt = now
        }
        storeNotificationRepo.save(mc, notif)
    }
}
