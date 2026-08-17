package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jooq.CrudOps
import com.ifmix.api.core.jooq.tables.CoreAuthIdentity.Companion.CORE_AUTH_IDENTITY
import com.ifmix.api.core.model.AuthIdentity
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * AuthIdentity jOOQ repository.
 * The original Jimmer AuthIdentityRepository is preserved for Wave 5 cleanup.
 */
@Repository
class AuthIdentityJooqRepository(
    private val crud: CrudOps,
) {
    companion object {
        private val mapper = jacksonObjectMapper()
    }

    fun findByTenantAndEmail(ctx: RepoContext, tenantId: UUID, email: String): AuthIdentity? {
        val record = ctx.dsl.selectFrom(CORE_AUTH_IDENTITY)
            .where(CORE_AUTH_IDENTITY.AUTH_TENANT_ID.eq(tenantId))
            .and(CORE_AUTH_IDENTITY.EMAIL.eq(email))
            .fetchOne()
        return record?.let { toModel(it) }
    }

    fun findById(ctx: RepoContext, id: UUID): AuthIdentity? {
        val record = ctx.dsl.selectFrom(CORE_AUTH_IDENTITY)
            .where(CORE_AUTH_IDENTITY.ID.eq(id))
            .fetchOne()
        return record?.let { toModel(it) }
    }

    fun insert(ctx: RepoContext, identity: AuthIdentity) {
        crud.insert(ctx, CORE_AUTH_IDENTITY, identity)
    }

    // =========================================================================
    // JSON ↔ model helpers
    // =========================================================================

    private fun toModel(r: org.jooq.Record): AuthIdentity = AuthIdentity(
        id = r.get(CORE_AUTH_IDENTITY.ID)!!,
        authTenantId = r.get(CORE_AUTH_IDENTITY.AUTH_TENANT_ID)!!,
        rawEmail = r.get(CORE_AUTH_IDENTITY.RAW_EMAIL),
        email = r.get(CORE_AUTH_IDENTITY.EMAIL),
        rawPhone = r.get(CORE_AUTH_IDENTITY.RAW_PHONE),
        phone = r.get(CORE_AUTH_IDENTITY.PHONE),
        contactEmail = r.get(CORE_AUTH_IDENTITY.CONTACT_EMAIL),
        displayName = r.get(CORE_AUTH_IDENTITY.DISPLAY_NAME),
        passwordHash = r.get(CORE_AUTH_IDENTITY.PASSWORD_HASH),
        profile = r.get(CORE_AUTH_IDENTITY.PROFILE)?.toString(),
        metadata = r.get(CORE_AUTH_IDENTITY.METADATA)?.toString(),
        createdAt = r.get(CORE_AUTH_IDENTITY.CREATED_AT) ?: Instant.now(),
        updatedAt = r.get(CORE_AUTH_IDENTITY.UPDATED_AT),
    )
}
