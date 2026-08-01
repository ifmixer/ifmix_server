package com.ifmix.api.core.service.iap

import org.babyfish.jimmer.sql.EnumItem
import org.babyfish.jimmer.sql.EnumType

/**
 * IAP 购买平台。
 *
 * 同时作为 Jimmer entity 属性类型（SMALLINT 存储）。
 * 编码：APPLE=10, GOOGLE=20。
 */
@EnumType(EnumType.Strategy.ORDINAL)
enum class Platform(val code: Int) {
    @EnumItem(ordinal = 100)
    APPLE(100),

    @EnumItem(ordinal = 200)
    GOOGLE(200);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): Platform = byCode[code]
            ?: throw IllegalArgumentException("Unknown Platform code: $code")
    }
}

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
fun statusFromExpiry(expiryDate: java.time.Instant?, now: java.time.Instant = java.time.Instant.now()): SubscriptionState {
    if (expiryDate == null) return SubscriptionState.ACTIVE
    return if (expiryDate.isAfter(now)) SubscriptionState.ACTIVE else SubscriptionState.EXPIRED
}

/**
 * 将 product SKU 映射到 Tier 名称，再查 [Tier] 枚举。
 * 未匹配时返回 null。
 */
fun tierOf(sku: String, productTierMap: Map<String, Any?>): com.ifmix.api.core.infra.ratelimit.Tier? {
    val tierName = (productTierMap[sku] as? String) ?: return null
    return try {
        com.ifmix.api.core.infra.ratelimit.Tier.valueOf(tierName.uppercase())
    } catch (_: IllegalArgumentException) {
        null
    }
}
