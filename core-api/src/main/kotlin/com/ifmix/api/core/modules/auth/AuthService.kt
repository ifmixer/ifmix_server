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
 * 流程：
 * 1. loginWithProvider — 验证 provider id_token → upsert identity → ensure app_user → issue tokens
 * 2. exchange — 用 device_secret 换 token 对（跨 app SSO）
 * 3. refresh — 原子轮换 refresh token，签发新 token 对
 * 4. logout — 吊销 refresh + device_secret
 * 5. me — 返回当前已认证用户信息
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
        val cfg = appConfigRepo.getByAppId(ctx.appId) ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val tid = cfg.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val verifier = verifiers[provider] ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "unknown provider")
        val v = verifier.verify(cfg, ctx.clientPlatform, req.idToken!!)

        data class Issued(val identityId: String, val appUserId: String, val deviceSecret: String, val refresh: AppRefreshTokenRepository.RefreshIssued, val access: String)
        val identityId = providerIdentityRepo.upsert(
            tid,
            provider,
            v.accountId,
            v.email,
            v.emailVerified,
            v.phone,
            v.userMetadata,
            null,  // loginIp - not available in VerifiedProvider
            ctx.installId,
            ctx.appId,
        )
        val appUserId = appUserRepo.ensure(ctx.appId, identityId)
        val existing = req.deviceSecret?.let { deviceSecretRepo.findValid(tid, it) }
        val (dsId, dsPlain) = if (existing != null && existing.authIdentity.id.toString() == identityId) {
            deviceSecretRepo.touch(existing.id.toString()); existing.id.toString() to req.deviceSecret!!
        } else {
            deviceSecretRepo.issue(tid, identityId, ctx.installId)
        }
        val refresh = refreshRepo.issue(ctx.appId, appUserId, dsId, ctx.installId)
        val issued = Issued(identityId, appUserId, dsPlain, refresh, jwt.signAccess(appUserId, ctx.appId))
        events.publishEvent(AuthLoggedInEvent(ctx.appId, issued.identityId, issued.appUserId, ctx.installId))
        return LoginRes(issued.access, issued.refresh.token, issued.refresh.expiresAt, issued.deviceSecret,
            accessTtlSec, UserDto(issued.appUserId, v.email))
    }

    @Transactional
    fun exchange(ctx: RequestContext, req: ExchangeReq): ExchangeRes {
        val tid = tenantId(ctx)
        val ds = deviceSecretRepo.findValid(tid, req.deviceSecret!!) ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        if (ds.authTenant.id.toString() != tid) throw ApiError(ErrorCode.UNAUTHORIZED)
        val appUserId = appUserRepo.ensure(ctx.appId, ds.authIdentity.id.toString())
        deviceSecretRepo.touch(ds.id.toString())
        val refresh = refreshRepo.issue(ctx.appId, appUserId, ds.id.toString(), null)
        return ExchangeRes(jwt.signAccess(appUserId, ctx.appId), refresh.token, refresh.expiresAt,
            accessTtlSec, UserDto(appUserId, null))
    }

    @Transactional
    fun refresh(ctx: RequestContext, req: RefreshReq): RefreshRes {
        val row = refreshRepo.findByHash(ctx.appId, req.refreshToken!!) ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        if (row.revokedAt != null) {                       // 重放：撤销该用户全部 refresh
            refreshRepo.revokeByAppUser(ctx.appId, row.appUser.id.toString())
            throw ApiError(ErrorCode.UNAUTHORIZED)
        }
        if (row.expiresAt?.isBefore(Instant.now()) != false) throw ApiError(ErrorCode.UNAUTHORIZED)
        val newId = UUID.randomUUID().toString()
        val won = refreshRepo.tryRotate(ctx.appId, row.tokenHash!!, newId)
        if (!won) throw ApiError(ErrorCode.UNAUTHORIZED)    // 并发失败方
        val deviceId = row.deviceSecret?.id ?: error("Device secret should exist")
        val refresh = refreshRepo.issue(ctx.appId, row.appUser.id.toString(), deviceId, row.loginInstallId, id = newId)
        return RefreshRes(jwt.signAccess(row.appUser.id.toString(), ctx.appId), refresh.token, refresh.expiresAt, accessTtlSec)
    }

    @Transactional
    fun logout(ctx: RequestContext, req: LogoutReq): LogoutRes {
        val row = refreshRepo.findByHash(ctx.appId, req.refreshToken!!)
        row?.deviceSecret?.let { ds ->
            deviceSecretRepo.revoke(ds.id.toString())
            refreshRepo.revokeByDeviceSecret(ds.id.toString())   // 跨该设备所有 app
        }
        return LogoutRes(true)
    }

    fun me(ctx: RequestContext): MeRes {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        return MeRes(userId, null)   // v1 只回 id；email 需要时经 app_user→auth_identity 反查
    }
}