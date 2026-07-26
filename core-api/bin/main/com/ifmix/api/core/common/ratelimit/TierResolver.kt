package com.ifmix.api.core.common.ratelimit

/** 限额档位：按用户身份自动判定。 */
enum class Tier {
    FREE,
    PRO,
    ENTERPRISE;

    companion object {
        /** 默认返回 FREE，子类覆写 [resolve] 实现自定义逻辑。 */
        fun from(ctx: com.ifmix.api.core.common.http.RequestContext): Tier = FREE
    }
}

/** 将请求上下文映射为一个 Tier。默认实现一律返回 FREE。 */
interface TierResolver {
    fun resolve(ctx: com.ifmix.api.core.common.http.RequestContext): Tier
}

/** 基础实现：所有用户走 FREE 档。后续可按付费等级/白名单等扩展。 */
class FreeTierResolver : TierResolver {
    override fun resolve(ctx: com.ifmix.api.core.common.http.RequestContext): Tier = Tier.FREE
}
