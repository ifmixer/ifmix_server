package com.ifmix.api.core.modules.iap.dto

import com.ifmix.api.core.infra.ratelimit.Tier
import com.ifmix.api.core.modules.iap.Platform
import com.ifmix.api.core.modules.iap.SubscriptionState

data class VerifyReq(
    val platform: Platform,
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
