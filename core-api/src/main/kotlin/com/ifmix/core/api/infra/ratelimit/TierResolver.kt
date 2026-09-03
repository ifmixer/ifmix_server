package com.ifmix.core.api.infra.ratelimit

import com.ifmix.core.api.entity.common.Tiers
import com.ifmix.core.api.infra.http.OperationContext

/** 将请求上下文映射为一个 Tier。默认实现一律返回 FREE。 */
interface TierResolver {
    fun resolve(ctx: OperationContext): Int
}

/** 基础实现：所有用户走 FREE 档。后续可按付费等级/白名单等扩展。 */
class FreeTierResolver : TierResolver {
    override fun resolve(ctx: OperationContext): Int = Tiers.FREE
}

/**
 * 限流主体解析器。
 * 优先使用 userId（已认证用户），fallback 到 "unknown"。
 * 不再使用 installId 作为限流主体（installId 可伪造）。
 */
interface RateLimitSubjectResolver {
    /**
     * @param clientIp 客户端真实 IP（由调用方传入）
     */
    fun resolve(ctx: OperationContext, clientIp: String): String
}

/** 默认实现：userId ?: ip ?: "unknown"。 */
class DefaultRateLimitSubjectResolver : RateLimitSubjectResolver {
    override fun resolve(ctx: OperationContext, clientIp: String): String =
        ctx.actorId?.toString() ?: if (clientIp.isNotBlank()) clientIp else "unknown"
}
