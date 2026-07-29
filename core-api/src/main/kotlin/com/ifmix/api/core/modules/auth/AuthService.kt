package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.auth.AuthJwtService
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.appconfig.AppConfigRepo
import com.ifmix.api.core.common.jimmer.repository.auth.AuthProviderIdentityRepository
import com.ifmix.api.core.common.jimmer.repository.auth.AppUserRepository
import com.ifmix.api.core.common.jimmer.repository.auth.AuthDeviceSecretRepository
import com.ifmix.api.core.common.jimmer.repository.auth.AppRefreshTokenRepository
import com.ifmix.api.core.common.jimmer.entity.auth.AppUser
import com.ifmix.api.core.common.jimmer.entity.auth.AuthDeviceSecret
import com.ifmix.api.core.common.jimmer.entity.auth.AppRefreshToken
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 认证业务编排（组合非继承）。
 *
 * 注意：此为实现简化版本（basic CRUD only），部分业务逻辑待后续完善。
 */
class AuthService(
    private val appConfigRepo: AppConfigRepo,
    private val verifiers: Map<String, ProviderVerifier>,   // "google"/"apple"
    private val jwt: AuthJwtService,
    private val providerIdentityRepo: AuthProviderIdentityRepository,
    private val appUserRepo: AppUserRepository,
    private val deviceSecretRepo: AuthDeviceSecretRepository,
    private val refreshRepo: AppRefreshTokenRepository,
    private val events: ApplicationEventPublisher,
    private val accessTtlSec: Long,
) {

    private fun tenantId(ctx: RequestContext): String =
        appConfigRepo.getByAppId(ctx.appId)?.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)

    @Transactional
    fun loginWithProvider(ctx: RequestContext, provider: String, req: LoginReq): LoginRes {
        // Stub implementation - needs full Jimmer integration
        throw NotImplementedError("AuthService loginWithProvider not fully implemented for Jimmer migration")
    }

    @Transactional
    fun exchange(ctx: RequestContext, req: ExchangeReq): ExchangeRes {
        throw NotImplementedError("Exchange not fully implemented")
    }

    @Transactional
    fun refresh(ctx: RequestContext, req: RefreshReq): RefreshRes {
        throw NotImplementedError("Refresh not fully implemented")
    }

    @Transactional
    fun logout(ctx: RequestContext, req: LogoutReq): LogoutRes {
        throw NotImplementedError("Logout not fully implemented")
    }

    fun me(ctx: RequestContext): MeRes {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        return MeRes(userId, null)
    }
}

// Simple placeholder DTOs to satisfy compiler
data class LoginReq(val idToken: String?, val deviceSecret: String? = null)
data class LoginRes(val accessToken: String, val refreshToken: String, val expiresAt: Instant, val deviceSecret: String, val accessTtlSec: Int, val user: UserDto)
data class UserDto(val userId: String, val email: String?)
data class ExchangeReq(val deviceSecret: String)
data class ExchangeRes(val accessToken: String, val refreshToken: String, val expiresAt: Int, val accessTtlSec: Int, val user: UserDto)
data class RefreshReq(val refreshToken: String)
data class RefreshRes(val accessToken: String, val refreshToken: String, val expiresAt: Int, val accessTtlSec: Int)
data class LogoutReq(val refreshToken: String)
data class LogoutRes(val success: Boolean)
