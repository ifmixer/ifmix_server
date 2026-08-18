package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreAuthProviderIdentity.Companion.CORE_AUTH_PROVIDER_IDENTITY
import com.ifmix.api.core.entity.auth.AuthProviderIdentity
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Repository
class AuthProviderIdentityRepository(factory: CrudRepoOpsFactory) {

    companion object {
        private val mapper = jacksonObjectMapper()
    }

    fun findByProviderAndAccountId(ctx: SvcCtx, tenantId: UUID, provider: String, providerAccountId: String): AuthProviderIdentity? =
        ctx.dsl.selectFrom(CORE_AUTH_PROVIDER_IDENTITY)
            .where(CORE_AUTH_PROVIDER_IDENTITY.AUTH_TENANT_ID.eq(tenantId))
            .and(CORE_AUTH_PROVIDER_IDENTITY.PROVIDER.eq(provider))
            .and(CORE_AUTH_PROVIDER_IDENTITY.PROVIDER_ACCOUNT_ID.eq(providerAccountId))
            .fetchOneInto(AuthProviderIdentity::class.java)

    fun upsert(
        ctx: SvcCtx, tenantId: UUID, provider: String, providerAccountId: String,
        identityId: UUID, email: String?, emailVerified: Boolean, phone: String?,
        userMetadata: Map<String, Any?>?, providerMetadata: Map<String, Any?>?,
        loginIp: String?, loginInstallId: UUID?, loginAppId: UUID?,
    ): UUID {
        val existing = findByProviderAndAccountId(ctx, tenantId, provider, providerAccountId)
        val now = Instant.now()
        val id = existing?.id ?: UUID.randomUUID()

        val entity = AuthProviderIdentity(
            id = id, authTenantId = tenantId, authIdentityId = identityId,
            provider = provider, providerAccountId = providerAccountId,
            email = email, emailVerified = emailVerified, phone = phone,
            userMetadata = userMetadata?.let { mapper.writeValueAsString(it) },
            providerMetadata = providerMetadata?.let { mapper.writeValueAsString(it) },
            loginIp = loginIp, loginInstallId = loginInstallId, loginAppId = loginAppId,
            createdAt = existing?.createdAt ?: now, updatedAt = now,
        )

        if (existing != null) {
            val record = ctx.dsl.newRecord(CORE_AUTH_PROVIDER_IDENTITY, entity)
            record.userMetadata = entity.userMetadata?.let { JSONB.jsonb(it) }
            record.providerMetadata = entity.providerMetadata?.let { JSONB.jsonb(it) }
            record.changed(CORE_AUTH_PROVIDER_IDENTITY.ID, false)
            ctx.dsl.executeUpdate(record)
        } else {
            val record = ctx.dsl.newRecord(CORE_AUTH_PROVIDER_IDENTITY, entity)
            record.userMetadata = entity.userMetadata?.let { JSONB.jsonb(it) }
            record.providerMetadata = entity.providerMetadata?.let { JSONB.jsonb(it) }
            ctx.dsl.executeInsert(record)
        }
        return id
    }
}
