package com.ifmix.core.api.modules.project.repo

import com.ifmix.core.api.entity.project.ProjectInfo
import com.ifmix.core.api.entity.project.id
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.CrudRepoTemplate
import org.springframework.stereotype.Repository

@Repository
class ProjectInfoRepository {
    companion object { private val tpl = CrudRepoTemplate(ProjectInfo::class, String::class) }

    /** id 即 slug。 */
    fun findById(mc: ModuleCtx, id: String): ProjectInfo? = tpl.findById(mc, id)

    /** 全量 projectId（阶段 6 清理任务按 app 逐个扫描候选）。 */
    fun findAllIds(mc: ModuleCtx): List<String> =
        mc.sql.createQuery(ProjectInfo::class) {
            select(table.id)
        }.execute()
}
