package com.ifmix.core.api.modules.pay

import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.entity.common.Tiers
import com.ifmix.core.api.infra.ratelimit.TierResolver

/**
 * 基于 IAP 订阅的 TierResolver 实现（简化版）。
 * 当前返回固定值，实际需连接到订阅仓库。
 */
fun createIapTierResolver(): TierResolver {
    return object : TierResolver {
        override fun resolve(ctx: OperationContext): Int = Tiers.FREE // Stubbed - needs Jimmer subscription query
    }
}
