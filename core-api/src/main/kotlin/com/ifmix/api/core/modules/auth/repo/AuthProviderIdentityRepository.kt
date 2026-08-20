package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthProviderIdentity
import com.ifmix.api.core.entity.auth.authIdentityId
import com.ifmix.api.core.entity.auth.authTenantId
import com.ifmix.api.core.entity.auth.email
import com.ifmix.api.core.entity.auth.provider
import com.ifmix.api.core.entity.auth.providerAccountId
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class AuthProviderIdentityRepository {
    companion object { private val tpl = CrudRepoTemplate(AuthProviderIdentity::class) }

    fun findByProviderAndAccountId(mc: ModuleCtx, tenantId: UUID, provider: String, providerAccountId: String): AuthProviderIdentity? {
        return mc.sql.createQuery(AuthProviderIdentity::class) {
            where(table.authTenantId eq tenantId)
            where(table.provider eq provider)
            where(table.providerAccountId eq providerAccountId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun upsert(
        mc: ModuleCtx, tenantId: UUID, provider: String, providerAccountId: String,
        identityId: UUID, email: String?, emailVerified: Boolean, phone: String?,
        userMetadata: Map<String, Any?>?, providerMetadata: Map<String, Any?>?,
        loginIp: String?, loginInstallId: UUID?, loginAppId: UUID?,
    ): UUID {
        val existing = findByProviderAndAccountId(mc, tenantId, provider, providerAccountId)
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
        mc.sql.entities.save(entity)
        return id
    }
}
