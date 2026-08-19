package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreAuthIdentity.Companion.CORE_AUTH_IDENTITY
import com.ifmix.api.core.entity.auth.AuthIdentity
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AuthIdentityRepository(factory: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            AuthIdentity::id.name to CORE_AUTH_IDENTITY.ID,
            AuthIdentity::authTenantId.name to CORE_AUTH_IDENTITY.AUTH_TENANT_ID,
            AuthIdentity::email.name to CORE_AUTH_IDENTITY.EMAIL,
            AuthIdentity::phone.name to CORE_AUTH_IDENTITY.PHONE,
            AuthIdentity::displayName.name to CORE_AUTH_IDENTITY.DISPLAY_NAME,
            AuthIdentity::createdAt.name to CORE_AUTH_IDENTITY.CREATED_AT,
            AuthIdentity::updatedAt.name to CORE_AUTH_IDENTITY.UPDATED_AT,
        )
    }

    private val crud = factory.create(
        table = CORE_AUTH_IDENTITY,
        idField = CORE_AUTH_IDENTITY.ID,
        appIdField = null,
        type = AuthIdentity::class.java,
    )

    fun findByTenantAndEmail(ctx: SvcCtx, tenantId: UUID, email: String): AuthIdentity? =
        ctx.dsl.selectFrom(CORE_AUTH_IDENTITY)
            .where(CORE_AUTH_IDENTITY.AUTH_TENANT_ID.eq(tenantId))
            .and(CORE_AUTH_IDENTITY.EMAIL.eq(email))
            .fetchOneInto(AuthIdentity::class.java)

    fun findById(ctx: SvcCtx, id: UUID): AuthIdentity? = crud.findById(ctx, id)

    fun insert(ctx: SvcCtx, identity: AuthIdentity) = crud.insert(ctx, identity)
}
