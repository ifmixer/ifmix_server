package com.ifmix.api.core.modules.auth.service

import com.ifmix.api.core.entity.auth.AppRefreshToken
import com.ifmix.api.core.entity.auth.AuthDeviceSecret
import com.ifmix.api.core.entity.auth.AuthIdentity
import com.ifmix.api.core.infra.auth.AuthJwtService
import com.ifmix.api.core.infra.auth.Hashing
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.app.repo.AppConfigRevisionRepository
import com.ifmix.api.core.modules.auth.AuthLoggedInEvent
import com.ifmix.api.core.modules.auth.dto.DeleteAccountRes
import com.ifmix.api.core.modules.auth.dto.ExchangeReq
import com.ifmix.api.core.modules.auth.dto.ExchangeRes
import com.ifmix.api.core.modules.auth.dto.LoginRes
import com.ifmix.api.core.modules.auth.dto.LogoutReq
import com.ifmix.api.core.modules.auth.dto.LogoutRes
import com.ifmix.api.core.modules.auth.dto.MeRes
import com.ifmix.api.core.modules.auth.dto.ProviderLoginReq
import com.ifmix.api.core.modules.auth.ProviderVerifier
import com.ifmix.api.core.modules.auth.dto.RefreshReq
import com.ifmix.api.core.modules.auth.dto.RefreshRes
import com.ifmix.api.core.modules.auth.dto.UserDto
import com.ifmix.api.core.modules.auth.dto.WechatLoginReq
import com.ifmix.api.core.modules.auth.repo.AppRefreshTokenRepository
import com.ifmix.api.core.modules.auth.repo.AppUserRepository
import com.ifmix.api.core.modules.auth.repo.AuthDeviceSecretRepository
import com.ifmix.api.core.modules.auth.repo.AuthIdentityRepository
import com.ifmix.api.core.modules.auth.repo.AuthProviderIdentityRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
open class AuthService(
    private val appConfigRepo: AppConfigRevisionRepository,
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

    private fun tenantId(ctx: OperationContext): String =
        appConfigRepo.mustFindCurrentRevision(ctx.repoCtx, ctx.appId!!).authTenantId?.toString()
            ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)

    @Transactional
    fun loginWithIdToken(ctx: OperationContext, provider: String, req: ProviderLoginReq): LoginRes {
        return loginWithProvider(ctx, provider, req.idToken, req.deviceSecret)
    }

    @Transactional
    fun loginWithCode(ctx: OperationContext, provider: String, req: WechatLoginReq): LoginRes {
        return loginWithProvider(ctx, provider, req.code, req.deviceSecret)
    }

    @Transactional
    fun loginWithProvider(ctx: OperationContext, provider: String, credential: String, deviceSecret: String? = null): LoginRes {
        val rc = ctx.globalRepoCtx

        // 1. Resolve app config & tenant (app_config_revision is a tenant table)
        val config = appConfigRepo.mustFindCurrentRevision(ctx.repoCtx, ctx.appId!!)
        val tenantUUID = config.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val tenantId = tenantUUID.toString()

        // 2. Get verifier
        val verifier = verifiers[provider] ?: throw ApiError(
            ErrorCode.AUTH_PROVIDER_FAILED,
            "unsupported provider: $provider"
        )

        // 3. Verify credential
        val verified = verifier.verify(config, ctx.clientPlatform, credential)

        // 4. Find or create AuthIdentity
        val normalizedEmail = verified.email?.lowercase()
        val identity = if (normalizedEmail != null) {
            identityRepo.findByTenantAndEmail(rc, tenantId, normalizedEmail) ?: run {
                val newIdentity = AuthIdentity {
                    id = UuidV7.generate()
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
                identityRepo.save(rc, newIdentity)
            }
        } else {
            val existingProvider = providerIdentityRepo.findByProviderAndAccountId(
                rc, tenantId, provider, verified.accountId
            )
            if (existingProvider != null) {
                identityRepo.findById(rc, existingProvider.authIdentity.id)!!
            } else {
                val newIdentity = AuthIdentity {
                    id = UuidV7.generate()
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
                identityRepo.save(rc, newIdentity)
            }
        }

        // 5. Upsert AuthProviderIdentity
        providerIdentityRepo.upsert(
            ctx = rc,
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
            loginAppId = ctx.appId
        )

        // 6. Ensure AppUser
        val appUserId = appUserRepo.ensure(rc, ctx.appId!!, identity.id)

        // 7. Issue device secret
        val rawDeviceSecret = Hashing.randomTokenBase64Url()
        val deviceSecretHash = Hashing.sha256Base64Url(rawDeviceSecret)
        val now = Instant.now()
        val deviceSecretEntity = AuthDeviceSecret {
            id = UuidV7.generate()
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
        val savedDeviceSecret = deviceSecretRepo.save(rc, deviceSecretEntity)

        // 8. Issue refresh token
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        val refreshTokenEntity = AppRefreshToken {
            id = UuidV7.generate()
            this.appId = ctx.appId!!
            appUser { id = appUserId }
            this.deviceSecret { id = savedDeviceSecret.id }
            this.tokenHash = refreshTokenHash
            this.loginInstallId = ctx.installId
            expiresAt = refreshExpiresAt
            revokedAt = null
            replacedBy = null
            createdAt = now
            updatedAt = now
        }
        refreshRepo.save(rc, refreshTokenEntity)

        // 9. Sign access token
        val accessToken = jwt.signAccess(appUserId.toString(), ctx.appId!!.toString())

        // 10. Publish event
        events.publishEvent(
            AuthLoggedInEvent(
                appId = ctx.appId!!,
                authIdentityId = identity.id.toString(),
                appUserId = appUserId,
                installId = ctx.installId,
                clientIp = ctx.clientIp,
                clientPlatform = ctx.clientPlatform?.name,
                ctx = ctx,
            )
        )

        // 11. Return
        return LoginRes(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            deviceSecret = rawDeviceSecret,
            expiresIn = accessTtlSec,
            user = UserDto(id = appUserId, email = identity.email),
        )
    }

    @Transactional
    fun exchange(ctx: OperationContext, req: ExchangeReq): ExchangeRes {
        val rc = ctx.globalRepoCtx
        val appId = ctx.appId!!

        val secretHash = Hashing.sha256Base64Url(req.deviceSecret!!)

        val foundSecret = deviceSecretRepo.findValidByHash(rc, secretHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired device secret")

        deviceSecretRepo.touch(rc, foundSecret.id)

        val identityId = foundSecret.authIdentity.id
        val appUserId = appUserRepo.ensure(rc, appId, identityId)

        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val now = Instant.now()
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        val refreshTokenEntity = AppRefreshToken {
            id = UuidV7.generate()
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
        refreshRepo.save(rc, refreshTokenEntity)

        val accessToken = jwt.signAccess(appUserId.toString(), ctx.appId!!.toString())

        return ExchangeRes(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            expiresIn = accessTtlSec,
            user = UserDto(id = appUserId, email = identityRepo.findById(rc, identityId)?.email),
        )
    }

    @Transactional
    fun refresh(ctx: OperationContext, req: RefreshReq): RefreshRes {
        val rc = ctx.globalRepoCtx
        val appId = ctx.appId!!

        val tokenHash = Hashing.sha256Base64Url(req.refreshToken!!)

        val oldToken = refreshRepo.findValidByHash(rc, appId, tokenHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired refresh token")

        val rawNewToken = Hashing.randomTokenBase64Url()
        val newTokenHash = Hashing.sha256Base64Url(rawNewToken)
        val now = Instant.now()
        val newExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        val newTokenId = UuidV7.generate()

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
        refreshRepo.save(rc, newTokenEntity)

        refreshRepo.revoke(rc, oldToken.id, replacedBy = newTokenId)

        val appUserId = oldToken.appUser.id
        val accessToken = jwt.signAccess(appUserId.toString(), ctx.appId!!.toString())

        return RefreshRes(
            accessToken = accessToken,
            refreshToken = rawNewToken,
            refreshExpiresAt = newExpiresAt,
            expiresIn = accessTtlSec,
        )
    }

    @Transactional
    fun logout(ctx: OperationContext, req: LogoutReq): LogoutRes {
        val rc = ctx.globalRepoCtx
        val appId = ctx.appId!!

        val tokenHash = Hashing.sha256Base64Url(req.refreshToken!!)

        val token = refreshRepo.findValidByHash(rc, appId, tokenHash)
        if (token != null) {
            refreshRepo.revoke(rc, token.id)

            token.deviceSecret?.let { ds ->
                deviceSecretRepo.revoke(rc, ds.id)
            }
        }

        return LogoutRes(ok = true)
    }

    fun me(ctx: OperationContext): MeRes {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        return MeRes(userId, null)
    }

    @Transactional
    open fun anonymousLogin(ctx: OperationContext): LoginRes {
        val installId = ctx.installId ?: throw ApiError(
            ErrorCode.INVALID_REQUEST,
            "x-install-id required for anonymous login"
        )
        return loginWithProvider(ctx, "anonymous", "anon_$installId", null)
    }

    fun requestAccountDeletion(ctx: OperationContext): DeleteAccountRes {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        val scheduledAt = Instant.now().plusSeconds(30L * 24 * 3600).toEpochMilli()
        return DeleteAccountRes(accepted = true, scheduledAt = scheduledAt)
    }
}
