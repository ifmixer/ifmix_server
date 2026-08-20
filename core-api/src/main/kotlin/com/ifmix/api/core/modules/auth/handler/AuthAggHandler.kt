package com.ifmix.api.core.modules.auth.handler

import com.ifmix.api.core.entity.auth.AppRefreshToken
import com.ifmix.api.core.entity.auth.AuthDeviceSecret
import com.ifmix.api.core.entity.auth.AuthIdentity
import com.ifmix.api.core.infra.auth.AuthJwtService
import com.ifmix.api.core.infra.auth.Hashing
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.modules.app.AppConfigFacade
import com.ifmix.api.core.modules.auth.AuthLoggedInEvent
import com.ifmix.api.core.modules.auth.ProviderVerifier
import com.ifmix.api.core.modules.auth.repo.AppRefreshTokenRepository
import com.ifmix.api.core.modules.auth.repo.AppUserRepository
import com.ifmix.api.core.modules.auth.repo.AuthDeviceSecretRepository
import com.ifmix.api.core.modules.auth.repo.AuthIdentityRepository
import com.ifmix.api.core.modules.auth.repo.AuthProviderIdentityRepository
import com.ifmix.api.core.dto.payment.SubscriptionState
import com.ifmix.api.core.entity.shared.Tiers
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

// ============================================================================
// Internal DTOs
// ============================================================================

data class UserDto(val id: UUID, val email: String?)

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
    val id: UUID,
    val email: String?,
    /** 当前订阅档位 */
    val tier: Int = Tiers.FREE,
    /** 订阅是否有效 */
    val active: Boolean = false,
    val state: SubscriptionState = SubscriptionState.EXPIRED,
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

@Component
class AuthAggHandler(
    private val appConfigFacade: AppConfigFacade,
    private val verifiers: Map<String, ProviderVerifier>,
    private val jwt: AuthJwtService,
    private val providerIdentityRepo: AuthProviderIdentityRepository,
    private val appUserRepo: AppUserRepository,
    private val deviceSecretRepo: AuthDeviceSecretRepository,
    private val refreshRepo: AppRefreshTokenRepository,
    private val identityRepo: AuthIdentityRepository,
    private val events: ApplicationEventPublisher,
    @Value("\${app.auth.access-ttl-sec:900}")
    private val accessTtlSec: Long,
) {
    companion object {
        private const val REFRESH_TTL_DAYS = 30L
        private const val DEVICE_SECRET_TTL_DAYS = 365L
    }

    private fun tenantUUID(sc: ModuleCtx, appId: UUID): UUID =
        appConfigFacade.findActiveByAppId(sc, appId)?.authTenantId
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)

    fun me(sc: ModuleCtx): MeRes {
        val userId = sc.op.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        val appUser = appUserRepo.findById(sc, sc.appId!!, userId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "user not found")
        val identity = identityRepo.findById(sc, appUser.authIdentity.id)
        return MeRes(userId, identity?.email)
    }

    fun loginWithIdToken(sc: ModuleCtx, provider: String, req: ProviderLoginReq): LoginRes {
        return loginWithProvider(sc, provider, req.idToken, req.deviceSecret)
    }

    fun loginWithCode(sc: ModuleCtx, provider: String, req: WechatLoginReq): LoginRes {
        return loginWithProvider(sc, provider, req.code, req.deviceSecret)
    }

    fun loginWithProvider(sc: ModuleCtx, provider: String, credential: String, deviceSecret: String? = null): LoginRes {
        val opCtx = sc.op
        val tenantId = tenantUUID(sc, opCtx.appId!!)

        // 1. Verify credential
        val verifier = verifiers[provider] ?: throw ApiError(
            ErrorCode.AUTH_PROVIDER_FAILED,
            "unsupported provider: $provider"
        )
        val config = appConfigFacade.findActiveByAppId(sc, opCtx.appId!!) ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val verified = verifier.verify(config, opCtx.clientPlatform, credential)

        // 2. Find or create AuthIdentity
        val normalizedEmail = verified.email?.lowercase()
        val identity: AuthIdentity = if (normalizedEmail != null) {
            identityRepo.findByTenantAndEmail(sc, tenantId, normalizedEmail)
                ?: insertIdentity(sc, tenantId, verified.email, normalizedEmail, verified.phone, verified.userMetadata)
        } else {
            val existingProvider = providerIdentityRepo.findByProviderAndAccountId(
                sc, tenantId, provider, verified.accountId
            )
            if (existingProvider != null) {
                identityRepo.findById(sc, existingProvider.authIdentity.id)!!
            } else {
                insertIdentity(sc, tenantId, null, null, verified.phone, verified.userMetadata)
            }
        }

        // 3. Upsert AuthProviderIdentity
        providerIdentityRepo.upsert(
            mc = sc,
            tenantId = tenantId,
            provider = provider,
            providerAccountId = verified.accountId,
            identityId = identity.id,
            email = verified.email,
            emailVerified = verified.emailVerified,
            phone = verified.phone,
            userMetadata = verified.userMetadata,
            providerMetadata = null,
            loginIp = opCtx.clientIp,
            loginInstallId = opCtx.installId,
            loginAppId = opCtx.appId,
        )

        // 4. Ensure AppUser
        val appUserId = appUserRepo.ensure(sc, opCtx.appId!!, identity.id)

        // 5. Issue device secret
        val now = Instant.now()
        val rawDeviceSecret = Hashing.randomTokenBase64Url()
        val deviceSecretHash = Hashing.sha256Base64Url(rawDeviceSecret)
        deviceSecretRepo.save(sc, AuthDeviceSecret {
            id = UuidV7.generate()
            authTenant { id = tenantId }
            authIdentity { id = identity.id }
            secretHash = deviceSecretHash
            loginInstallId = opCtx.installId
            expiresAt = now.plusSeconds(DEVICE_SECRET_TTL_DAYS * 86400)
            revokedAt = null
            lastUsedAt = now
            createdAt = now
            updatedAt = now
        })

        // 6. Issue refresh token
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        refreshRepo.save(sc, AppRefreshToken {
            this.id = UuidV7.generate()
            this.appId = opCtx.appId!!
            appUser { id = appUserId }
            this.deviceSecretId = null
            this.tokenHash = refreshTokenHash
            this.loginInstallId = opCtx.installId
            this.expiresAt = refreshExpiresAt
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })

        // 7. Sign access token
        val accessToken = jwt.signAccess(appUserId.toString(), opCtx.appId!!.toString())

        // 8. Publish event
        events.publishEvent(AuthLoggedInEvent(
            appId = opCtx.appId!!,
            authIdentityId = identity.id.toString(),
            appUserId = appUserId,
            installId = opCtx.installId,
            clientIp = opCtx.clientIp,
            clientPlatform = opCtx.clientPlatform?.name,
            ctx = opCtx,
        ))

        return LoginRes(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            deviceSecret = rawDeviceSecret,
            expiresIn = accessTtlSec,
            user = UserDto(id = appUserId, email = identity.email),
        )
    }

    fun exchange(sc: ModuleCtx, req: ExchangeReq): ExchangeRes {
        val appId = sc.appId!!

        val secretHash = Hashing.sha256Base64Url(req.deviceSecret!!)
        val foundSecret = deviceSecretRepo.findValidByHash(sc, secretHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired device secret")

        deviceSecretRepo.touch(sc, foundSecret.id)
        val identityId = foundSecret.authIdentity.id
        val appUserId = appUserRepo.ensure(sc, appId, identityId)

        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val now = Instant.now()
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        refreshRepo.save(sc, AppRefreshToken {
            this.id = UuidV7.generate()
            this.appId = appId
            appUser { id = appUserId }
            deviceSecret { id = foundSecret.id }
            this.tokenHash = refreshTokenHash
            this.loginInstallId = sc.installId
            this.expiresAt = refreshExpiresAt
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })

        val accessToken = jwt.signAccess(appUserId.toString(), appId.toString())
        return ExchangeRes(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            expiresIn = accessTtlSec,
            user = UserDto(id = appUserId, email = identityRepo.findById(sc, identityId)?.email),
        )
    }

    fun refresh(sc: ModuleCtx, req: RefreshReq): RefreshRes {
        val appId = sc.appId!!

        val tokenHash = Hashing.sha256Base64Url(req.refreshToken!!)
        val oldToken = refreshRepo.findValidByHash(sc, appId, tokenHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired refresh token")

        val rawNewToken = Hashing.randomTokenBase64Url()
        val newTokenHash = Hashing.sha256Base64Url(rawNewToken)
        val now = Instant.now()
        val newExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        val newTokenId = UuidV7.generate()

        refreshRepo.save(sc, AppRefreshToken {
            this.id = newTokenId
            this.appId = appId
            appUser { id = oldToken.appUser.id }
            this.tokenHash = newTokenHash
            this.loginInstallId = oldToken.loginInstallId
            this.expiresAt = newExpiresAt
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })
        refreshRepo.revoke(sc, oldToken.id, replacedBy = newTokenId)

        val appUserId = oldToken.appUser.id
        val accessToken = jwt.signAccess(appUserId.toString(), appId.toString())
        return RefreshRes(
            accessToken = accessToken,
            refreshToken = rawNewToken,
            refreshExpiresAt = newExpiresAt,
            expiresIn = accessTtlSec,
        )
    }

    fun logout(sc: ModuleCtx, req: LogoutReq): LogoutRes {
        val appId = sc.appId!!

        val tokenHash = Hashing.sha256Base64Url(req.refreshToken!!)
        val token = refreshRepo.findValidByHash(sc, appId, tokenHash)
        if (token != null) {
            refreshRepo.revoke(sc, token.id)
            token.deviceSecret?.id?.let { dsId ->
                deviceSecretRepo.revoke(sc, dsId)
            }
        }
        return LogoutRes(ok = true)
    }

    fun anonymousLogin(sc: ModuleCtx): LoginRes {
        val installId = sc.installId ?: throw ApiError(
            ErrorCode.INVALID_REQUEST,
            "x-install-id required for anonymous login"
        )
        return loginWithProvider(sc, "anonymous", "anon_$installId", null)
    }

    fun requestAccountDeletion(sc: ModuleCtx): DeleteAccountRes {
        val userId = sc.op.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        val scheduledAt = Instant.now().plusSeconds(30L * 24 * 3600).toEpochMilli()
        return DeleteAccountRes(accepted = true, scheduledAt = scheduledAt)
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private fun insertIdentity(
        sc: ModuleCtx,
        tenantId: UUID,
        rawEmail: String?,
        email: String?,
        phone: String?,
        userMetadata: Map<String, Any?>?,
    ): AuthIdentity {
        val now = Instant.now()
        val identity = AuthIdentity {
            this.id = UuidV7.generate()
            this.authTenant { this.id = tenantId }
            this.rawEmail = rawEmail
            this.email = email
            this.rawPhone = phone
            this.phone = phone
            this.contactEmail = email
            this.displayName = userMetadata?.get("name") as? String
            this.passwordHash = null
            this.profile = null
            this.metadata = null
            this.createdAt = now
            this.updatedAt = now
        }
        identityRepo.save(sc, identity)
        return identity
    }
}
