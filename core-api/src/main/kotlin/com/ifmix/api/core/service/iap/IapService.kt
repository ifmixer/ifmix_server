package com.ifmix.api.core.service.iap

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.repository.iap.SubscriptionRepository
import com.ifmix.api.core.repository.iap.StoreNotificationRepository
import com.ifmix.api.core.entity.iap.Subscription
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * IAP 服务：购买验证、订阅状态管理、商店通知处理（简化版）。
 */
class IapService(
    private val appleVerifier: PurchaseVerifier,
    private val googleVerifier: PurchaseVerifier,
    private val subscriptionRepo: SubscriptionRepository,
) {

    /**
     * 验证一笔购买并写入订阅记录。
     */
    @Transactional
    fun verifyPurchase(ctx: RequestContext, req: VerifyReq): VerifyRes {
        // TODO: 完整实现
        // 1. 根据 platform 选择 verifier
        // 2. 调用 verifier.verify(receipt/token)
        // 3. upsert subscription record
        // 4. 返回当前订阅状态
        throw NotImplementedError("verifyPurchase — pending full Jimmer integration")
    }

    /**
     * 处理 Apple Server Notifications 推送。
     */
    @Transactional
    fun handleAppleNotification(ctx: RequestContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "APPLE")
    }

    /**
     * 处理 Google Play 推送通知。
     */
    @Transactional
    fun handleGoogleNotification(ctx: RequestContext, rawPayload: String, decoder: NotificationDecoder) {
        handleNotification(ctx, rawPayload, decoder, "GOOGLE")
    }

    /** 内部统一处理通知逻辑。 */
    private fun handleNotification(ctx: RequestContext, rawPayload: String, decoder: NotificationDecoder, platform: String) {
        // TODO: 幂等检查 + 状态更新
    }

    /**
     * 获取活跃订阅（通过 subscriptionPxid）。
     */
    fun getActiveSubscription(ctx: RequestContext, subscriptionPxid: String?): Subscription? {
        if (subscriptionPxid == null) return null
        return subscriptionRepo.findAll().firstOrNull {
            it.subscriptionPxid == subscriptionPxid && it.active
        }
    }
}

// Request/Response DTOs
data class VerifyReq(
    val platform: String? = null,     // "APPLE" or "GOOGLE"
    val receipt: String? = null,       // Apple receipt data (base64)
    val purchaseToken: String? = null, // Google purchase token
    val productId: String? = null,
)

data class VerifyRes(
    val subscriptionPxid: String?,
    val active: Boolean,
    val expiryDate: Instant?,
    val state: SubscriptionState,
)
