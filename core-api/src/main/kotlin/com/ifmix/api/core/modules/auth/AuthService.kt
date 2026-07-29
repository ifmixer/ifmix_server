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
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Transactional

/**
 * 认证业务编排（组合非继承）。
 *
 * 注意：此为 Jimmer 迁移后的骨架版本，核心业务逻辑待后续完善。
 */
class AuthService(
    private val appConfigRepo: AppConfigRepo,
    private val verifiers: Map<String, ProviderVerifier>,
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
        // TODO: 实现完整 Jimmer 集成
        // 1. verifier.verify(req.idToken) → ProviderProfile
        // 2. upsert AuthProviderIdentity
        // 3. ensure AppUser
        // 4. issue device secret + refresh token
        // 5. sign access token
        throw NotImplementedError("AuthService.loginWithProvider — pending full Jimmer integration")
    }

    @Transactional
    fun exchange(ctx: RequestContext, req: ExchangeReq): ExchangeRes {
        // TODO: validate device secret → issue new refresh + access tokens
        throw NotImplementedError("AuthService.exchange — pending full Jimmer integration")
    }

    @Transactional
    fun refresh(ctx: RequestContext, req: RefreshReq): RefreshRes {
        // TODO: atomic rotation of refresh token
        throw NotImplementedError("AuthService.refresh — pending full Jimmer integration")
    }

    @Transactional
    fun logout(ctx: RequestContext, req: LogoutReq): LogoutRes {
        // TODO: revoke refresh token + device secret
        throw NotImplementedError("AuthService.logout — pending full Jimmer integration")
    }

    fun me(ctx: RequestContext): MeRes {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        return MeRes(userId, null)
    }
}
