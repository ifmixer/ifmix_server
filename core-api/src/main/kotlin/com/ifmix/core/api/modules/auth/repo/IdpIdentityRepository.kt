package com.ifmix.core.api.modules.auth.repo

import com.ifmix.core.api.entity.auth.IdpIdentity
import com.ifmix.core.api.entity.auth.idpId
import com.ifmix.core.api.entity.auth.providerSubjectId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class IdpIdentityRepository {
    companion object { private val tpl = CrudRepoTemplate(IdpIdentity::class) }

    /** 按 (idpId, providerSubjectId) 查找唯一身份 */
    fun findByIdpAndSubject(mc: ModuleCtx, idpId: UUID, providerSubjectId: String): IdpIdentity? {
        return mc.sql.createQuery(IdpIdentity::class) {
            where(table.idpId eq idpId)
            where(table.providerSubjectId eq providerSubjectId)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun findById(mc: ModuleCtx, id: UUID) = tpl.findById(mc, id)
    fun save(mc: ModuleCtx, entity: IdpIdentity) = tpl.save(mc, entity)
}
