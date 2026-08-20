package com.ifmix.api.core.modules.app

import com.ifmix.api.core.entity.app.AppConfigRevision
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.modules.app.handler.AppConfigAggHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppConfigFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: AppConfigAggHandler,
) {
    fun createOneRevision(ctx: OperationContext, req: AppConfigAggHandler.CreateRevisionInput): AppConfigRevision =
        handler.createOneRevision(mcFactory.forApp(ctx), req)

    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): AppConfigRevision =
        handler.toggleRevision(mcFactory.forApp(ctx), revisionId, enabled)

    /** webhook 无 x-app-id header，直接按 bundleId/androidPackageName 反查 appId */
    fun findAppIdByBundleId(bundleId: String): UUID? =
        handler.findAppIdByBundleId(mcFactory.default(OperationContext(req = RequestContext())), bundleId)

    fun findAppIdByAndroidPackage(packageName: String): UUID? =
        handler.findAppIdByAndroidPackage(mcFactory.default(OperationContext(req = RequestContext())), packageName)
}
