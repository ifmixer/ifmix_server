package com.ifmix.api.core.service.auth

import com.ifmix.api.core.infra.ratelimit.Tier
import java.time.Instant

data class UserDto(val id: String, val email: String?)

/** Google / Apple 登录请求。idToken 必填。 */
data class ProviderLoginReq(
    val idToken: String,
    val deviceSecret: String? = null,
)

/** 微信登录请求。code 必填。 */
data class WechatLoginReq(
    val code: String,
    val deviceSecret: String? = null,
)
data class LoginRes(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val deviceSecret: String,
    val expiresIn: Long,
    val user: UserDto,
)

data class ExchangeReq(val deviceSecret: String)
data class ExchangeRes(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val expiresIn: Long,
    val user: UserDto,
)

data class RefreshReq(val refreshToken: String)
data class RefreshRes(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val expiresIn: Long,
)

data class LogoutReq(val refreshToken: String)
data class LogoutRes(val ok: Boolean)

data class MeRes(
    val id: String,
    val email: String?,
    /** 当前订阅档位 */
    val tier: Tier = Tier.FREE,
    /** 订阅是否有效 */
    val active: Boolean = false,
    /** 订阅过期时间（epoch millis），永久权益为 null */
    val expiresAt: Long? = null,
)
