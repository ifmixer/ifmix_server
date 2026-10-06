package com.ifmix.core.api.dto.auth

import java.time.Instant
import java.util.UUID

/** auth 模块 API 入参/出参（wire 契约，字段与 GraphQL input/result 一一对应）。 */
data class LoginInput(val idpId: UUID, val credential: String)

data class RefreshInput(val refreshToken: String)

data class LogoutInput(val refreshToken: String)

data class UserInfoRes(val id: UUID, val email: String?)

data class LoginRes(val accessToken: String, val refreshToken: String, val expiresIn: Int, val user: UserInfoRes)

data class RefreshRes(val accessToken: String, val refreshToken: String, val expiresIn: Int)

data class MeRes(val user: UserInfoRes, val tier: Int, val tierActive: Boolean, val tierExpiresAt: Instant?)
