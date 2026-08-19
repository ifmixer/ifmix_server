package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthProviderIdentity
import com.ifmix.api.core.entity.auth.authTenant
import com.ifmix.api.core.entity.auth.provider
import com.ifmix.api.core.entity.auth.providerAccountId
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.repo.BaseCrudRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID
import com.ifmix.api.core.entity.auth.authIdentityId
import com.ifmix.api.core.entity.auth.authTenantId

@Repository
class AuthProviderIdentityRepository(sql: KSqlClient) : BaseCrudRepository<AuthProviderIdentity>(sql, AuthProviderIdentity::class) {

    fun findByProviderAndAccountId(ctx: SvcCtx, tenantId: UUID, provider: String, providerAccountId: String): AuthProviderIdentity? {
        return ctx.sql.createQuery(AuthProviderIdentity::class) {
            where(table.authTenantId eq tenantId)
            where(table.provider eq provider)
            where(table.providerAccountId eq providerAccountId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun upsert(
        ctx: SvcCtx, tenantId: UUID, provider: String, providerAccountId: String,
        identityId: UUID, email: String?, emailVerified: Boolean, phone: String?,
        userMetadata: Map<String, Any?>?, providerMetadata: Map<String, Any?>?,
        loginIp: String?, loginInstallId: UUID?, loginAppId: UUID?,
    ): UUID {
        val existing = findByProviderAndAccountId(ctx, tenantId, provider, providerAccountId)
        val now = Instant.now()
        val id = existing?.id ?: UuidV7.generate()

        val entity = AuthProviderIdentity {
            this.id = id
            this.authTenant { this.id = tenantId }
            this.authIdentity { this.id = identityId }
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
            this.createdAt = existing?.createdAt ?: now
            this.updatedAt = now
        }
        save(ctx, entity)
        return id
    }
}
