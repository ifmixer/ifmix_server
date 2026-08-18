package com.ifmix.api.core.modules.payment.service.internal

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.entity.iap.Subscription
import com.ifmix.api.core.entity.shared.Platforms
import com.ifmix.api.core.entity.shared.Tiers
import com.ifmix.api.core.modules.payment.PurchaseVerifier
import com.ifmix.api.core.modules.payment.VerifyInput
import com.ifmix.api.core.dto.iap.SubStatus
import com.ifmix.api.core.dto.iap.VerifyReq
import com.ifmix.api.core.dto.iap.VerifyRes
import com.ifmix.api.core.dto.iap.statusFromExpiry
import com.ifmix.api.core.dto.iap.tierOf
import com.ifmix.api.core.modules.app.repo.AppConfigRepository
import com.ifmix.api.core.modules.payment.repo.SubscriptionRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PaymentInternalService(
    @Qualifier("appleVerifier") private val appleVerifier: PurchaseVerifier,
    @Qualifier("googleVerifier") private val googleVerifier: PurchaseVerifier,
    private val subscriptionRepo: SubscriptionRepository,
    private val appConfigRepo: AppConfigRepository,
) {
    private val verifierMap: Map<String, PurchaseVerifier> = hashMapOf(
        "APPLE" to appleVerifier,
        "GOOGLE" to googleVerifier,
    )

    fun verifyPurchase(sc: SvcCtx, req: VerifyReq): VerifyRes {
        val ctx = sc.op
        val appId = ctx.appId ?: throw ApiError(ErrorCode.INVALID_REQUEST)

        val verifier = verifierMap[if (req.platform == Platforms.APPLE) "APPLE" else "GOOGLE"]
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "Unknown platform: ${req.platform}")

        val purchaseToken = req.purchaseToken ?: req.signedTransaction
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "purchaseToken or signedTransaction required")
        val input = VerifyInput(
            platform = req.platform,
            purchaseToken = purchaseToken,
            productId = req.productId,
            appId = appId.toString(),
        )
        val verifyResult = verifier.verify(input)

        val config = appConfigRepo.mustFindCurrentRevision(sc, appId)
        val productTierMap = config.contentConfig.iap.productTierMap
        val tier = tierOf(req.productId, productTierMap) ?: Tiers.FREE

        val subscriptionPxid = verifyResult.originalTransactionId ?: run {
            "pxid-${req.platform}-${UuidV7.generate()}"
        }

        val existingSub = subscriptionRepo.findActiveByPxid(sc, appId, subscriptionPxid)
        if (existingSub != null) {
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
        val subscription = Subscription(
            id = UuidV7.generate(),
            appId = appId,
            subscriptionPxid = subscriptionPxid,
            originalTransactionId = verifyResult.originalTransactionId,
            productId = req.productId,
            platform = req.platform,
            active = true,
            subStatus = subStatus,
            expiryDate = verifyResult.expiryDate,
            purchaseToken = purchaseToken,
            rawResponse = tools.jackson.databind.ObjectMapper().writeValueAsString(
                mapOf(
                    "original_transaction_id" to verifyResult.originalTransactionId,
                    "product_id" to req.productId,
                    "expiry_date" to verifyResult.expiryDate?.toString(),
                    "sub_status" to verifyResult.subStatus.name,
                    "platform" to req.platform,
                ),
            ),
            createdAt = now,
            updatedAt = now,
        )

        subscriptionRepo.upsertSubscription(sc, subscription)
        return VerifyRes(
            expiresAt = verifyResult.expiryDate?.toEpochMilli(),
            state = statusFromExpiry(verifyResult.expiryDate),
            productId = req.productId,
            tier = tier,
        )
    }
}
