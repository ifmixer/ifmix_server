package com.ifmix.core.api.modules.project.repo

import com.ifmix.core.api.entity.project.ProjectServerConfig
import com.ifmix.core.api.entity.project.projectId
import com.ifmix.core.api.infra.db.ModuleCtx
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.springframework.stereotype.Repository

/**
 * 服务端专属项目配置访问（仅后端内部用，绝不暴露 customer GraphQL）。
 */
@Repository
class ProjectServerConfigRepository {

    fun findByProjectId(mc: ModuleCtx, projectId: String): ProjectServerConfig? =
        mc.sql.createQuery(ProjectServerConfig::class) {
            where(table.projectId eq projectId)
            select(table)
        }.limit(1).execute().firstOrNull()
}
