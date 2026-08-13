package com.ifmix.api.core.modules.iap.dto

import com.ifmix.api.core.entity.enums.Tier
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
 * [expiryDate] 为 null 时返回 ACTIVE（例如一次性购买或尚未设置过期时间的订阅）。
 */
fun statusFromExpiry(expiryDate: Instant?, now: Instant = Instant.now()): SubscriptionState {
    if (expiryDate == null) return SubscriptionState.ACTIVE
    return if (expiryDate.isAfter(now)) SubscriptionState.ACTIVE else SubscriptionState.EXPIRED
}

/**
 * 将 product SKU 映射到 Tier 名称，再查 [Tier] 枚举。
 * 未匹配时返回 null。
 */
fun tierOf(sku: String, productTierMap: Map<String, Any?>): Tier? {
    val tierName = (productTierMap[sku] as? String) ?: return null
    return try {
        Tier.valueOf(tierName.uppercase())
    } catch (_: IllegalArgumentException) {
        null
    }
}
