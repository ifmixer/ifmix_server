package com.ifmix.api.core.modules.user.repo

import com.ifmix.api.core.entity.user.Install
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.repo.AppCrudRepoTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class InstallRepository {
    companion object { private val tpl = AppCrudRepoTemplate(Install::class) }

    fun save(mc: ModuleCtx, entity: Install): Boolean = tpl.save(mc, entity)
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun exists(mc: ModuleCtx, appId: UUID, id: UUID): Boolean = tpl.exists(mc, appId, id)
}
