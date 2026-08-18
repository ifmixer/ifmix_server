package com.ifmix.api.core.modules.iap

import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.model.enums.Tier
import com.ifmix.api.core.infra.ratelimit.TierResolver

/**
 * 基于 IAP 订阅的 TierResolver 实现（简化版）。
 * 当前返回固定值，实际需连接到订阅仓库。
 */
fun createIapTierResolver(): TierResolver {
    return object : TierResolver {
        override fun resolve(ctx: OperationContext): Tier = Tier.FREE // Stubbed - needs Jimmer subscription query
    }
}
