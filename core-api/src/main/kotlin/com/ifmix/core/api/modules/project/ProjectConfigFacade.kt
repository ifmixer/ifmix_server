package com.ifmix.core.api.modules.project

import com.ifmix.core.api.entity.project.ProjectConfigRevision
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.modules.project.handler.ProjectConfigAggHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ProjectConfigFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: ProjectConfigAggHandler,
) {
    fun createOneRevision(ctx: OperationContext, req: ProjectConfigAggHandler.CreateRevisionInput): ProjectConfigRevision =
        handler.createOneRevision(mcFactory.forProject(ctx), req)

    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): ProjectConfigRevision =
        handler.toggleRevision(mcFactory.forProject(ctx), revisionId, enabled)

    /** webhook 无 x-project-id header，直接按 bundleId/androidPackageName 反查 projectId */
    fun findAppIdByBundleId(bundleId: String): UUID? =
        handler.findAppIdByBundleId(mcFactory.default(OperationContext()), bundleId)

    fun findAppIdByAndroidPackage(packageName: String): UUID? =
        handler.findAppIdByAndroidPackage(mcFactory.default(OperationContext()), packageName)

    /** 供外部模块按 projectId 获取当前生效配置 */
    fun findActiveByAppId(mc: ModuleCtx, projectId: UUID): ProjectConfigRevision? =
        handler.findActiveByAppId(mc, projectId)
}
