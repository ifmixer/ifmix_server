package com.ifmix.api.core.service.auth

import com.ifmix.api.core.entity.auth.AppRefreshToken
import com.ifmix.api.core.entity.auth.AuthDeviceSecret
import com.ifmix.api.core.entity.auth.AuthIdentity
import com.ifmix.api.core.infra.auth.AuthJwtService
import com.ifmix.api.core.infra.auth.Hashing
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.repository.auth.AppRefreshTokenRepository
import com.ifmix.api.core.repository.auth.AppUserRepository
import com.ifmix.api.core.repository.auth.AuthDeviceSecretRepository
import com.ifmix.api.core.repository.auth.AuthIdentityRepository
import com.ifmix.api.core.repository.auth.AuthProviderIdentityRepository
import com.ifmix.api.core.repository.appconfig.AppConfigRevisionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Transactional
import com.ifmix.api.core.infra.db.UuidV7
import java.time.Instant
import java.util.UUID

/**
 * 认证业务编排（组合非继承）。
 *
 * 提供完整的 provider 登录、设备密钥交换、refresh 轮转、logout 流程。
 */
@org.springframework.stereotype.Service
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
    @org.springframework.beans.factory.annotation.Value("\${app.auth.access-ttl-sec:900}")
    private val accessTtlSec: Long,
) {

    companion object {
        private const val REFRESH_TTL_DAYS = 30L
        private const val DEVICE_SECRET_TTL_DAYS = 365L
    }

    private fun tenantId(ctx: OperationContext): String =
        appConfigRepo.mustFindCurrentRevision(ctx).authTenantId?.toString()
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
        // 1. Resolve app config & tenant
        val config = appConfigRepo.mustFindCurrentRevision(ctx)
        val tenantUUID = config.authTenantId ?: throw ApiError(ErrorCode.APP_CONFIG_MISSING)
        val tenantId = tenantUUID.toString()

        // 2. Get verifier
        val verifier = verifiers[provider] ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "unsupported provider: $provider")

        // 3. Verify credential
        val verified = verifier.verify(config, ctx.clientPlatform, credential)

        // 4. Find or create AuthIdentity
        val normalizedEmail = verified.email?.lowercase()
        val identity = if (normalizedEmail != null) {
            identityRepo.findByTenantAndEmail(ctx, tenantId, normalizedEmail) ?: run {
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
                identityRepo.save(ctx, newIdentity)
            }
        } else {
            // No email — look up existing provider identity first
            val existingProvider = providerIdentityRepo.findByProviderAndAccountId(
                ctx, tenantId, provider, verified.accountId
            )
            if (existingProvider != null) {
                identityRepo.findById(ctx, existingProvider.authIdentity.id)!!
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
                identityRepo.save(ctx, newIdentity)
            }
        }

        // 5. Upsert AuthProviderIdentity
        providerIdentityRepo.upsert(
            ctx = ctx,
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
        val appUserId = appUserRepo.ensure(ctx, ctx.appId!!, identity.id)

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
        val savedDeviceSecret = deviceSecretRepo.save(ctx, deviceSecretEntity)

        // 8. Issue refresh token
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        val refreshTokenEntity = AppRefreshToken {
            id = UuidV7.generate()
            this.appId = ctx.appId!!
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
        refreshRepo.save(ctx, refreshTokenEntity)

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
        val appId = ctx.appId!!

        // 1. Hash the provided device secret
        val secretHash = Hashing.sha256Base64Url(req.deviceSecret!!)

        // 2. Find valid device secret
        val foundSecret = deviceSecretRepo.findValidByHash(ctx, secretHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired device secret")

        // 3. Touch device secret (update lastUsedAt)
        deviceSecretRepo.touch(ctx, foundSecret.id)

        // 4. Find AppUser via identity
        val identityId = foundSecret.authIdentity.id
        val appUserId = appUserRepo.ensure(ctx, appId, identityId)

        // 5. Issue new refresh token
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
        refreshRepo.save(ctx, refreshTokenEntity)

        // 6. Sign access token
        val accessToken = jwt.signAccess(appUserId.toString(), ctx.appId!!.toString())

        // 7. Return
        return ExchangeRes(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            expiresIn = accessTtlSec,
            user = UserDto(id = appUserId, email = identityRepo.findById(ctx, identityId)?.email),
        )
    }

    @Transactional
    fun refresh(ctx: OperationContext, req: RefreshReq): RefreshRes {
        val appId = ctx.appId!!

        // 1. Hash the provided refresh token
        val tokenHash = Hashing.sha256Base64Url(req.refreshToken!!)

        // 2. Find valid token
        val oldToken = refreshRepo.findValidByHash(ctx, appId, tokenHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired refresh token")

        // 3. Generate new refresh token
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
        refreshRepo.save(ctx, newTokenEntity)

        // 4. Revoke old token
        refreshRepo.revoke(ctx, oldToken.id, replacedBy = newTokenId)

        // 5. Sign new access token
        val appUserId = oldToken.appUser.id
        val accessToken = jwt.signAccess(appUserId.toString(), ctx.appId!!.toString())

        // 6. Return
        return RefreshRes(
            accessToken = accessToken,
            refreshToken = rawNewToken,
            refreshExpiresAt = newExpiresAt,
            expiresIn = accessTtlSec,
        )
    }

    @Transactional
    fun logout(ctx: OperationContext, req: LogoutReq): LogoutRes {
        val appId = ctx.appId!!

        // 1. Hash the provided refresh token
        val tokenHash = Hashing.sha256Base64Url(req.refreshToken!!)

        // 2. Find token (valid or not - we still revoke it)
        val token = refreshRepo.findValidByHash(ctx, appId, tokenHash)
        if (token != null) {
            // 3. Revoke refresh token
            refreshRepo.revoke(ctx, token.id)

            // 4. If device secret is linked, revoke it too
            token.deviceSecret?.let { ds ->
                deviceSecretRepo.revoke(ctx, ds.id)
            }
        }

        return LogoutRes(ok = true)
    }

    fun me(ctx: OperationContext): MeRes {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        return MeRes(userId, null)
    }

    /**
     * 匿名 token 签发。
     * 复用 [loginWithProvider] 逻辑，用 installId 作为匿名凭据。
     */
    @Transactional
    open fun anonymousLogin(ctx: OperationContext): LoginRes {
        val installId = ctx.installId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-install-id required for anonymous login")
        return loginWithProvider(ctx, "anonymous", "anon_$installId", null)
    }

    /**
     * 请求删除账号（stub）。符合 App Store 审核要求。
     */
    fun requestAccountDeletion(ctx: OperationContext): DeleteAccountRes {
        val userId = ctx.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        // TODO: 存储删除请求到数据库
        val scheduledAt = Instant.now().plusSeconds(30L * 24 * 3600).toEpochMilli()
        return DeleteAccountRes(accepted = true, scheduledAt = scheduledAt)
    }
}
