package com.ifmix.core.api.modules.auth.repo

import com.ifmix.core.api.entity.auth.Idp
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.CrudRepoTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class IdpRepository {
    companion object { private val tpl = CrudRepoTemplate(Idp::class) }

    fun findById(mc: ModuleCtx, id: UUID) = tpl.findById(mc, id)
    fun findByIds(mc: ModuleCtx, ids: Collection<UUID>) = tpl.findByIds(mc, ids)
    fun save(mc: ModuleCtx, entity: Idp) = tpl.save(mc, entity)
}
