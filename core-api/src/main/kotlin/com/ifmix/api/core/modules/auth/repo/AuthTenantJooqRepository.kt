package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.jooq.CrudOps
import com.ifmix.api.core.jooq.tables.CoreAuthTenant.Companion.CORE_AUTH_TENANT
import com.ifmix.api.core.model.AuthTenant
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * AuthTenant jOOQ repository.
 * The original Jimmer AuthTenantRepository is preserved for Wave 5 cleanup.
 */
@Repository
class AuthTenantJooqRepository(
    private val crud: CrudOps,
) {

    fun findById(ctx: RepoContext, id: UUID): AuthTenant? {
        val record = ctx.dsl.selectFrom(CORE_AUTH_TENANT)
            .where(CORE_AUTH_TENANT.ID.eq(id))
            .fetchOne()
        return record?.let { toModel(it) }
    }

    // =========================================================================
    // Record ↔ model helpers
    // =========================================================================

    private fun toModel(r: org.jooq.Record): AuthTenant = AuthTenant(
        id = r.get(CORE_AUTH_TENANT.ID)!!,
        jwtPrivateKeyPem = r.get(CORE_AUTH_TENANT.JWT_PRIVATE_KEY_PEM),
        jwtIssuer = r.get(CORE_AUTH_TENANT.JWT_ISSUER),
        createdAt = r.get(CORE_AUTH_TENANT.CREATED_AT)!!,
        updatedAt = r.get(CORE_AUTH_TENANT.UPDATED_AT),
    )
}
