package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.repository.base.BaseCrudRepository
import com.ifmix.api.core.entity.auth.AuthProviderIdentity
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Provider identity repository with custom queries */
@Component
class AuthProviderIdentityRepository(
    sql: KSqlClient,
) : BaseCrudRepository<AuthProviderIdentity>(sql, AuthProviderIdentity::class) {

    /** 按 tenantId + provider + providerAccountId 查找 */
    fun findByProviderAndAccountId(tenantId: String, provider: String, providerAccountId: String): AuthProviderIdentity? {
        return findAll().firstOrNull {
            it.authTenant.id.toString() == tenantId && it.provider == provider && it.providerAccountId == providerAccountId
        }
    }

    /**
     * Upsert: if exists, update login metadata; otherwise create new.
     * Returns the saved AuthProviderIdentity.
     */
    fun upsert(
        tenantId: String,
        provider: String,
        providerAccountId: String,
        identityId: UUID,
        email: String?,
        emailVerified: Boolean,
        phone: String?,
        userMetadata: Map<String, Any?>?,
        providerMetadata: Map<String, Any?>?,
        loginIp: String?,
        loginInstallId: String?,
        loginAppId: String?,
    ): AuthProviderIdentity {
        val existing = findByProviderAndAccountId(tenantId, provider, providerAccountId)

        val tenantUUID = UUID.fromString(tenantId)
        val now = Instant.now()

        val entity = AuthProviderIdentity {
            id = existing?.id ?: UUID.randomUUID()
            authTenant { id = tenantUUID }
            authIdentity { id = identityId }
            this.provider = provider
            this.providerAccountId = providerAccountId
            this.email = email
            this.emailVerified = emailVerified
            this.phone = phone
            this.userMetadata = userMetadata
            this.providerMetadata = providerMetadata
            this.loginIp = loginIp
            this.loginInstallId = loginInstallId
            this.loginAppId = loginAppId
            createdAt = existing?.createdAt ?: now
            updatedAt = now
        }
        return save(entity)
    }
}
