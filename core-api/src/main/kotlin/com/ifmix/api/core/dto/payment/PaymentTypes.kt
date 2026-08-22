package com.ifmix.api.core.dto.payment

import com.ifmix.api.core.entity.common.Tiers
import java.time.Instant

/** App Store / Google Play 返回的原始订阅状态。 */
enum class SubStatus {
    ACCEPTED,
    CANCELLED,
    EXPIRED,
    BILLING_RETRY,
    GRACE_PERIOD,
    INTRO_PRICE,
    INCOMPLETE,
    PENDING,
    RESUBSCRIBED,
    TRIAL_PERIOD,
    UNKNOWN,
}

/** 归一化后的订阅生命周期状态。 */
enum class SubscriptionState {
    ACTIVE,
    EXPIRED,
    PENDING,
}

/**
 * 根据过期时间推断订阅状态。
 */
fun statusFromExpiry(expiryDate: Instant?, now: Instant = Instant.now()): SubscriptionState {
    if (expiryDate == null) return SubscriptionState.ACTIVE
    return if (expiryDate.isAfter(now)) SubscriptionState.ACTIVE else SubscriptionState.EXPIRED
}

/**
 * 将 product SKU 映射到 Tier 名称，再查 [Tiers] 对象。
 */
fun tierOf(sku: String, productTierMap: Map<String, Any?>): Int? {
    val tierName = (productTierMap[sku] as? String) ?: return null
    return when (tierName.uppercase()) {
        "FREE" -> Tiers.FREE
        "PRO" -> Tiers.PRO
        "ENTERPRISE" -> Tiers.ENTERPRISE
        else -> null
    }
}
