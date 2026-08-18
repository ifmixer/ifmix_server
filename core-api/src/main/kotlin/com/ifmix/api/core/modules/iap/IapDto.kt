package com.ifmix.api.core.modules.iap

import com.ifmix.api.core.model.enums.Tier

data class VerifyReq(
    val platform: Int,
    val signedTransaction: String? = null,
    val purchaseToken: String? = null,
    val productId: String,
)

data class VerifyRes(
    val expiresAt: Long?,
    val state: SubscriptionState,
    val productId: String,
    val tier: Tier,
)
