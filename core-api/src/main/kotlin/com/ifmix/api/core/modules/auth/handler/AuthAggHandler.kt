package com.ifmix.api.core.modules.auth.handler

import com.ifmix.api.core.entity.auth.AppUserRefreshToken
import com.ifmix.api.core.entity.auth.AppUserToIdpIdentityRelation
import com.ifmix.api.core.entity.auth.IdpIdentity
import com.ifmix.api.core.infra.auth.AuthJwtService
import com.ifmix.api.core.infra.auth.Hashing
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.modules.auth.AuthLoggedInEvent
import com.ifmix.api.core.modules.auth.ProviderVerifier
import com.ifmix.api.core.modules.auth.repo.AppToIdpRelationRepository
import com.ifmix.api.core.modules.auth.repo.AppUserRefreshTokenRepository
import com.ifmix.api.core.modules.auth.repo.AppUserToIdpIdentityRelationRepository
import com.ifmix.api.core.modules.auth.repo.IdpIdentityRepository
import com.ifmix.api.core.modules.auth.repo.IdpRepository
import com.ifmix.api.core.modules.user.repo.AppUserRepository
import com.ifmix.api.core.dto.payment.SubscriptionState
import com.ifmix.api.core.entity.common.Tiers
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

// ============================================================================
// DTOs
// ============================================================================

data class UserDto(val id: UUID, val email: String?)

data class LoginReq(
    val idpId: UUID,
    val credential: String,
)

data class LoginRes(
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
    val tier: Int = Tiers.FREE,
    val active: Boolean = false,
    val state: SubscriptionState = SubscriptionState.EXPIRED,
    val expiresAt: Long? = null,
    val isAnonymous: Boolean = false,
    val deletionScheduledAt: Long? = null,
)

data class DeleteAccountRes(
    val accepted: Boolean = true,
    val scheduledAt: Long,
)

@Component
class AuthAggHandler(
    private val verifiers: Map<String, ProviderVerifier>,
    private val jwt: AuthJwtService,
    private val idpRepo: IdpRepository,
    private val idpIdentityRepo: IdpIdentityRepository,
    private val appToIdpRepo: AppToIdpRelationRepository,
    private val appUserToIdpIdentityRepo: AppUserToIdpIdentityRelationRepository,
    private val refreshTokenRepo: AppUserRefreshTokenRepository,
    private val appUserRepo: AppUserRepository,
    private val events: ApplicationEventPublisher,
    @Value("\${app.auth.access-ttl-sec:900}")
    private val accessTtlSec: Long,
) {
    companion object {
        private const val REFRESH_TTL_DAYS = 30L
    }

    fun me(mc: ModuleCtx): MeRes {
        val userId = mc.op.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        val appId = mc.appId!!
        val appUser = appUserRepo.findById(mc, appId, userId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "user not found")
        // 查该用户绑定的第一个 idpIdentity 拿 email
        val email = findPrimaryEmail(mc, appId, userId)
        return MeRes(userId, email)
    }

    fun login(mc: ModuleCtx, req: LoginReq): LoginRes {
        val appId = mc.appId!!

        // 1. 验证 app 是否启用了该 IDP
        appToIdpRepo.findByAppAndIdp(mc, appId, req.idpId)
            ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "IDP not enabled for this app")

        // 2. 加载 IDP 配置，验证 credential
        val idp = idpRepo.findById(mc, req.idpId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "IDP not found")
        val providerKey = providerKeyForType(idp.providerType)
        val verifier = verifiers[providerKey]
            ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "unsupported provider type: ${idp.providerType}")
        val verified = verifier.verifyWithIdpConfig(idp, mc.op.clientPlatform, req.credential)

        // 3. 找/建 IdpIdentity（全局）
        val idpIdentity = idpIdentityRepo.findByIdpAndIdentityId(mc, req.idpId, verified.accountId)
            ?: createIdpIdentity(mc, req.idpId, verified)

        // 4. 通过 relation 查该 idpIdentity 在此 app 下绑了哪个 appUser
        val relation = appUserToIdpIdentityRepo.findByAppAndIdpIdentity(mc, appId, idpIdentity.id)
        val appUserId: UUID = if (relation != null) {
            relation.appUserId
        } else {
            // 创建 AppUser + 绑定 relation
            val newUserId = appUserRepo.createAppUser(mc, appId)
            createRelation(mc, appId, newUserId, req.idpId, idpIdentity.id)
            newUserId
        }

        // 5. 更新 idpIdentity 登录信息
        updateIdpIdentityLogin(mc, idpIdentity.id, verified, mc.op.clientIp, mc.op.installId)

        // 6. 签发 refresh token + access token
        val now = Instant.now()
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        refreshTokenRepo.save(mc, AppUserRefreshToken {
            this.id = UuidV7.generate()
            this.appId = appId
            this.appUserId = appUserId
            this.tokenHash = refreshTokenHash
            this.loginInstallId = mc.op.installId
            this.expiresAt = refreshExpiresAt
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })

        val accessToken = jwt.signAccess(appUserId.toString(), mc.op.mustGetInstallId().toString(), appId.toString())

        // 7. Publish event
        events.publishEvent(AuthLoggedInEvent(
            appId = appId,
            authIdentityId = idpIdentity.id.toString(),
            appUserId = appUserId,
            installId = mc.op.installId,
            clientIp = mc.op.clientIp,
            clientPlatform = mc.op.clientPlatform?.name,
            ctx = mc.op,
        ))

        return LoginRes(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            expiresIn = accessTtlSec,
            user = UserDto(id = appUserId, email = verified.email),
        )
    }

    fun refresh(mc: ModuleCtx, req: RefreshReq): RefreshRes {
        val appId = mc.appId!!
        val tokenHash = Hashing.sha256Base64Url(req.refreshToken)
        val oldToken = refreshTokenRepo.findValidByHash(mc, appId, tokenHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or expired refresh token")

        val now = Instant.now()
        val rawNewToken = Hashing.randomTokenBase64Url()
        val newTokenHash = Hashing.sha256Base64Url(rawNewToken)
        val newExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        val newTokenId = UuidV7.generate()

        refreshTokenRepo.save(mc, AppUserRefreshToken {
            this.id = newTokenId
            this.appId = appId
            this.appUserId = oldToken.appUserId
            this.tokenHash = newTokenHash
            this.loginInstallId = oldToken.loginInstallId
            this.expiresAt = newExpiresAt
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })
        refreshTokenRepo.revoke(mc, oldToken.id, replacedBy = newTokenId)

        val accessToken = jwt.signAccess(oldToken.appUserId.toString(), oldToken.loginInstallId?.toString() ?: "", appId.toString())
        return RefreshRes(
            accessToken = accessToken,
            refreshToken = rawNewToken,
            refreshExpiresAt = newExpiresAt,
            expiresIn = accessTtlSec,
        )
    }

    fun logout(mc: ModuleCtx, req: LogoutReq): LogoutRes {
        val appId = mc.appId!!
        val tokenHash = Hashing.sha256Base64Url(req.refreshToken)
        val token = refreshTokenRepo.findValidByHash(mc, appId, tokenHash)
        if (token != null) {
            refreshTokenRepo.revoke(mc, token.id)
        }
        return LogoutRes(ok = true)
    }

    fun requestAccountDeletion(mc: ModuleCtx): DeleteAccountRes {
        mc.op.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        val scheduledAt = Instant.now().plusSeconds(30L * 24 * 3600).toEpochMilli()
        return DeleteAccountRes(accepted = true, scheduledAt = scheduledAt)
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private fun providerKeyForType(providerType: Int): String = when (providerType) {
        10 -> "apple"
        20 -> "google"
        else -> throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "unknown provider type: $providerType")
    }

    private fun createIdpIdentity(mc: ModuleCtx, idpId: UUID, verified: ProviderVerifier.VerifiedResult): IdpIdentity {
        val now = Instant.now()
        val entity = IdpIdentity {
            this.id = UuidV7.generate()
            this.idpId = idpId
            this.idpIdentityId = verified.accountId
            this.email = verified.email
            this.emailVerified = verified.emailVerified
            this.phone = verified.phone
            this.profile = verified.userMetadata
            this.loginIp = mc.op.clientIp
            this.loginInstallId = mc.op.installId
            this.createdAt = now
            this.updatedAt = now
        }
        idpIdentityRepo.save(mc, entity)
        return entity
    }

    private fun createRelation(mc: ModuleCtx, appId: UUID, appUserId: UUID, idpId: UUID, idpIdentityId: UUID) {
        val now = Instant.now()
        appUserToIdpIdentityRepo.save(mc, AppUserToIdpIdentityRelation {
            this.id = UuidV7.generate()
            this.appId = appId
            this.appUserId = appUserId
            this.idpId = idpId
            this.idpIdentityId = idpIdentityId
            this.createdAt = now
            this.updatedAt = now
        })
    }

    private fun updateIdpIdentityLogin(mc: ModuleCtx, id: UUID, verified: ProviderVerifier.VerifiedResult, ip: String?, installId: UUID?) {
        val now = Instant.now()
        val entity = IdpIdentity {
            this.id = id
            this.idpId = verified.accountId.let { /* keep existing */ mc.let { ctx -> idpIdentityRepo.findById(ctx, id)!!.idpId } }
            this.idpIdentityId = verified.accountId
            this.email = verified.email
            this.emailVerified = verified.emailVerified
            this.phone = verified.phone
            this.profile = verified.userMetadata
            this.loginIp = ip
            this.loginInstallId = installId
            this.updatedAt = now
            this.createdAt = now // won't change on upsert
        }
        idpIdentityRepo.save(mc, entity)
    }

    private fun findPrimaryEmail(mc: ModuleCtx, appId: UUID, appUserId: UUID): String? {
        // ponytail: 简单实现，后续可优化为专门查询
        val relation = appUserToIdpIdentityRepo.findFirstByAppUser(mc, appId, appUserId) ?: return null
        val idpIdentity = idpIdentityRepo.findById(mc, relation.idpIdentityId) ?: return null
        return idpIdentity.email
    }
}
