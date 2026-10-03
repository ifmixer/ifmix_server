package com.ifmix.core.api.modules.project

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.project.repo.ProjectServerConfigRepository
import org.springframework.stereotype.Service

/**
 * 服务端专属项目配置 facade（仅后端内部用，不暴露 customer GraphQL）。
 */
@Service
class ProjectServerConfigFacade(
    private val mcFactory: ModuleCtxFactory,
    private val repo: ProjectServerConfigRepository,
) {
    /** 读某 project 的 FCM service account 配置；无则 null（该 project 不发 FCM push）。 */
    fun findFcmConfig(projectId: String): Map<String, Any?>? {
        val mc = mcFactory.default(ActionContext(projectId = projectId))
        return repo.findByProjectId(mc, projectId)?.fcmConfig
    }
}
