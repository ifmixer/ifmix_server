package com.ifmix.api.core.service.auth

import com.ifmix.api.core.infra.ratelimit.Tier
import com.ifmix.api.core.service.iap.SubscriptionState
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
    @io.swagger.v3.oas.annotations.media.Schema(description = "订阅状态，与 VerifyRes.state 相同。active 等价于 state==ACTIVE。")
    val state: SubscriptionState = SubscriptionState.EXPIRED,
    /** 订阅过期时间（epoch millis），永久权益为 null */
    val expiresAt: Long? = null,
    @io.swagger.v3.oas.annotations.media.Schema(description = "是否为匿名用户。匿名用户 email 为 null、tier 为 FREE。")
    val isAnonymous: Boolean = false,
    @io.swagger.v3.oas.annotations.media.Schema(description = "账号删除请求的预计处理时间（epoch millis）。未请求删除时为 null。前端可展示「账号将于 X 日删除，登录可取消」。")
    val deletionScheduledAt: Long? = null,
)

data class DeleteAccountRes(
    @io.swagger.v3.oas.annotations.media.Schema(description = "删除请求已接受")
    val accepted: Boolean = true,
    @io.swagger.v3.oas.annotations.media.Schema(description = "预计处理时间（epoch millis）")
    val scheduledAt: Long,
)
