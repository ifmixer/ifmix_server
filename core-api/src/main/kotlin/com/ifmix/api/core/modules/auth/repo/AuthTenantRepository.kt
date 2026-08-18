package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreAuthTenant.Companion.CORE_AUTH_TENANT
import com.ifmix.api.core.entity.auth.AuthTenant
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * AuthTenant jOOQ repository（无 appId 字段）。
 */
@Repository
class AuthTenantRepository(factory: CrudRepoOpsFactory) {

    private val crud = factory.create(
        table = CORE_AUTH_TENANT,
        idField = CORE_AUTH_TENANT.ID,
        appIdField = null,
        type = AuthTenant::class.java,
    )

    fun findById(ctx: SvcCtx, id: UUID): AuthTenant? = crud.findById(ctx, id)
}
