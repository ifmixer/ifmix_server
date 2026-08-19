package com.ifmix.api.core.modules.payment

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.ratelimit.Tier
import com.ifmix.api.core.common.ratelimit.TierResolver
import com.ifmix.api.core.modules.payment.repo.SubscriptionRepo

/**
 * 基于 IAP 订阅的 TierResolver 实现。
 * 通过查询 [SubscriptionRepo] 判断用户是否有活跃订阅，有则返回 PRO 档。
 */
fun createIapTierResolver(subscriptionRepo: SubscriptionRepo): TierResolver {
    return object : TierResolver {
        override fun resolve(ctx: RequestContext): Tier {
            val userId = ctx.userId ?: return Tier.FREE
            val sub = subscriptionRepo.findActiveBySubject(ctx, userId)
            return if (sub != null && sub.active) Tier.PRO else Tier.FREE
        }
    }
}
