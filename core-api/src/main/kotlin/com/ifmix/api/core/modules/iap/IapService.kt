package com.ifmix.api.core.modules.iap

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.appconfig.AppConfigRepo
import java.time.Instant

/**
 * IAP 服务：购买验证、订阅状态管理、商店通知处理。
 */
class IapService(
    private val appleVerifier: PurchaseVerifier,
    private val googleVerifier: PurchaseVerifier,
    private val subscriptionRepo: SubscriptionRepo,
    private val appConfigRepo: AppConfigRepo,
) {

    // ---- verifyPurchase ----

    /**
     * 验证一笔购买：查 app_config 获取凭证 → 调用对应 verifier → 写入 subscriptions。
     */
    fun verifyPurchase(ctx: RequestContext, req: VerifyReq): VerifyRes {
        val config = appConfigRepo.getByAppId(ctx.appId)
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)

        val verifier = when (req.platform) {
            Platform.APPLE -> appleVerifier
            Platform.GOOGLE -> googleVerifier
        }

        val result = try {
            verifier.verify(
                VerifyInput(
                    platform = req.platform,
                    purchaseToken = req.purchaseToken,
                    productId = req.productId,
                    appId = ctx.appId,
                ),
            )
        } catch (e: Exception) {
            throw ApiError(ErrorCode.IAP_VERIFY_FAILED, e.message ?: "verification failed", mapOf("detail" to (e.message ?: "")))
        }

        // 落盘订阅记录
        val doc = SubscriptionDocument().apply {
            appId = ctx.appId
            subscriptionPxid = result.originalTransactionId ?: "pending-${java.util.UUID.randomUUID()}"
            originalTransactionId = result.originalTransactionId
            productId = result.productId
            platform = result.platform
            active = result.subStatus == SubStatus.ACCEPTED || statusFromExpiry(result.expiryDate) == SubscriptionState.ACTIVE
            subStatus = result.subStatus
            expiryDate = result.expiryDate
            rawResponse = null // TODO: 存储 verifier 返回的原始响应
        }
        subscriptionRepo.upsert(ctx, doc)

        return VerifyRes(
            verified = true,
            productId = result.productId,
            state = statusFromExpiry(result.expiryDate),
            tier = tierOf(result.productId, config.iap.productTierMap),
        )
    }

    // ---- Webhook handlers ----

    /**
     * 处理 Apple Server Notifications 推送。
     * 解码 → 幂等检查 → 更新订阅状态。
     */
    fun handleAppleNotification(ctx: RequestContext, rawPayload: String, decoder: NotificationDecoder) {
        val notification = decoder.decode(rawPayload, Platform.APPLE)
        handleNotification(ctx, notification, decoder)
    }

    /**
     * 处理 Google Play 推送通知。
     */
    fun handleGoogleNotification(ctx: RequestContext, rawPayload: String, decoder: NotificationDecoder) {
        val notification = decoder.decode(rawPayload, Platform.GOOGLE)
        handleNotification(ctx, notification, decoder)
    }

    /** 内部统一处理逻辑。 */
    private fun handleNotification(ctx: RequestContext, notification: DecodedNotification, decoder: NotificationDecoder) {
        // 幂等检查：store_notifications 集合
        // TODO: 实现去重逻辑（按 platform + subscriptionPxid + type 查询）

        val sub = subscriptionRepo.findActiveBySubject(ctx, notification.subscriptionPxid)
        if (sub != null) {
            // 根据通知类型更新订阅状态
            val active = when (notification.type) {
                NotificationType.CANCELLED, NotificationType.EXPIRED, NotificationType.GRACE_PERIOD_EXPIRED -> false
                else -> true
            }
            sub.active = active
            sub.subStatus = when (notification.type) {
                NotificationType.CANCELLED -> SubStatus.CANCELLED
                NotificationType.EXPIRED -> SubStatus.EXPIRED
                NotificationType.BILLING_RETRY -> SubStatus.BILLING_RETRY
                else -> sub.subStatus
            }
            sub.updatedAt = Instant.now()
            // TODO: mongo.save(sub)
        }
    }
}

// ---- DTOs ----

/** 客户端调用 verify 接口的请求体。 */
data class VerifyReq(
    val platform: Platform,
    val purchaseToken: String,
    val productId: String,
)

/** verify 接口的响应体。 */
data class VerifyRes(
    val verified: Boolean,
    val productId: String,
    val state: SubscriptionState,
    val tier: com.ifmix.api.core.common.ratelimit.Tier?,
)
