package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.auth.AuthJwtService
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ClientIpResolver
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.tx.TxRunner
import com.ifmix.api.core.modules.appconfig.repo.AppConfigRepo
import jakarta.servlet.http.HttpServletRequest
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant
import com.ifmix.api.core.modules.auth.repo.AppRefreshTokenRepo
import com.ifmix.api.core.modules.auth.repo.AuthProviderIdentityRepo
import com.ifmix.api.core.modules.auth.repo.AppUserRepo
import com.ifmix.api.core.modules.auth.repo.AuthDeviceSecretRepo
import com.ifmix.api.core.modules.auth.repo.AuthTenantRepo

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
    private val providerIdentityRepo: AuthProviderIdentityRepo,
    private val appUserRepo: AppUserRepo,
    private val deviceSecretRepo: AuthDeviceSecretRepo,
    private val refreshRepo: AppRefreshTokenRepo,
    private val txRunner: TxRunner,
    private val events: ApplicationEventPublisher,
    private val accessTtlSec: Long,
) {

    private fun tenantId(ctx: RequestContext): String =
        appConfigRepo.getByAppId(ctx.appId)?.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)

    /**
     * 从当前请求上下文中提取客户端 IP。
     * 安全回落：无请求上下文时返回 null（异步场景）。
     */
    private fun resolveClientIp(): String? = try {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        attrs?.request?.let { ClientIpResolver.resolve(it) }
    } catch (_: Exception) {
        null
    }

    fun loginWithProvider(ctx: RequestContext, provider: String, req: LoginReq): LoginRes {
        val cfg = appConfigRepo.getByAppId(ctx.appId) ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val tid = cfg.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val verifier = verifiers[provider] ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "unknown provider")
        val v = verifier.verify(cfg, ctx.clientPlatform, req.idToken!!)

        data class Issued(val identityId: String, val appUserId: String, val deviceSecret: String, val refresh: RefreshIssued, val access: String)
        val issued = txRunner.withTx(ctx) {
            val identityId = providerIdentityRepo.upsert(
                tid,
                UpsertInput(provider, v.accountId, v.email, v.emailVerified, v.phone, v.userMetadata,
                    null, ctx.installId, ctx.appId),
            )
            val appUserId = appUserRepo.ensure(ctx.appId, identityId)
            val existing = req.deviceSecret?.let { deviceSecretRepo.findValid(tid, it) }
            val (dsId, dsPlain) = if (existing != null && existing.authIdentityId == identityId) {
                deviceSecretRepo.touch(existing.id.toHexString()); existing.id.toHexString() to req.deviceSecret!!
            } else {
                deviceSecretRepo.issue(tid, identityId, ctx.installId)
            }
            val refresh = refreshRepo.issue(ctx.appId, appUserId, dsId, ctx.installId)
            Issued(identityId, appUserId, dsPlain, refresh, jwt.signAccess(appUserId, ctx.appId))
        }

        // 捕获当前请求的客户端 IP 和平台，随事件传递
        val clientIp = resolveClientIp()
        val clientPlatform = ctx.clientPlatform?.name
        events.publishEvent(AuthLoggedInEvent(
            appId = ctx.appId,
            authIdentityId = issued.identityId,
            appUserId = issued.appUserId,
            installId = ctx.installId,
            clientIp = clientIp,
            clientPlatform = clientPlatform,
            ctx = ctx,
        ))
        return LoginRes(issued.access, issued.refresh.token, issued.refresh.expiresAt, issued.deviceSecret,
            accessTtlSec, UserDto(issued.appUserId, v.email))
    }

    fun exchange(ctx: RequestContext, req: ExchangeReq): ExchangeRes {
        val tid = tenantId(ctx)
        val ds = deviceSecretRepo.findValid(tid, req.deviceSecret!!) ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        if (ds.authTenantId != tid) throw ApiError(ErrorCode.UNAUTHORIZED)
        return txRunner.withTx(ctx) {
            val appUserId = appUserRepo.ensure(ctx.appId, ds.authIdentityId!!)
            deviceSecretRepo.touch(ds.id.toHexString())
            val refresh = refreshRepo.issue(ctx.appId, appUserId, ds.id.toHexString(), null)
            ExchangeRes(jwt.signAccess(appUserId, ctx.appId), refresh.token, refresh.expiresAt,
                accessTtlSec, UserDto(appUserId, null))
        }
    }

    fun refresh(ctx: RequestContext, req: RefreshReq): RefreshRes {
        val row = refreshRepo.findByHash(ctx.appId, req.refreshToken!!) ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        if (row.revokedAt != null) {                       // 重放：撤销该用户全部 refresh
            refreshRepo.revokeByAppUser(ctx.appId, row.appUserId!!)
            throw ApiError(ErrorCode.UNAUTHORIZED)
        }
        if (row.expiresAt?.isBefore(Instant.now()) != false) throw ApiError(ErrorCode.UNAUTHORIZED)
        val newId = ObjectId().toHexString()
        val won = refreshRepo.tryRotate(ctx.appId, row.tokenHash!!, newId)
        if (!won) throw ApiError(ErrorCode.UNAUTHORIZED)    // 并发失败方
        val refresh = refreshRepo.issue(ctx.appId, row.appUserId!!, row.deviceSecretId!!, row.loginInstallId, id = newId)
        return RefreshRes(jwt.signAccess(row.appUserId!!, ctx.appId), refresh.token, refresh.expiresAt, accessTtlSec)
    }

    fun logout(ctx: RequestContext, req: LogoutReq): LogoutRes {
        val row = refreshRepo.findByHash(ctx.appId, req.refreshToken!!)
        row?.deviceSecretId?.let {
            deviceSecretRepo.revoke(it)
            refreshRepo.revokeByDeviceSecret(it)   // 跨该设备所有 app
        }
        return LogoutRes(true)
    }

    fun me(ctx: RequestContext): MeRes {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        return MeRes(userId, null)   // v1 只回 id；email 需要时经 app_user→auth_identity 反查
    }
}
