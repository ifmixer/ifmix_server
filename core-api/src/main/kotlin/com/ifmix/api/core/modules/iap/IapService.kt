package com.ifmix.api.core.modules.iap

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.repository.iap.SubscriptionRepository
import com.ifmix.api.core.common.jimmer.repository.iap.StoreNotificationRepository
import com.ifmix.api.core.common.jimmer.entity.iap.Subscription
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * IAP 服务：购买验证、订阅状态管理、商店通知处理（简化版）。
 */
class IapService(
    private val appleVerifier: PurchaseVerifier,
    private val googleVerifier: PurchaseVerifier,
    private val subscriptionRepo: SubscriptionRepository,
) {

    enum class Platform { APPLE, GOOGLE }

    /**
     * 验证一笔购买。暂存 stub，实际逻辑需整合 verifier 和订阅仓库。
     */
    @Transactional
    fun verifyPurchase(ctx: RequestContext, req: Any?): Any =
        throw NotImplementedError("verifyPurchase not fully implemented")

    /**
     * 处理 Apple Server Notifications 推送。
     */
    @Transactional
    fun handleAppleNotification(ctx: RequestContext, rawPayload: String, decoder: Any) =
        handleNotification(ctx, decoder, "apple")

    /**
     * 处理 Google Play 推送通知。
     */
    @Transactional
    fun handleGoogleNotification(ctx: RequestContext, rawPayload: String, decoder: Any) =
        handleNotification(ctx, decoder, "google")

    /** 内部统一处理通知逻辑。（待实现） */
    private fun handleNotification(ctx: RequestContext, decoder: Any, platform: String) {
        // TODO: 实现通知处理
    }

    /**
     * 获取活跃订阅（通过 subscriptionPxid）。
     */
    fun getActiveSubscription(ctx: RequestContext, subscriptionPxid: String?): Subscription? =
        // SubscriptionRepository 目前只有基础 CRUD，需要在子类中扩展查询方法
        // 此处为占位符，实际实现需要添加 findBySubscriptionPxid 到仓库
        null
}
