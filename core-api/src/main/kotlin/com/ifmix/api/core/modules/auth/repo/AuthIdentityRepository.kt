package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.AuthIdentity
import com.ifmix.api.core.entity.auth.authTenantId
import com.ifmix.api.core.entity.auth.email
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AuthIdentityRepository {
    companion object { private val tpl = CrudRepoTemplate(AuthIdentity::class) }

    fun findByTenantAndEmail(mc: ModuleCtx, tenantId: UUID, email: String): AuthIdentity? {
        return mc.sql.createQuery(AuthIdentity::class) {
            where(table.authTenantId eq tenantId)
            where(table.email eq email)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun save(mc: ModuleCtx, entity: AuthIdentity) = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, id: UUID) = tpl.findById(mc, id)
}
