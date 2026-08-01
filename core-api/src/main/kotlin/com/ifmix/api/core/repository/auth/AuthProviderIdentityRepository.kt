package com.ifmix.api.core.repository.auth

import com.ifmix.api.core.entity.auth.AuthProviderIdentity
import com.ifmix.api.core.entity.auth.authTenantId
import com.ifmix.api.core.entity.auth.provider
import com.ifmix.api.core.entity.auth.providerAccountId
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.repository.base.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import org.springframework.stereotype.Repository
import com.ifmix.api.core.infra.db.UuidV7
import java.time.Instant
import java.util.UUID

/** Provider identity repository with custom queries */
@Repository
class AuthProviderIdentityRepository(sql: KSqlClient,) : BaseCrudRepository<AuthProviderIdentity>(sql, AuthProviderIdentity::class) {

    /** Find by tenantId + provider + providerAccountId using Jimmer query DSL */
    fun findByProviderAndAccountId(ctx: OperationContext, tenantId: String, provider: String, providerAccountId: String): AuthProviderIdentity? {
        val tenantUUID = UUID.fromString(tenantId)
        return sql.createQuery(AuthProviderIdentity::class) {
            where(table.authTenantId eq tenantUUID)
            where(table.provider eq provider)
            where(table.providerAccountId eq providerAccountId)
            select(table)
        }.fetchOneOrNull()
    }

    /**
     * Upsert: if exists, update login metadata; otherwise create new.
     * Returns the saved AuthProviderIdentity.
     */
    fun upsert(
        ctx: OperationContext,
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
        val existing = findByProviderAndAccountId(ctx, tenantId, provider, providerAccountId)

        val tenantUUID = UUID.fromString(tenantId)
        val now = Instant.now()

        val entity = AuthProviderIdentity {
            id = existing?.id ?: UuidV7.generate()
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
        return save(ctx, entity)
    }
}
