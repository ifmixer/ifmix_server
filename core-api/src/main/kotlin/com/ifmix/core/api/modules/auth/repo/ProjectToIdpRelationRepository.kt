package com.ifmix.core.api.modules.auth.repo

import com.ifmix.core.api.entity.auth.ProjectToIdpRelation
import com.ifmix.core.api.entity.auth.projectId
import com.ifmix.core.api.entity.auth.deletedAt
import com.ifmix.core.api.entity.auth.idpId
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.isNull
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ProjectToIdpRelationRepository {
    companion object { private val tpl = ProjectCrudRepoTemplate(ProjectToIdpRelation::class) }

    /** 查该 app 是否启用了指定 IDP */
    fun findByAppAndIdp(mc: ModuleCtx, projectId: UUID, idpId: UUID): ProjectToIdpRelation? {
        return mc.sql.createQuery(ProjectToIdpRelation::class) {
            where(table.projectId eq projectId)
            where(table.idpId eq idpId)
            where(table.deletedAt.isNull())
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    fun save(mc: ModuleCtx, entity: ProjectToIdpRelation) = tpl.save(mc, entity)
}
