package com.ifmix.api.core.service.auth

import com.ifmix.api.core.entity.auth.AppRefreshToken
import com.ifmix.api.core.entity.auth.AuthDeviceSecret
import com.ifmix.api.core.entity.auth.AuthIdentity
import com.ifmix.api.core.infra.auth.AuthJwtService
import com.ifmix.api.core.infra.auth.Hashing
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.repository.auth.AppRefreshTokenRepository
import com.ifmix.api.core.repository.auth.AppUserRepository
import com.ifmix.api.core.repository.auth.AuthDeviceSecretRepository
import com.ifmix.api.core.repository.auth.AuthIdentityRepository
import com.ifmix.api.core.repository.auth.AuthProviderIdentityRepository
import com.ifmix.api.core.service.appconfig.AppConfigRepo
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 认证业务编排（组合非继承）。
 *
 * 提供完整的 provider 登录、设备密钥交换、refresh 轮转、logout 流程。
 */
open class AuthService(
    private val appConfigRepo: AppConfigRepo,
    private val verifiers: Map<String, ProviderVerifier>,
    private val jwt: AuthJwtService,
    private val providerIdentityRepo: AuthProviderIdentityRepository,
    private val appUserRepo: AppUserRepository,
    private val deviceSecretRepo: AuthDeviceSecretRepository,
    private val refreshRepo: AppRefreshTokenRepository,
    private val identityRepo: AuthIdentityRepository,
    private val events: ApplicationEventPublisher,
    private val accessTtlSec: Long,
) {

    companion object {
        private const val REFRESH_TTL_DAYS = 30L
        private const val DEVICE_SECRET_TTL_DAYS = 365L
    }

    private fun tenantId(ctx: RequestContext): String =
        appConfigRepo.getByAppId(ctx.appId)?.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)

    @Transactional
    fun loginWithProvider(ctx: RequestContext, provider: String, req: LoginReq): LoginRes {
        // 1. Resolve app config & tenant
        val config = appConfigRepo.getByAppId(ctx.appId) ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val tenantId = config.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val tenantUUID = UUID.fromString(tenantId)

        // 2. Get verifier
        val verifier = verifiers[provider] ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "unsupported provider: $provider")

        // 3. Verify id_token
        val verified = verifier.verify(config, ctx.clientPlatform, req.idToken!!)

        // 4. Find or create AuthIdentity
        val normalizedEmail = verified.email?.lowercase()
        val identity = if (normalizedEmail != null) {
            identityRepo.findByTenantAndEmail(tenantId, normalizedEmail) ?: run {
                val newIdentity = AuthIdentity {
                    id = UUID.randomUUID()
                    authTenant { id = tenantUUID }
                    rawEmail = verified.email
                    email = normalizedEmail
                    rawPhone = verified.phone
                    phone = verified.phone
                    contactEmail = normalizedEmail
                    displayName = verified.userMetadata["name"] as? String
                    passwordHash = null
                    profile = null
                    metadata = null
                    createdAt = Instant.now()
                    updatedAt = Instant.now()
                }
                identityRepo.save(newIdentity)
            }
        } else {
            // No email — look up existing provider identity first
            val existingProvider = providerIdentityRepo.findByProviderAndAccountId(
                tenantId, provider, verified.accountId
            )
            if (existingProvider != null) {
                identityRepo.findById(existingProvider.authIdentity.id)!!
            } else {
                val newIdentity = AuthIdentity {
                    id = UUID.randomUUID()
                    authTenant { id = tenantUUID }
                    rawEmail = null
                    email = null
                    rawPhone = verified.phone
                    phone = verified.phone
                    contactEmail = null
                    displayName = verified.userMetadata["name"] as? String
                    passwordHash = null
                    profile = null
                    metadata = null
                    createdAt = Instant.now()
                    updatedAt = Instant.now()
                }
                identityRepo.save(newIdentity)
            }
        }

        // 5. Upsert AuthProviderIdentity
        providerIdentityRepo.upsert(
            tenantId = tenantId,
            provider = provider,
            providerAccountId = verified.accountId,
            identityId = identity.id,
            email = verified.email,
            emailVerified = verified.emailVerified,
            phone = verified.phone,
            userMetadata = verified.userMetadata,
            providerMetadata = null,
            loginIp = ctx.clientIp,
            loginInstallId = ctx.installId,
            loginAppId = ctx.appId,
        )

        // 6. Ensure AppUser
        val appUserId = appUserRepo.ensure(UUID.fromString(ctx.appId), identity.id)

        // 7. Issue device secret
        val rawDeviceSecret = Hashing.randomTokenBase64Url()
        val deviceSecretHash = Hashing.sha256Base64Url(rawDeviceSecret)
        val now = Instant.now()
        val deviceSecretEntity = AuthDeviceSecret {
            id = UUID.randomUUID()
            authTenant { id = tenantUUID }
            authIdentity { id = identity.id }
            secretHash = deviceSecretHash
            loginInstallId = ctx.installId
            expiresAt = now.plusSeconds(DEVICE_SECRET_TTL_DAYS * 86400)
            revokedAt = null
            lastUsedAt = now
            createdAt = now
            updatedAt = now
        }
        val savedDeviceSecret = deviceSecretRepo.save(deviceSecretEntity)

        // 8. Issue refresh token
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        val refreshTokenEntity = AppRefreshToken {
            id = UUID.randomUUID()
            this.appId = UUID.fromString(ctx.appId)
            appUser { id = appUserId }
            deviceSecret { id = savedDeviceSecret.id }
            this.tokenHash = refreshTokenHash
            this.loginInstallId = ctx.installId
            expiresAt = refreshExpiresAt
            revokedAt = null
            replacedBy = null
            createdAt = now
            updatedAt = now
        }
        refreshRepo.save(refreshTokenEntity)

        // 9. Sign access token
        val accessToken = jwt.signAccess(appUserId.toString(), ctx.appId)

        // 10. Publish event
        events.publishEvent(
            AuthLoggedInEvent(
                appId = ctx.appId,
                authIdentityId = identity.id.toString(),
                appUserId = appUserId.toString(),
                installId = ctx.installId,
            )
        )

        // 11. Return
        return LoginRes(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            deviceSecret = rawDeviceSecret,
            expiresIn = accessTtlSec,
            user = UserDto(id = appUserId.toString(), email = identity.email),
        )
    }

    @Transactional
    fun exchange(ctx: RequestContext, req: ExchangeReq): ExchangeRes {
        val appId = UUID.fromString(ctx.appId)

        // 1. Hash the provided device secret
        val secretHash = Hashing.sha256Base64Url(req.deviceSecret!!)

        // 2. Find valid device secret
        val foundSecret = deviceSecretRepo.findValidByHash(secretHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired device secret")

        // 3. Touch device secret (update lastUsedAt)
        deviceSecretRepo.touch(foundSecret.id)

        // 4. Find AppUser via identity
        val identityId = foundSecret.authIdentity.id
        val appUserId = appUserRepo.ensure(appId, identityId)

        // 5. Issue new refresh token
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val now = Instant.now()
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        val refreshTokenEntity = AppRefreshToken {
            id = UUID.randomUUID()
            this.appId = appId
            appUser { id = appUserId }
            deviceSecret { id = foundSecret.id }
            this.tokenHash = refreshTokenHash
            this.loginInstallId = ctx.installId
            expiresAt = refreshExpiresAt
            revokedAt = null
            replacedBy = null
            createdAt = now
            updatedAt = now
        }
        refreshRepo.save(refreshTokenEntity)

        // 6. Sign access token
        val accessToken = jwt.signAccess(appUserId.toString(), ctx.appId)

        // 7. Return
        return ExchangeRes(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            expiresIn = accessTtlSec,
            user = UserDto(id = appUserId.toString(), email = identityRepo.findById(identityId)?.email),
        )
    }

    @Transactional
    fun refresh(ctx: RequestContext, req: RefreshReq): RefreshRes {
        val appId = UUID.fromString(ctx.appId)

        // 1. Hash the provided refresh token
        val tokenHash = Hashing.sha256Base64Url(req.refreshToken!!)

        // 2. Find valid token
        val oldToken = refreshRepo.findValidByHash(appId, tokenHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired refresh token")

        // 3. Generate new refresh token
        val rawNewToken = Hashing.randomTokenBase64Url()
        val newTokenHash = Hashing.sha256Base64Url(rawNewToken)
        val now = Instant.now()
        val newExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        val newTokenId = UUID.randomUUID()

        val newTokenEntity = AppRefreshToken {
            id = newTokenId
            this.appId = appId
            appUser { id = oldToken.appUser.id }
            this.tokenHash = newTokenHash
            this.loginInstallId = oldToken.loginInstallId
            expiresAt = newExpiresAt
            revokedAt = null
            replacedBy = null
            createdAt = now
            updatedAt = now
        }
        refreshRepo.save(newTokenEntity)

        // 4. Revoke old token
        refreshRepo.revoke(oldToken.id, replacedBy = newTokenId)

        // 5. Sign new access token
        val appUserId = oldToken.appUser.id
        val accessToken = jwt.signAccess(appUserId.toString(), ctx.appId)

        // 6. Return
        return RefreshRes(
            accessToken = accessToken,
            refreshToken = rawNewToken,
            refreshExpiresAt = newExpiresAt,
            expiresIn = accessTtlSec,
        )
    }

    @Transactional
    fun logout(ctx: RequestContext, req: LogoutReq): LogoutRes {
        val appId = UUID.fromString(ctx.appId)

        // 1. Hash the provided refresh token
        val tokenHash = Hashing.sha256Base64Url(req.refreshToken!!)

        // 2. Find token (valid or not - we still revoke it)
        val token = refreshRepo.findValidByHash(appId, tokenHash)
        if (token != null) {
            // 3. Revoke refresh token
            refreshRepo.revoke(token.id)

            // 4. If device secret is linked, revoke it too
            token.deviceSecret?.let { ds ->
                deviceSecretRepo.revoke(ds.id)
            }
        }

        return LogoutRes(ok = true)
    }

    fun me(ctx: RequestContext): MeRes {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        return MeRes(userId, null)
    }
}
