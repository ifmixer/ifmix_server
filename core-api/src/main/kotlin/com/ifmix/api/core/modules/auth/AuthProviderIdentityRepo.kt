package com.ifmix.api.core.modules.auth

import com.ifmix.api.core.common.auth.EmailNormalize
import com.ifmix.api.core.common.db.BaseEntity
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.stereotype.Component
import java.time.Instant
import com.ifmix.api.core.modules.auth.entity.AuthIdentityEntity
import com.ifmix.api.core.modules.auth.entity.AuthProviderIdentityEntity

/** 供 AuthProviderIdentityRepo.upsert 使用的输入参数。 */
data class UpsertInput(
    val provider: String,
    val accountId: String,
    val email: String?,
    val emailVerified: Boolean,
    val phone: String?,
    val userMetadata: Map<String, Any?>?,
    val loginIp: String?,
    val loginInstallId: String?,
    val loginAppId: String?,
)

/**
 * provider_identity upsert：根据 (authTenantId, provider, providerAccountId) 查找，
 * 不存在则创建 provider_identity + auth_identity，返回 authIdentityId。
 */
@Component
class AuthProviderIdentityRepo(private val mongo: MongoTemplate) {

    /**
     * 返回 authIdentityId。已存在 provider 账号 → 复用其 identity 并更新登录元信息；
     * 否则新建 identity + provider_identity。
     */
    fun upsert(tenantId: String, input: UpsertInput): String {
        val now = Instant.now()
        val existing = mongo.findOne(
            Query(Criteria().andOperator(
                AuthProviderIdentityEntity::authTenantId isEqualTo tenantId,
                AuthProviderIdentityEntity::provider isEqualTo input.provider,
                AuthProviderIdentityEntity::providerAccountId isEqualTo input.accountId,
            )),
            AuthProviderIdentityEntity::class.java,
        )
        if (existing?.authIdentityId != null) {
            mongo.updateFirst(
                Query(Criteria().andOperator(AuthProviderIdentityEntity::id isEqualTo existing.id)),
                Update().set(AuthProviderIdentityEntity::email, input.email)
                    .set(AuthProviderIdentityEntity::emailVerified, input.emailVerified)
                    .set(AuthProviderIdentityEntity::phone, input.phone)
                    .set(AuthProviderIdentityEntity::loginIp, input.loginIp)
                    .set(AuthProviderIdentityEntity::loginInstallId, input.loginInstallId)
                    .set(AuthProviderIdentityEntity::loginAppId, input.loginAppId)
                    .set(AuthProviderIdentityEntity::userMetadata, input.userMetadata)
                    .set(BaseEntity::updatedAt, now),
                AuthProviderIdentityEntity::class.java,
            )
            return existing.authIdentityId!!
        }
        // 新建 identity（v1：每次新 provider 账号 = 新 identity，不按 email 合并）
        val identity = AuthIdentityEntity().apply {
            authTenantId = tenantId
            rawEmail = input.email
            email = EmailNormalize.normalizeEmail(input.email)
            rawPhone = input.phone
            phone = EmailNormalize.normalizePhone(input.phone)
            displayName = input.userMetadata?.get("name") as? String
            profile = input.userMetadata
            createdAt = now; updatedAt = now
        }
        mongo.insert(identity)
        val pi = AuthProviderIdentityEntity().apply {
            authTenantId = tenantId
            authIdentityId = identity.id.toHexString()
            provider = input.provider
            providerAccountId = input.accountId
            email = input.email
            emailVerified = input.emailVerified
            phone = input.phone
            userMetadata = input.userMetadata
            loginIp = input.loginIp; loginInstallId = input.loginInstallId; loginAppId = input.loginAppId
            createdAt = now; updatedAt = now
        }
        mongo.insert(pi)
        return identity.id.toHexString()
    }
}
