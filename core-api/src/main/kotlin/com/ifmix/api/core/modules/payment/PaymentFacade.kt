package com.ifmix.api.core.modules.payment

import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import org.bson.types.ObjectId
import com.ifmix.api.core.modules.app.repo.AppConfigRepo
import com.ifmix.api.core.modules.payment.entity.SubscriptionEntity
import com.ifmix.api.core.modules.payment.repo.SubscriptionRepo

/**
 * Payment Facade——编排购买验证流程：
 * 1. 查 app_config 获取凭证
 * 2. 调用对应 verifier 验证购买
 * 3. upsert 订阅记录
 * 4. 返回验证结果
 */
class PaymentFacade(
    private val appleVerifier: PurchaseVerifier,
    private val googleVerifier: PurchaseVerifier,
    private val subscriptionRepo: SubscriptionRepo,
    private val appConfigRepo: AppConfigRepo,
) {

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
        val doc = SubscriptionEntity().apply {
            appId = ObjectId(ctx.appId)
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
            tier = tierOf(result.productId, config.iap.productTierMap as Map<String, Any?>),
        )
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
