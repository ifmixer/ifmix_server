package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreAuthProviderIdentity.Companion.CORE_AUTH_PROVIDER_IDENTITY
import com.ifmix.api.core.model.AuthProviderIdentity
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * AuthProviderIdentity jOOQ repository.
 */
@Repository
class AuthProviderIdentityRepository(
    private val crud: CrudRepoOps,
) {
    companion object {
        private val mapper = jacksonObjectMapper()
    }

    fun findByProviderAndAccountId(
        ctx: RepoContext,
        tenantId: UUID,
        provider: String,
        providerAccountId: String,
    ): AuthProviderIdentity? {
        val record = ctx.dsl.selectFrom(CORE_AUTH_PROVIDER_IDENTITY)
            .where(CORE_AUTH_PROVIDER_IDENTITY.AUTH_TENANT_ID.eq(tenantId))
            .and(CORE_AUTH_PROVIDER_IDENTITY.PROVIDER.eq(provider))
            .and(CORE_AUTH_PROVIDER_IDENTITY.PROVIDER_ACCOUNT_ID.eq(providerAccountId))
            .fetchOne()
        return record?.let { toModel(it) }
    }

    /**
     * Upsert: if exists, update login metadata; otherwise create new.
     */
    fun upsert(
        ctx: RepoContext,
        tenantId: UUID,
        provider: String,
        providerAccountId: String,
        identityId: UUID,
        email: String?,
        emailVerified: Boolean,
        phone: String?,
        userMetadata: Map<String, Any?>?,
        providerMetadata: Map<String, Any?>?,
        loginIp: String?,
        loginInstallId: UUID?,
        loginAppId: UUID?,
    ): UUID {
        val existing = findByProviderAndAccountId(ctx, tenantId, provider, providerAccountId)

        val now = Instant.now()
        val id = existing?.id ?: UUID.randomUUID()

        val entity = AuthProviderIdentity(
            id = id,
            authTenantId = tenantId,
            authIdentityId = identityId,
            provider = provider,
            providerAccountId = providerAccountId,
            email = email,
            emailVerified = emailVerified,
            phone = phone,
            userMetadata = userMetadata?.let { mapper.writeValueAsString(it) },
            providerMetadata = providerMetadata?.let { mapper.writeValueAsString(it) },
            loginIp = loginIp,
            loginInstallId = loginInstallId,
            loginAppId = loginAppId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )

        if (existing != null) {
            ctx.dsl.update(CORE_AUTH_PROVIDER_IDENTITY)
                .set(CORE_AUTH_PROVIDER_IDENTITY.AUTH_IDENTITY_ID, entity.authIdentityId)
                .set(CORE_AUTH_PROVIDER_IDENTITY.EMAIL, entity.email)
                .set(CORE_AUTH_PROVIDER_IDENTITY.EMAIL_VERIFIED, entity.emailVerified)
                .set(CORE_AUTH_PROVIDER_IDENTITY.PHONE, entity.phone)
                .set(CORE_AUTH_PROVIDER_IDENTITY.USER_METADATA, entity.userMetadata?.let { JSONB.jsonb(it) })
                .set(CORE_AUTH_PROVIDER_IDENTITY.PROVIDER_METADATA, entity.providerMetadata?.let { JSONB.jsonb(it) })
                .set(CORE_AUTH_PROVIDER_IDENTITY.LOGIN_IP, entity.loginIp)
                .set(CORE_AUTH_PROVIDER_IDENTITY.LOGIN_INSTALL_ID, entity.loginInstallId)
                .set(CORE_AUTH_PROVIDER_IDENTITY.LOGIN_APP_ID, entity.loginAppId)
                .set(CORE_AUTH_PROVIDER_IDENTITY.UPDATED_AT, entity.updatedAt)
                .where(CORE_AUTH_PROVIDER_IDENTITY.ID.eq(id))
                .execute()
        } else {
            crud.insert(ctx, CORE_AUTH_PROVIDER_IDENTITY, entity)
        }

        return id
    }

    // =========================================================================
    // JSON ↔ model helpers
    // =========================================================================

    private fun toModel(r: org.jooq.Record): AuthProviderIdentity = AuthProviderIdentity(
        id = r.get(CORE_AUTH_PROVIDER_IDENTITY.ID)!!,
        authTenantId = r.get(CORE_AUTH_PROVIDER_IDENTITY.AUTH_TENANT_ID)!!,
        authIdentityId = r.get(CORE_AUTH_PROVIDER_IDENTITY.AUTH_IDENTITY_ID)!!,
        provider = r.get(CORE_AUTH_PROVIDER_IDENTITY.PROVIDER)!!,
        providerAccountId = r.get(CORE_AUTH_PROVIDER_IDENTITY.PROVIDER_ACCOUNT_ID)!!,
        email = r.get(CORE_AUTH_PROVIDER_IDENTITY.EMAIL),
        emailVerified = r.get(CORE_AUTH_PROVIDER_IDENTITY.EMAIL_VERIFIED) ?: false,
        phone = r.get(CORE_AUTH_PROVIDER_IDENTITY.PHONE),
        userMetadata = r.get(CORE_AUTH_PROVIDER_IDENTITY.USER_METADATA)?.toString(),
        providerMetadata = r.get(CORE_AUTH_PROVIDER_IDENTITY.PROVIDER_METADATA)?.toString(),
        loginIp = r.get(CORE_AUTH_PROVIDER_IDENTITY.LOGIN_IP),
        loginInstallId = r.get(CORE_AUTH_PROVIDER_IDENTITY.LOGIN_INSTALL_ID),
        loginAppId = r.get(CORE_AUTH_PROVIDER_IDENTITY.LOGIN_APP_ID),
        createdAt = r.get(CORE_AUTH_PROVIDER_IDENTITY.CREATED_AT) ?: Instant.now(),
        updatedAt = r.get(CORE_AUTH_PROVIDER_IDENTITY.UPDATED_AT),
    )
}
