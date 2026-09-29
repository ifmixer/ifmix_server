package com.ifmix.core.api.modules.install.repo

import com.ifmix.core.api.entity.install.Install
import com.ifmix.core.api.entity.install.installId
import com.ifmix.core.api.entity.install.projectId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class InstallRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(Install::class, UUID::class) }

    fun save(mc: ModuleCtx, entity: Install): Boolean = tpl.save(mc, entity)

    /** 按 (projectId, installId) 查设备（installId 是业务唯一键，非主键）。 */
    fun findByInstallId(mc: ModuleCtx, projectId: String, installId: UUID): Install? =
        mc.sql.createQuery(Install::class) {
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            select(table)
        }.limit(1).execute().firstOrNull()
}
