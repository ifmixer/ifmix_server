package com.ifmix.api.core.modules.auth.repo

import com.ifmix.api.core.entity.auth.Idp
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.CrudRepoTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class IdpRepository {
    companion object { private val tpl = CrudRepoTemplate(Idp::class) }

    fun findById(mc: ModuleCtx, id: UUID) = tpl.findById(mc, id)
    fun findByIds(mc: ModuleCtx, ids: Collection<UUID>) = tpl.findByIds(mc, ids)
    fun save(mc: ModuleCtx, entity: Idp) = tpl.save(mc, entity)
}
