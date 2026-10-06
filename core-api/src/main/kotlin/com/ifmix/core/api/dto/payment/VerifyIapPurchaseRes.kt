package com.ifmix.core.api.dto.payment

/** IAP 购买验证响应（GraphQL `type VerifyIapPurchaseResult` 字段级一致）。 */
data class VerifyIapPurchaseRes(
    /** 付费等级。10=FREE, 20=PRO, 30=ENTERPRISE。 */
    val tier: Int,
    /** 订阅过期时间（ISO-8601 字符串；原 GraphQL DateTime）。 */
    val expiresAt: String?,
)
