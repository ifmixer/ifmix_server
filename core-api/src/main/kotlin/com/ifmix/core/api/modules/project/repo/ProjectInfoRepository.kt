package com.ifmix.core.api.modules.project.repo

import com.ifmix.core.api.entity.project.ProjectInfo
import com.ifmix.core.api.entity.project.id
import com.ifmix.core.api.entity.project.slug
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.CrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ProjectInfoRepository {
    companion object { private val tpl = CrudRepoTemplate(ProjectInfo::class) }

    fun findBySlug(mc: ModuleCtx, slug: String): ProjectInfo? {
        return mc.sql.createQuery(ProjectInfo::class) {
            where(table.slug eq slug)
            select(table)
        }.limit(1).execute().firstOrNull()
    }

    /** 全量 projectId（阶段 6 清理任务按 app 逐个扫描候选）。 */
    fun findAllIds(mc: ModuleCtx): List<UUID> =
        mc.sql.createQuery(ProjectInfo::class) {
            select(table.id)
        }.execute()
}
