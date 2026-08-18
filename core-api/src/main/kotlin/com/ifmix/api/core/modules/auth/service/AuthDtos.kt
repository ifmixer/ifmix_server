package com.ifmix.api.core.modules.auth.service

import java.time.Instant
import java.util.UUID

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

data class UserDto(val id: UUID, val email: String?)

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
    val id: UUID,
    val email: String?,
    /** 当前订阅档位 */
    val tier: Int = com.ifmix.api.core.model.shared.Tiers.FREE,
    /** 订阅是否有效 */
    val active: Boolean = false,
    val state: com.ifmix.api.core.dto.iap.SubscriptionState = com.ifmix.api.core.dto.iap.SubscriptionState.EXPIRED,
    /** 订阅过期时间（epoch millis），永久权益为 null */
    val expiresAt: Long? = null,
    /** 是否为匿名用户。匿名用户 email 为 null、tier 为 FREE。 */
    val isAnonymous: Boolean = false,
    /** 账号删除请求的预计处理时间（epoch millis）。未请求删除时为 null。 */
    val deletionScheduledAt: Long? = null,
)

data class DeleteAccountRes(
    /** 删除请求已接受 */
    val accepted: Boolean = true,
    /** 预计处理时间（epoch millis） */
    val scheduledAt: Long,
)
