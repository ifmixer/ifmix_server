package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOps
import com.ifmix.api.core.jooq.tables.CoreAuthIdentity.Companion.CORE_AUTH_IDENTITY
import com.ifmix.api.core.entity.auth.AuthIdentity
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AuthIdentityRepository(private val crud: CrudRepoOps) {

    fun findByTenantAndEmail(ctx: SvcCtx, tenantId: UUID, email: String): AuthIdentity? =
        ctx.dsl.selectFrom(CORE_AUTH_IDENTITY)
            .where(CORE_AUTH_IDENTITY.AUTH_TENANT_ID.eq(tenantId))
            .and(CORE_AUTH_IDENTITY.EMAIL.eq(email))
            .fetchOneInto(AuthIdentity::class.java)

    fun findById(ctx: SvcCtx, id: UUID): AuthIdentity? =
        ctx.dsl.selectFrom(CORE_AUTH_IDENTITY)
            .where(CORE_AUTH_IDENTITY.ID.eq(id))
            .fetchOneInto(AuthIdentity::class.java)

    fun insert(ctx: SvcCtx, identity: AuthIdentity) = crud.insert(ctx, CORE_AUTH_IDENTITY, identity)
}
