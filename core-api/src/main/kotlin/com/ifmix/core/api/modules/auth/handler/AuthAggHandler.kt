package com.ifmix.core.api.modules.auth.handler

import com.ifmix.core.api.entity.auth.RefreshToken
import com.ifmix.core.api.entity.auth.AuthIdentityIdpRelation
import com.ifmix.core.api.entity.auth.IdpIdentity
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.Hashing
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.auth.AuthLoggedInEvent
import com.ifmix.core.api.modules.auth.ProviderVerifier
import com.ifmix.core.api.modules.auth.repo.ProjectToIdpRelationRepository
import com.ifmix.core.api.modules.auth.repo.RefreshTokenRepository
import com.ifmix.core.api.modules.auth.repo.AuthIdentityRepository
import com.ifmix.core.api.modules.auth.repo.AuthIdentityIdpRelationRepository
import com.ifmix.core.api.modules.auth.repo.IdpIdentityRepository
import com.ifmix.core.api.modules.auth.repo.IdpRepository
import com.ifmix.core.api.modules.customer.repo.CustomerRepository
import com.ifmix.core.api.modules.customer.handler.CustomerMergeHandler
import com.ifmix.core.api.dto.payment.SubscriptionState
import com.ifmix.core.api.entity.common.Tiers
import com.ifmix.core.api.entity.common.IdpType
import com.ifmix.core.api.entity.common.IdpTypes
import com.ifmix.core.api.entity.customer.DeletionReasons
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
    val refreshExpiresAt: Instant?,
    val expiresIn: Long,
    val user: UserDto,
)

data class RefreshReq(val refreshToken: String)
data class RefreshRes(
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant?,
    val expiresIn: Long,
)

data class LogoutReq(val refreshToken: String)
data class LogoutRes(val ok: Boolean)

data class CreateAnonymousRes(
    val customerId: UUID,
    val accessToken: String,
    val refreshToken: String,
    val refreshExpiresAt: Instant?,
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
    private val projectToIdpRepo: ProjectToIdpRelationRepository,
    private val authIdentityRepo: AuthIdentityRepository,
    private val relationRepo: AuthIdentityIdpRelationRepository,
    private val refreshTokenRepo: RefreshTokenRepository,
    private val customerRepo: CustomerRepository,
    private val mergeHandler: CustomerMergeHandler,
    private val installFacade: com.ifmix.core.api.modules.install.InstallFacade,
    private val events: ApplicationEventPublisher,
    @Value("\${app.auth.access-ttl-sec:900}")
    private val accessTtlSec: Long,
) {
    companion object {
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
        val userId = mc.action.actorId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        val projectId = mc.projectId!!
        val appUser = customerRepo.findById(mc, projectId, userId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "user not found")
        // 查该用户绑定的第一个 idpIdentity 拿 email
        val email = findPrimaryEmail(mc, projectId, userId)
        return MeRes(userId, email)
    }

    fun login(mc: ModuleCtx, req: LoginReq): LoginRes {
        val projectId = mc.projectId!!

        // 1. 验证 app 是否启用了该 IDP
        projectToIdpRepo.findByAppAndIdp(mc, projectId, req.idpId)
            ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "IDP not enabled for this app")

        // 2. 加载 IDP 配置，验证 credential
        val idp = idpRepo.findById(mc, req.idpId)
            ?: throw ApiError(ErrorCode.NOT_FOUND, "IDP not found")
        val providerKey = providerKeyForType(idp.idpType)
        val verifier = verifiers[providerKey]
            ?: throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "unsupported provider type: ${idp.idpType}")
        val verified = verifier.verifyWithIdpConfig(idp, mc.action.clientPlatform, req.credential)

        // 3. 找/建 IdpIdentity（全局）
        val idpIdentity = idpIdentityRepo.findByIdpAndSubject(mc, req.idpId, verified.accountId)
            ?: createIdpIdentity(mc, req.idpId, idp.idpType, verified)

        // 4. 走关系表 + auth_identity 判定该 idpIdentity 在此 app 下对应哪个 customer（existing）。
        //    idpIdentity(全局) → relation(app级) → auth_identity → customer.authIdentityId。
        //    cur = 当前 token 主体（可能为匿名 customer，也可能为 null——旧调用无匿名 token）。
        val cur: UUID? = mc.action.actorId
        val curAnonymous: Boolean = mc.action.anonymous
        val relation = relationRepo.findByAppAndIdpIdentity(mc, projectId, idpIdentity.id)
        val existing: UUID? = relation?.let { customerRepo.findByAuthIdentity(mc, projectId, it.authIdentityId) }

        val ownerId: UUID = when (val action = decideLoginAction(cur, curAnonymous, existing)) {
            // 判定表①：该身份此 app 下无账号 → cur 转正 + 建账号/关系（零迁移）；cur==null 才新建 customer
            is LoginAction.PromoteOrCreate -> {
                val target = cur ?: customerRepo.createCustomer(mc, projectId)
                val authId = authIdentityRepo.createAccount(mc, projectId)
                createRelation(mc, projectId, authId, idpIdentity.id)
                customerRepo.setAuthIdentity(mc, projectId, target, authId)
                if (cur != null) customerRepo.promote(mc, projectId, cur)
                target
            }
            // 判定表②：relation 存在且 existing == cur → 无操作（重复登录）
            is LoginAction.NoOp -> action.owner
            // 判定表③：relation 存在、cur 匿名、existing != cur → 合并 cur → existing
            is LoginAction.Merge -> {
                mergeHandler.merge(mc, projectId, curId = action.from, existingId = action.to)
                // 吊销 cur 的全部 refresh token（其数据已迁往 existing）
                refreshTokenRepo.revokeAllByActor(mc, projectId, action.from, AuthJwtService.ACTOR_CUSTOMER)
                action.to
            }
            // 判定表④：relation 存在、cur 非匿名、existing != cur → 冲突报错
            is LoginAction.Conflict -> throw ApiError(
                ErrorCode.FORBIDDEN,
                "该账号已在其他设备使用，请用原账号登录",
            )
        }

        // 5. 更新 idpIdentity 登录信息
        updateIdpIdentityLogin(mc, idpIdentity.id, verified, mc.action.clientIp)

        // 6. 为 ownerId（existing / 转正后的 cur）签发 refresh token + access token
        val now = Instant.now()
        val refreshTokenId = UuidV7.generate()
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val refreshExpiresAt: Instant? = null
        refreshTokenRepo.save(mc, RefreshToken {
            this.id = refreshTokenId
            this.projectId = projectId
            this.actorId = ownerId
            this.actorType = AuthJwtService.ACTOR_CUSTOMER
            this.tokenHash = refreshTokenHash
            this.expiresAt = refreshExpiresAt
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })

        // 登录主体已转正/合并到 existing（非匿名）。token: sub=ownerId, act=customer, ano=false, sid=refreshTokenId, iid=installId
        // login 必须有可信 iid（两类上下文之一）；签发含 iid 的 customer token 并 bind 最终 owner。
        val tokenIid = mc.action.mustGetLoginInstallId()
        val accessToken = jwt.signAccess(
            ownerId.toString(), AuthJwtService.ACTOR_CUSTOMER, projectId.toString(),
            sessionId = refreshTokenId.toString(), anonymous = false,
            installId = tokenIid.toString(),
        )
        // Merge 分支 ownerId 已是合并后的 existing（action.to）→ bind 指向 existing，绝不反向
        installFacade.bind(mc.action, tokenIid, ownerId)

        // 7. Publish event
        events.publishEvent(AuthLoggedInEvent(
            projectId = projectId,
            authIdentityId = idpIdentity.id.toString(),
            customerId = ownerId,
            clientIp = mc.action.clientIp,
            clientPlatform = mc.action.clientPlatform?.name,
            ctx = mc.action,
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
        val projectId = mc.projectId!!
        // 携带有效可信 iid 即可（customerToken 或 installToken 都行；fetcher 已校验，handler 作为信任边界再取一次）。
        val tokenIid = mc.action.mustGetTokenInstallId()
        val tokenHash = Hashing.sha256Base64Url(req.refreshToken)
        val oldToken = refreshTokenRepo.findValidByHash(mc, projectId, tokenHash)
            ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid or revoked refresh token")

        val now = Instant.now()
        val rawNewToken = Hashing.randomTokenBase64Url()
        val newTokenHash = Hashing.sha256Base64Url(rawNewToken)
        val newExpiresAt: Instant? = null
        val newTokenId = UuidV7.generate()

        refreshTokenRepo.save(mc, RefreshToken {
            this.id = newTokenId
            this.projectId = projectId
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

        val anonymous = oldToken.actorType == AuthJwtService.ACTOR_CUSTOMER &&
            customerRepo.findById(mc, projectId, oldToken.actorId)?.anonymous == true
        val accessToken = jwt.signAccess(
            oldToken.actorId.toString(), oldToken.actorType, projectId.toString(),
            sessionId = newTokenId.toString(),
            anonymous = anonymous,
            installId = tokenIid.toString(),
        )
        // refresh 续期 token 并保留 iid/anonymous。若 refresh token 属于某 customer actor，
        // 则检查该 actor 与 iid 的关系：未绑定则补绑（bind 幂等——已绑定则 NoOp，软删则复活）。
        // 这修复了 legacy/漏绑场景，使 refresh 也能收敛到「一 install 一 customer」不变量。
        if (oldToken.actorType == AuthJwtService.ACTOR_CUSTOMER) {
            installFacade.bind(mc.action, tokenIid, oldToken.actorId)
        }
        return RefreshRes(
            accessToken = accessToken,
            refreshToken = rawNewToken,
            refreshExpiresAt = newExpiresAt,
            expiresIn = accessTtlSec,
        )
    }

    fun logout(mc: ModuleCtx, req: LogoutReq): LogoutRes {
        val projectId = mc.projectId!!
        val tokenHash = Hashing.sha256Base64Url(req.refreshToken)
        val token = refreshTokenRepo.findValidByHash(mc, projectId, tokenHash)
        if (token != null) {
            refreshTokenRepo.revoke(mc, token.id)
        }
        // 老 customer token 没有 iid，也从未建立 install 关系：兼容退出，只撤销 refresh token。
        // 新 token 有 iid 时正常解绑当前 install↔customer 关系。
        val iid = mc.action.tokenInstallId
        if (iid != null) {
            val customerId = mc.action.actorId
                ?: throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
            installFacade.unbind(mc.action, iid, customerId)
        }
        return LogoutRes(ok = true)
    }

    /**
     * 创建匿名 Customer 并签发 access + refresh token。只要求携带有效可信 iid（token 类型不限）。
     * 在同一事务内：建 customer → 建 refresh token → bind Install→Customer → 签发含 iid 的 customer access token。
     * 既是首装入口，也是登出后惰性重建入口。token: sub=customerId, act="customer", ano=true, iid=install.id。
     */
    fun createAnonymousCustomer(mc: ModuleCtx): CreateAnonymousRes {
        val projectId = mc.projectId!!
        // 只要求 iid 非空（fetcher 已校验，handler 作为信任边界再取一次）。
        val tokenIid = mc.action.mustGetTokenInstallId()
        val customerId = customerRepo.createCustomer(mc, projectId) // anonymous=true

        val now = Instant.now()
        val refreshTokenId = UuidV7.generate()
        val rawRefreshToken = Hashing.randomTokenBase64Url()
        val refreshTokenHash = Hashing.sha256Base64Url(rawRefreshToken)
        val refreshExpiresAt: Instant? = null
        refreshTokenRepo.save(mc, RefreshToken {
            this.id = refreshTokenId
            this.projectId = projectId
            this.actorId = customerId
            this.actorType = AuthJwtService.ACTOR_CUSTOMER
            this.tokenHash = refreshTokenHash
            // expires_at 不承载失效语义（有效性只看 revoked_at，见 RefreshTokenRepositoryTest），匿名 token 存 null
            this.expiresAt = null
            this.revokedAt = null
            this.replacedBy = null
            this.createdAt = now
            this.updatedAt = now
        })

        val accessToken = jwt.signAccess(
            customerId.toString(), AuthJwtService.ACTOR_CUSTOMER, projectId.toString(),
            sessionId = refreshTokenId.toString(), anonymous = true,
            installId = tokenIid.toString(),
        )
        installFacade.bind(mc.action, tokenIid, customerId)
        return CreateAnonymousRes(
            customerId = customerId,
            accessToken = accessToken,
            refreshToken = rawRefreshToken,
            refreshExpiresAt = refreshExpiresAt,
            expiresIn = accessTtlSec,
        )
    }

    fun requestAccountDeletion(mc: ModuleCtx): DeleteAccountRes {
        val actorId = mc.action.actorId ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        val projectId = mc.projectId!!
        // 请求删除当下即软删该 customer 全部有效关系（设计 D9b；不依赖 iid）
        installFacade.unbindAllForCustomer(mc.action, actorId)
        // 逻辑删除 + 记录原因分类/说明（幂等：已删除时 no-op，仍返回 accepted）
        // reason 说明文本来自客户端提交的原始输入；当前 mutation 无入参，暂存 null，
        // 将来 m_auth_deleteAccount 增加 reason 入参时在此透传。
        customerRepo.requestDeletion(mc, projectId, actorId, DeletionReasons.USER_REQUESTED, reason = null)
        // 吊销全部 refresh token：注销后 refresh 链即刻失效（已签发 access token 至多存活至其过期）
        refreshTokenRepo.revokeAllByActor(mc, projectId, actorId, AuthJwtService.ACTOR_CUSTOMER)
        val scheduledAt = Instant.now().plusSeconds(30L * 24 * 3600).toEpochMilli()
        return DeleteAccountRes(accepted = true, scheduledAt = scheduledAt)
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private fun providerKeyForType(idpType: IdpType): String = when (idpType) {
        IdpTypes.APPLE -> "apple"
        IdpTypes.GOOGLE -> "google"
        else -> throw ApiError(ErrorCode.AUTH_PROVIDER_FAILED, "unknown provider type: $idpType")
    }

    private fun createIdpIdentity(mc: ModuleCtx, idpId: UUID, idpType: IdpType, verified: ProviderVerifier.VerifiedResult): IdpIdentity {
        val now = Instant.now()
        val entity = IdpIdentity {
            this.id = UuidV7.generate()
            this.idpType = idpType
            this.idpId = idpId
            this.providerSubjectId = verified.accountId
            this.email = verified.email
            this.emailVerified = verified.emailVerified
            this.phoneCallingCode = null
            this.phoneCountryCode = null
            this.phoneNationalNumber = null
            this.phoneVerified = false
            this.profile = verified.userMetadata
            this.loginIp = mc.action.clientIp
            this.createdAt = now
            this.updatedAt = now
        }
        idpIdentityRepo.save(mc, entity)
        return entity
    }

    private fun createRelation(mc: ModuleCtx, projectId: String, authIdentityId: UUID, idpIdentityId: UUID) {
        val now = Instant.now()
        relationRepo.save(mc, AuthIdentityIdpRelation {
            this.id = UuidV7.generate()
            this.projectId = projectId
            this.authIdentityId = authIdentityId
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

    private fun findPrimaryEmail(mc: ModuleCtx, projectId: String, customerId: UUID): String? {
        // ponytail: 简单实现，后续可优化为专门查询
        val customer = customerRepo.findById(mc, projectId, customerId) ?: return null
        val authIdentityId = customer.authIdentityId ?: return null
        val relation = relationRepo.findFirstByAuthIdentity(mc, projectId, authIdentityId) ?: return null
        val idpIdentity = idpIdentityRepo.findById(mc, relation.idpIdentityId) ?: return null
        return idpIdentity.email
    }
}
