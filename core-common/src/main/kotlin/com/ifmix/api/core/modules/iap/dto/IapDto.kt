package com.ifmix.api.core.modules.iap.dto

import com.ifmix.api.core.entity.enums.Tier

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
