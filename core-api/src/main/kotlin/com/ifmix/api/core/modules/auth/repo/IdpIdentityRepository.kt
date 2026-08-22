package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.IdpIdentity
import com.ifmix.api.core.entity.auth.idpId
import com.ifmix.api.core.entity.auth.idpIdentityId
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class IdpIdentityRepository {
    companion object { private val tpl = CrudRepoTemplate(IdpIdentity::class) }

    /** 按 (idpId, idpIdentityId) 查找唯一身份 */
    fun findByIdpAndIdentityId(mc: ModuleCtx, idpId: UUID, idpIdentityId: String): IdpIdentity? {
        return mc.sql.createQuery(IdpIdentity::class) {
            where(table.idpId eq idpId)
            where(table.idpIdentityId eq idpIdentityId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findById(mc: ModuleCtx, id: UUID) = tpl.findById(mc, id)
    fun save(mc: ModuleCtx, entity: IdpIdentity) = tpl.save(mc, entity)
}
