package com.ifmix.api.core.modules.app

import com.ifmix.api.core.entity.app.AppConfigRevision
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
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
}
