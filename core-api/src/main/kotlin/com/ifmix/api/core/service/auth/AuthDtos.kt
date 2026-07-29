package com.ifmix.api.core.service.auth

import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class UserDto(val id: String?, val email: String?)

data class LoginReq(
    @field:NotBlank val idToken: String? = null,
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

data class ExchangeReq(@field:NotBlank val deviceSecret: String? = null)
data class ExchangeRes(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val expiresIn: Long,
    val user: UserDto,
)

data class RefreshReq(@field:NotBlank val refreshToken: String? = null)
data class RefreshRes(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val expiresIn: Long,
)

data class LogoutReq(@field:NotBlank val refreshToken: String? = null)
data class LogoutRes(val ok: Boolean)

data class MeRes(val id: String, val email: String?)
