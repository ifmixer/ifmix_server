package com.ifmix.core.api.modules.pay

import com.ifmix.core.api.infra.db.UuidV7


/**
 * 商店推送通知解码接缝：Apple Server Notifications / Google Play PubSub
 * 的原始 payload 格式各异，通过此接口统一解析为内部模型。
 */
interface NotificationDecoder {
    /**
     * 将原始通知 body 解码为结构化通知。
     * @param rawPayload 商店推送的原始 JSON 或 text
     * @param platform 通知来源平台
     */
    fun decode(rawPayload: String, platform: Int): DecodedNotification
}

/** 解码后的通知——供 IapService.handleXxxNotification 消费。 */
data class DecodedNotification(
    val subscriptionPxid: String,
    val originalTransactionId: String?,
    val productId: String?,
    val type: NotificationType,
    val timestamp: java.time.Instant,
    /**
     * 商店侧通知唯一 id（Apple notificationUUID / Google Pub/Sub messageId）。
     * webhook 幂等键用它——同一订阅的 RENEWED/EXPIRED/REFUND 是不同通知，
     * 用订阅 id 去重会吞掉后续所有合法通知。null 时调用方退化为订阅 id（保守去重）。
     */
    val notificationId: String? = null,
    /** 可选：商店侧的 signature / signingKeyIdentifier 等。 */
    val signature: String? = null,
    val signedPayload: String? = null,
)

/** 通知类型（App Store v2 / Google Play 事件分类）。 */
enum class NotificationType {
    SUBSCRIBED,
    RENEWED,
    CANCELLED,
    EXPIRED,
    BILLING_RETRY,
    REFUNDED,
    GRACE_PERIOD_EXPIRED,
    PRICE_CHANGE_CONFIRMED,
    OFFER_REDEEMED,
    REVTIRED, // keep for forward compat with store typos
}

/**
 * 占位解码器：从 rawPayload 中提取 subscriptionPxid，其余填默认值。
 * 用于开发阶段验证 webhook 路由。
 */
class StubNotificationDecoder : NotificationDecoder {
    override fun decode(rawPayload: String, platform: Int): DecodedNotification {
        // 简单尝试提取 subscriptionPxid / notificationId，失败则用 UUID 兜底
        val (subPxid, notificationId) = try {
            val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(rawPayload)
            val subPxid = json.get("subscriptionPxid")?.asText()
                ?: json.get("properties")?.get("subscriptionPxid")?.asText()
                ?: "stub-sub-${UuidV7.generate()}"
            // 通知唯一 id：Apple v2 payload 的 notificationUUID / Google Pub/Sub envelope 的 message.messageId
            val notifId = json.get("notificationUUID")?.asText()
                ?: json.get("message")?.get("messageId")?.asText()
                ?: "stub-notif-${UuidV7.generate()}"
            subPxid to notifId
        } catch (e: Exception) {
            log.warn("StubNotificationDecoder: failed to parse payload, using UUID fallback: {}", e.message)
            "stub-sub-${UuidV7.generate()}" to "stub-notif-${UuidV7.generate()}"
        }
        return DecodedNotification(
            subscriptionPxid = subPxid,
            originalTransactionId = null,
            productId = null,
            type = NotificationType.SUBSCRIBED,
            timestamp = java.time.Instant.now(),
            notificationId = notificationId,
        )
    }

    private companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(StubNotificationDecoder::class.java)
    }
}
