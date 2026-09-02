package com.ifmix.core.api.modules.auth.handler

import com.ifmix.core.api.entity.auth.RefreshToken
import com.ifmix.core.api.entity.auth.IdpIdentityBinding
import com.ifmix.core.api.entity.auth.IdpIdentity
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.Hashing
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.auth.AuthLoggedInEvent
import com.ifmix.core.api.modules.auth.ProviderVerifier
import com.ifmix.core.api.modules.auth.repo.AppToIdpRelationRepository
import com.ifmix.core.api.modules.auth.repo.RefreshTokenRepository
import com.ifmix.core.api.modules.auth.repo.IdpIdentityBindingRepository
import com.ifmix.core.api.modules.auth.repo.IdpIdentityRepository
import com.ifmix.core.api.modules.auth.repo.IdpRepository
import com.ifmix.core.api.modules.customer.repo.CustomerRepository
import com.ifmix.core.api.modules.customer.handler.CustomerMergeHandler
import com.ifmix.core.api.dto.payment.SubscriptionState
import com.ifmix.core.api.entity.common.Tiers
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

data class CreateAnonymousRes(
    val customerId: UUID,
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val expiresIn: Long,
)

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
    private val appUserToIdpIdentityRepo: IdpIdentityBindingRepository,
    private val refreshTokenRepo: RefreshTokenRepository,
    private val customerRepo: CustomerRepository,
    private val mergeHandler: CustomerMergeHandler,
    private val events: ApplicationEventPublisher,
    @Value("\${app.auth.access-ttl-sec:900}")
    private val accessTtlSec: Long,
) {
    companion object {
        private const val REFRESH_TTL_DAYS = 90L

        /** 登录判定动作（R1：合并方向由此单一入口决定，绝不反向）。 */
        sealed interface LoginAction {
            /** ①relation 不存在：cur 转正或（cur==null 时）新建。 */
            data object PromoteOrCreate : LoginAction
            /** ②relation 存在且 existing == cur：重复登录，无操作。 */
            data class NoOp(val owner: UUID) : LoginAction
            /** ③cur 匿名且 existing != cur：合并 from(cur) → to(existing)。 */
            data class Merge(val from: UUID, val to: UUID) : LoginAction
            /** ④cur 非匿名且 existing != cur：冲突。 */
            data object Conflict : LoginAction
        }

        /**
         * 纯判定：给定当前主体 cur、cur 是否匿名、该 idpIdentity 已绑定的 existing，返回应执行的动作。
         * 无副作用、无 DB，便于单测覆盖判定表四分支（R1）。
         */
        fun decideLoginAction(cur: UUID?, curAnonymous: Boolean, existing: UUID?): LoginAction = when {
            existing == null -> LoginAction.PromoteOrCreate
            existing == cur -> LoginAction.NoOp(existing)
            cur != null && curAnonymous -> LoginAction.Merge(from = cur, to = existing)
            else -> LoginAction.Conflict
        }
    }

    fun me(mc: ModuleCtx): MeRes {
        val userId = mc.op.customerId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        val appId = mc.appId!!
        val appUser = customerRepo.findById(mc, appId, userId)
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
        val idpIdentity = idpIdentityRepo.findByIdpAndSubject(mc, req.idpId, verified.accountId)
            ?: createIdpIdentity(mc, req.idpId, idp.providerType, verified)

        // 4. 通过 relation 查该 idpIdentity 在此 app 下绑了哪个 customer（existing）
        //    并按判定表决定：转正 / 无操作 / 合并 / 冲突。方向硬编码 匿名 cur → existing（R1）。
        //    cur = 当前 token 主体（可能为匿名 customer，也可能为 null——旧调用无匿名 token）。
        val cur: UUID? = mc.op.customerId
        val curAnonymous: Boolean = mc.op.anonymous
        val relation = appUserToIdpIdentityRepo.findByAppAndIdpIdentity(mc, appId, idpIdentity.id)
        val existing: UUID? = relation?.actorId

        val ownerId: UUID = when (val action = decideLoginAction(cur, curAnonymous, existing)) {
            // 判定表①：relation 不存在 → cur 转正 + 建 relation（零迁移）；cur==null 才新建 customer（兼容旧调用）
            is LoginAction.PromoteOrCreate -> {
                val target = cur ?: customerRepo.createCustomer(mc, appId)
                if (cur != null) customerRepo.promote(mc, appId, cur)
                createRelation(mc, appId, target, req.idpId, idpIdentity.id)
                target
            }
            // 判定表②：relation 存在且 existing == cur → 无操作（重复登录）
            is LoginAction.NoOp -> action.owner
            // 判定表③：relation 存在、cur 匿名、existing != cur → 合并 cur → existing
            is LoginAction.Merge -> {
                mergeHandler.merge(mc, appId, curId = action.from, existingId = action.to)
                // 吊销 cur 的全部 refresh token（其数据已迁往 existing）
                refreshTokenRepo.revokeAllByActor(mc, appId, action.from, AuthJwtService.ACTOR_CUSTOMER)
                action.to
            }
            // 判定表④：relation 存在、cur 非匿名、existing != cur → 冲突报错
            is LoginAction.Conflict -> throw ApiError(
                ErrorCode.FORBIDDEN,
                "该账号已在其他设备使用，请用原账号登录",
            )
        }

        // 5. 更新 idpIdentity 登录信息
        updateIdpIdentityLogin(mc, idpIdentity.id, verified, mc.op.clientIp)

        // 6. 为 ownerId（existing / 转正后的 cur）签发 refresh token + access token
        val now = Instant.now()
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        refreshTokenRepo.save(mc, RefreshToken {
            this.id = UuidV7.generate()
            this.appId = appId
            this.actorId = ownerId
            this.actorType = AuthJwtService.ACTOR_CUSTOMER
            this.tokenHash = refreshTokenHash
            this.expiresAt = refreshExpiresAt
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })

        // 登录主体已转正/合并到 existing（非匿名）。token: sub=ownerId, act=customer, ano=false
        val accessToken = jwt.signAccess(ownerId.toString(), AuthJwtService.ACTOR_CUSTOMER, appId.toString(), anonymous = false)

        // 7. Publish event
        events.publishEvent(AuthLoggedInEvent(
            appId = appId,
            authIdentityId = idpIdentity.id.toString(),
            customerId = ownerId,
            clientIp = mc.op.clientIp,
            clientPlatform = mc.op.clientPlatform?.name,
            ctx = mc.op,
        ))

        return LoginRes(
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            expiresIn = accessTtlSec,
            user = UserDto(id = ownerId, email = verified.email),
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

        refreshTokenRepo.save(mc, RefreshToken {
            this.id = newTokenId
            this.appId = appId
            this.actorId = oldToken.actorId
            this.actorType = oldToken.actorType
            this.tokenHash = newTokenHash
            this.expiresAt = newExpiresAt
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })
        refreshTokenRepo.revoke(mc, oldToken.id, replacedBy = newTokenId)

        val accessToken = jwt.signAccess(oldToken.actorId.toString(), oldToken.actorType, appId.toString())
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

    /**
     * 创建匿名 Customer 并签发 access + refresh token。无需鉴权。
     * 既是首装入口，也是登出后惰性重建入口。token: sub=customerId, act="customer", ano=true。
     */
    fun createAnonymousCustomer(mc: ModuleCtx): CreateAnonymousRes {
        val appId = mc.appId!!
        val customerId = customerRepo.createCustomer(mc, appId) // anonymous=true

        val now = Instant.now()
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val refreshExpiresAt = now.plusSeconds(REFRESH_TTL_DAYS * 86400)
        refreshTokenRepo.save(mc, RefreshToken {
            this.id = UuidV7.generate()
            this.appId = appId
            this.actorId = customerId
            this.actorType = AuthJwtService.ACTOR_CUSTOMER
            this.tokenHash = refreshTokenHash
            this.expiresAt = refreshExpiresAt
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })

        val accessToken = jwt.signAccess(
            customerId.toString(), AuthJwtService.ACTOR_CUSTOMER, appId.toString(), anonymous = true,
        )
        return CreateAnonymousRes(
            customerId = customerId,
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            expiresIn = accessTtlSec,
        )
    }

    fun requestAccountDeletion(mc: ModuleCtx): DeleteAccountRes {
        mc.op.customerId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
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

    private fun createIdpIdentity(mc: ModuleCtx, idpId: UUID, providerType: Int, verified: ProviderVerifier.VerifiedResult): IdpIdentity {
        val now = Instant.now()
        val entity = IdpIdentity {
            this.id = UuidV7.generate()
            this.providerType = providerType
            this.idpId = idpId
            this.providerSubjectId = verified.accountId
            this.email = verified.email
            this.emailVerified = verified.emailVerified
            this.phoneCallingCode = null
            this.phoneCountryCode = null
            this.phoneNationalNumber = null
            this.phoneVerified = false
            this.password = null
            this.profile = verified.userMetadata
            this.loginIp = mc.op.clientIp
            this.createdAt = now
            this.updatedAt = now
        }
        idpIdentityRepo.save(mc, entity)
        return entity
    }

    private fun createRelation(mc: ModuleCtx, appId: UUID, actorId: UUID, idpId: UUID, idpIdentityId: UUID) {
        val now = Instant.now()
        appUserToIdpIdentityRepo.save(mc, IdpIdentityBinding {
            this.id = UuidV7.generate()
            this.appId = appId
            this.actorType = AuthJwtService.ACTOR_CUSTOMER
            this.actorId = actorId
            this.idpId = idpId
            this.idpIdentityId = idpIdentityId
            this.createdAt = now
            this.updatedAt = now
        })
    }

    private fun updateIdpIdentityLogin(mc: ModuleCtx, id: UUID, verified: ProviderVerifier.VerifiedResult, ip: String?) {
        val now = Instant.now()
        val entity = IdpIdentity {
            this.id = id
            this.providerSubjectId = verified.accountId
            this.email = verified.email
            this.emailVerified = verified.emailVerified
            this.profile = verified.userMetadata
            this.loginIp = ip
            this.updatedAt = now
        }
        idpIdentityRepo.save(mc, entity)
    }

    private fun findPrimaryEmail(mc: ModuleCtx, appId: UUID, customerId: UUID): String? {
        // ponytail: 简单实现，后续可优化为专门查询
        val relation = appUserToIdpIdentityRepo.findFirstByCustomer(mc, appId, customerId) ?: return null
        val idpIdentity = idpIdentityRepo.findById(mc, relation.idpIdentityId) ?: return null
        return idpIdentity.email
    }
}
