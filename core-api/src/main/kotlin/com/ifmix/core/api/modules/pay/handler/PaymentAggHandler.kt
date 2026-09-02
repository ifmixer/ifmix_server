package com.ifmix.core.api.modules.pay.handler

import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.entity.pay.Subscription
import com.ifmix.core.api.entity.common.Platforms
import com.ifmix.core.api.entity.common.Tiers
import com.ifmix.core.api.modules.pay.PurchaseVerifier
import com.ifmix.core.api.modules.pay.VerifyInput
import com.ifmix.core.api.modules.pay.VerifyResult
import com.ifmix.core.api.dto.payment.SubStatus
import com.ifmix.core.api.dto.payment.VerifyReq
import com.ifmix.core.api.dto.payment.VerifyRes
import com.ifmix.core.api.dto.payment.statusFromExpiry
import com.ifmix.core.api.dto.payment.tierOf
import com.ifmix.core.api.modules.app.AppConfigFacade
import com.ifmix.core.api.modules.pay.repo.SubscriptionRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PaymentAggHandler(
    @Qualifier("appleVerifier") private val appleVerifier: PurchaseVerifier,
    @Qualifier("googleVerifier") private val googleVerifier: PurchaseVerifier,
    private val subscriptionRepo: SubscriptionRepository,
    private val appConfigFacade: AppConfigFacade,
) {
    private val verifierMap: Map<String, PurchaseVerifier> = hashMapOf(
        "APPLE" to appleVerifier,
        "GOOGLE" to googleVerifier,
    )

    /**
     * 验证 + upsert 整体执行。
     * 注意：外部 verifier 调用应在事务外由 Facade 编排，
     * 此方法仅处理 DB 写入逻辑（由调用方在 tx.withTx 内调用）。
     */
    fun verifyAndUpsert(mc: ModuleCtx, req: VerifyReq, verifyResult: VerifyResult): VerifyRes {
        val appId = mc.op.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST)

        val productTierMap = appConfigFacade.findActiveByAppId(mc, appId)
            ?.content?.iap?.productTierMap
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val tier = tierOf(req.productId, productTierMap) ?: Tiers.FREE

        val subscriptionPxid = verifyResult.originalTransactionId ?: run {
            "pxid-${req.platform}-${UuidV7.generate()}"
        }

        val existingSub = subscriptionRepo.findActiveByPxid(mc, appId, subscriptionPxid)
        if (existingSub != null) {
            // restore purchases：命中已存在订阅，若归属与当前主体不一致则刷新 customerId
            // （合并/换设备后同一订阅需归到当前登录的 customer）。方向以当前主体为准。
            val curCustomerId = mc.op.customerId
            if (curCustomerId != null && existingSub.customerId != curCustomerId) {
                subscriptionRepo.updateOwner(mc, appId, existingSub.id, curCustomerId)
            }
            return VerifyRes(
                expiresAt = existingSub.expiryDate?.toEpochMilli(),
                state = statusFromExpiry(existingSub.expiryDate),
                productId = existingSub.productId ?: req.productId,
                tier = tier,
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
        val subscription = Subscription {
            this.id = UuidV7.generate()
            this.appId = appId
            this.customerId = mc.op.customerId
            this.subscriptionPxid = subscriptionPxid
            this.originalTransactionId = verifyResult.originalTransactionId
            this.productId = req.productId
            this.platform = req.platform
            this.active = true
            this.subStatus = subStatus
            this.expiryDate = verifyResult.expiryDate
            this.purchaseToken = req.purchaseToken ?: req.signedTransaction ?: ""
            rawResponse = mapOf(
                "original_transaction_id" to verifyResult.originalTransactionId,
                "product_id" to req.productId,
                "expiry_date" to verifyResult.expiryDate?.toString(),
                "sub_status" to verifyResult.subStatus.name,
                "platform" to req.platform,
            )
            this.createdAt = now
            this.updatedAt = now
        }

        subscriptionRepo.upsertSubscription(mc, subscription)
        return VerifyRes(
            expiresAt = verifyResult.expiryDate?.toEpochMilli(),
            state = statusFromExpiry(verifyResult.expiryDate),
            productId = req.productId,
            tier = tier,
        )
    }

    /** 外部验证（无事务）。返回 VerifyResult，由 Facade 在事务外调用。 */
    fun verifyPurchase(req: VerifyReq): VerifyResult {
        val verifier = verifierMap[if (req.platform == Platforms.APPLE) "APPLE" else "GOOGLE"]
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "Unknown platform: ${req.platform}")

        val purchaseToken = req.purchaseToken ?: req.signedTransaction
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "purchaseToken or signedTransaction required")
        val appId = "" // not needed for verification
        val input = VerifyInput(
            platform = req.platform,
            purchaseToken = purchaseToken,
            productId = req.productId,
            appId = appId,
        )
        return verifier.verify(input)
    }
}
