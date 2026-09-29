package com.ifmix.core.api.modules.install

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.install.handler.InstallAggHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class InstallFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: InstallAggHandler,
) {
    fun createInstall(ctx: ActionContext, deviceInfo: Map<String, Any?>?): InstallAggHandler.CreateInstallRes =
        handler.createInstall(mcFactory.forProject(ctx), deviceInfo)

    fun updateInstall(ctx: ActionContext, installId: UUID, fid: String?, fcmToken: String?, deviceInfo: Map<String, Any?>?): Boolean =
        handler.updateInstall(mcFactory.forProject(ctx), installId, fid, fcmToken, deviceInfo)

    // ===== 关系维护（供 AuthAggHandler 在 auth 流程内调用） =====

    fun bind(ctx: ActionContext, installId: UUID, customerId: UUID) =
        handler.bind(mcFactory.forProject(ctx), ctx.mustGetProjectId(), installId, customerId)

    fun unbind(ctx: ActionContext, installId: UUID, customerId: UUID) =
        handler.unbind(mcFactory.forProject(ctx), ctx.mustGetProjectId(), installId, customerId)

    fun unbindAllForCustomer(ctx: ActionContext, customerId: UUID) =
        handler.unbindAllForCustomer(mcFactory.forProject(ctx), ctx.mustGetProjectId(), customerId)
}
