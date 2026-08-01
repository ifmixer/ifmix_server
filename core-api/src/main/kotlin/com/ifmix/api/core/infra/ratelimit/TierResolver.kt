package com.ifmix.api.core.infra.ratelimit

import org.babyfish.jimmer.sql.EnumItem
import org.babyfish.jimmer.sql.EnumType

/**
 * 限额档位：按用户身份自动判定。
 *
 * 同时作为 Jimmer entity 属性类型（SMALLINT 存储）。
 * 编码：FREE=10, PRO=20, ENTERPRISE=30。
 */
@EnumType(EnumType.Strategy.ORDINAL)
enum class Tier(val code: Int) {
    @EnumItem(ordinal = 100)
    FREE(100),

    @EnumItem(ordinal = 200)
    PRO(200),

    @EnumItem(ordinal = 300)
    ENTERPRISE(300);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): Tier = byCode[code]
            ?: throw IllegalArgumentException("Unknown Tier code: $code")

        /** 默认返回 FREE。 */
        fun from(ctx: com.ifmix.api.core.infra.http.RequestContext): Tier = FREE
    }
}

/** 将请求上下文映射为一个 Tier。默认实现一律返回 FREE。 */
interface TierResolver {
    fun resolve(ctx: com.ifmix.api.core.infra.http.RequestContext): Tier
}

/** 基础实现：所有用户走 FREE 档。后续可按付费等级/白名单等扩展。 */
class FreeTierResolver : TierResolver {
    override fun resolve(ctx: com.ifmix.api.core.infra.http.RequestContext): Tier = Tier.FREE
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
    fun resolve(ctx: com.ifmix.api.core.infra.http.RequestContext, clientIp: String): String
}

/** 默认实现：userId ?: ip ?: "unknown"。 */
class DefaultRateLimitSubjectResolver : RateLimitSubjectResolver {
    override fun resolve(ctx: com.ifmix.api.core.infra.http.RequestContext, clientIp: String): String =
        ctx.userId ?: if (clientIp.isNotBlank()) clientIp else "unknown"
}
