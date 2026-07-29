package com.ifmix.api.core.service.iap

import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.infra.ratelimit.Tier
import com.ifmix.api.core.infra.ratelimit.TierResolver

/**
 * 基于 IAP 订阅的 TierResolver 实现（简化版）。
 * 当前返回固定值，实际需连接到订阅仓库。
 */
fun createIapTierResolver(): TierResolver {
    return object : TierResolver {
        override fun resolve(ctx: RequestContext): Tier = Tier.FREE // Stubbed - needs Jimmer subscription query
    }
}
