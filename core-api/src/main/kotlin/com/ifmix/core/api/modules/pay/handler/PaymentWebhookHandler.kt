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
        val ctx = mc.action
        val projectId = ctx.projectId ?: return

        val decodedPlatform = if (platform == "APPLE") com.ifmix.core.api.entity.common.Platforms.APPLE else com.ifmix.core.api.entity.common.Platforms.GOOGLE
        val decoderResult = decoder.decode(rawPayload, decodedPlatform)

        if (decoderResult.subscriptionPxid.isNullOrEmpty()) return

        // 幂等键必须是「通知」唯一 id（Apple notificationUUID / Google messageId）：
        // 同一订阅的 RENEWED/EXPIRED/REFUND 是不同通知，用订阅 id 去重会把后续通知全部吞掉。
        // 通知 id 缺失时退化为订阅 id（保守去重，防重放）。
        val dedupKey = decoderResult.notificationId?.takeIf { it.isNotBlank() }
            ?: decoderResult.subscriptionPxid
        if (storeNotificationRepo.existsByPlatformAndToken(mc, platform, dedupKey)) return

        var subscription = subscriptionRepo.findActiveByPxid(mc, projectId, decoderResult.subscriptionPxid)
        if (subscription == null) {
            subscription = subscriptionRepo.findByPxid(mc, projectId, decoderResult.subscriptionPxid)
        }

        if (subscription == null) {
            createStoreNotification(mc, platform, decoderResult.subscriptionPxid, dedupKey, rawPayload, decoderResult.type, projectId, processed = true)
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

        createStoreNotification(mc, platform, decoderResult.subscriptionPxid, dedupKey, rawPayload, decoderResult.type, projectId, processed = true)
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
        dedupKey: String,
        rawPayload: String,
        notificationType: NotificationType,
        projectId: String,
        processed: Boolean = false,
    ) {
        val now = Instant.now()
        val notif = com.ifmix.core.api.entity.pay.StoreNotification {
            this.id = UuidV7.generate()
            this.projectId = projectId
            this.platform = platform
            this.subscriptionPxid = subscriptionPxid
            // purchaseToken 存幂等键（通知唯一 id），与 existsByPlatformAndToken 的查询键一致
            this.purchaseToken = dedupKey
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
