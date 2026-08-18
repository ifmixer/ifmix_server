package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreAuthTenant.Companion.CORE_AUTH_TENANT
import com.ifmix.api.core.entity.auth.AuthTenant
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * AuthTenant jOOQ repository.
 */
@Repository
class AuthTenantRepository(
    private val crud: CrudRepoOps,
) {
    fun findById(ctx: SvcCtx, id: UUID): AuthTenant? =
        crud.findById(ctx, CORE_AUTH_TENANT, CORE_AUTH_TENANT.ID, id, AuthTenant::class.java)
}
