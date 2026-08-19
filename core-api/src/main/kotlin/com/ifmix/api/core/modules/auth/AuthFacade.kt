package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.auth.AuthJwtService
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.OperationContext
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.tx.TxRunner
import com.ifmix.api.core.modules.app.repo.AppConfigRepo
import com.ifmix.api.core.modules.auth.handler.AuthEntityHandler
import com.ifmix.api.core.modules.auth.repo.AppRefreshTokenRepo
import com.ifmix.api.core.modules.auth.repo.AppUserRepo
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

/**
 * 认证门面：编排所有 auth 操作，供 GraphQL Fetcher 调用。
 *
 * 流程：
 * 1. loginWithProvider — 验证 provider id_token → upsert identity → ensure app_user → issue tokens
 * 2. exchange — 用 device_secret 换 token 对（跨 app SSO）
 * 3. refresh — 原子轮换 refresh token，签发新 token 对
 * 4. logout — 吊销 refresh + device_secret
 * 5. me — 返回当前已认证用户信息
 */
class AuthFacade(
    private val appConfigRepo: AppConfigRepo,
    private val verifiers: Map<String, ProviderVerifier>,
    private val jwt: AuthJwtService,
    private val appUserRepo: AppUserRepo,
    private val refreshRepo: AppRefreshTokenRepo,
    private val entityHandler: AuthEntityHandler,
    private val txRunner: TxRunner,
    private val events: ApplicationEventPublisher,
    private val accessTtlSec: Long,
) {
    private fun tenantId(ctx: RequestContext): String =
        appConfigRepo.getByAppId(ctx.appId)?.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)

    /**
     * 通过 OAuth provider 登录（google/apple）。
     * @return AuthLoginResult with tokens and user info
     */
    fun loginWithProvider(ctx: RequestContext, provider: String, idToken: String, deviceSecret: String? = null): AuthLoginResult {
        val cfg = appConfigRepo.getByAppId(ctx.appId) ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val tid = cfg.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val verifier = verifiers[provider] ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "unknown provider")
        val v = verifier.verify(cfg, ctx.clientPlatform, idToken)

        data class Issued(
            val identityId: ObjectId,
            val appUserId: String,
            val deviceSecret: String,
            val refresh: RefreshIssued,
            val access: String,
        )
        val issued = txRunner.withTx(OperationContext.from(ctx)) {
            val identityId = entityHandler.upsertProviderIdentity(
                tid,
                UpsertInput(
                    provider = provider,
                    accountId = v.accountId,
                    email = v.email,
                    emailVerified = v.emailVerified,
                    phone = v.phone,
                    userMetadata = v.userMetadata,
                    loginIp = null,
                    loginInstallId = ctx.installId,
                    loginAppId = ctx.appId,
                ),
            )
            val appUserId = appUserRepo.ensure(ctx.appId, identityId)
            val existing = deviceSecret?.let { entityHandler.findValidDeviceSecret(tid, it) }
            val (dsId, dsPlain) = if (existing != null && existing.authIdentityId == identityId) {
                entityHandler.touchDeviceSecret(existing.id.toHexString())
                existing.id.toHexString() to deviceSecret!!
            } else {
                entityHandler.issueDeviceSecret(OperationContext.from(ctx), tid, identityId)
            }
            val refresh = refreshRepo.issue(ctx.appId, appUserId, dsId, ctx.installId)
            Issued(identityId, appUserId, dsPlain, refresh, jwt.signAccess(appUserId, ctx.appId))
        }

        events.publishEvent(AuthLoggedInEvent(
            appId = ctx.appId,
            authIdentityId = issued.identityId.toHexString(),
            appUserId = issued.appUserId,
            installId = ctx.installId,
            clientIp = null,
            clientPlatform = ctx.clientPlatform?.name,
            ctx = ctx,
        ))
        return AuthLoginResult(
            accessToken = issued.access,
            refreshToken = issued.refresh.token,
            refreshExpiresAt = issued.refresh.expiresAt,
            deviceSecret = issued.deviceSecret,
            expiresIn = accessTtlSec,
            user = AuthUserDto(issued.appUserId, v.email),
        )
    }

    fun exchange(ctx: RequestContext, deviceSecret: String): AuthExchangeResult {
        val tid = tenantId(ctx)
        val ds = entityHandler.findValidDeviceSecret(tid, deviceSecret)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        if (ds.authTenantId != tid) throw ApiError(ErrorCode.UNAUTHORIZED)
        return txRunner.withTx(OperationContext.from(ctx)) {
            val appUserId = appUserRepo.ensure(ctx.appId, ds.authIdentityId!!)
            entityHandler.touchDeviceSecret(ds.id.toHexString())
            val refresh = refreshRepo.issue(ctx.appId, appUserId, ds.id.toHexString(), null)
            AuthExchangeResult(
                accessToken = jwt.signAccess(appUserId, ctx.appId),
                refreshToken = refresh.token,
                refreshExpiresAt = refresh.expiresAt,
                expiresIn = accessTtlSec,
                user = AuthUserDto(appUserId, null),
            )
        }
    }

    fun refresh(ctx: RequestContext, refreshToken: String): AuthRefreshResult {
        val row = refreshRepo.findByHash(ctx.appId, refreshToken) ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        if (row.revokedAt != null) {
            refreshRepo.revokeByAppUser(ctx.appId, row.appUserId!!.toHexString())
            throw ApiError(ErrorCode.UNAUTHORIZED)
        }
        if (row.expiresAt?.isBefore(Instant.now()) != false) throw ApiError(ErrorCode.UNAUTHORIZED)
        val newId = ObjectId().toHexString()
        val won = refreshRepo.tryRotate(ctx.appId, row.tokenHash!!, newId)
        if (!won) throw ApiError(ErrorCode.UNAUTHORIZED)
        val refreshed = refreshRepo.issue(
            ctx.appId, row.appUserId!!.toHexString(), row.deviceSecretId!!.toHexString(),
            row.loginInstallId, id = newId,
        )
        return AuthRefreshResult(
            accessToken = jwt.signAccess(row.appUserId!!.toHexString(), ctx.appId),
            refreshToken = refreshed.token,
            refreshExpiresAt = refreshed.expiresAt,
            expiresIn = accessTtlSec,
        )
    }

    fun logout(ctx: RequestContext, refreshToken: String): Boolean {
        val row = refreshRepo.findByHash(ctx.appId, refreshToken)
        row?.deviceSecretId?.let {
            entityHandler.touchDeviceSecret(it.toHexString()) // no-op for logout, just touch for safety
            refreshRepo.revokeByDeviceSecret(it.toHexString())
        }
        return true
    }

    fun me(ctx: RequestContext): AuthUserDto {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        return AuthUserDto(userId, null)
    }
}

// ---- Result DTOs ----

data class AuthUserDto(
    val id: String,
    val email: String?,
)

data class AuthLoginResult(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val deviceSecret: String,
    val expiresIn: Long,
    val user: AuthUserDto,
)

data class AuthExchangeResult(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val expiresIn: Long,
    val user: AuthUserDto,
)

data class AuthRefreshResult(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val expiresIn: Long,
)
