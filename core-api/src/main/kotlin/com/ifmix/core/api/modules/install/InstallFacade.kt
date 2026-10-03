package com.ifmix.core.api.modules.install

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.install.handler.InstallAggHandler
import org.springframework.stereotype.Service
import java.util.UUID


/** notification 模块读取 install 的最小公开视图。 */
data class InstallNotificationTarget(
    val enabled: Boolean,
    val fcmToken: String?,
    val fcmTokenValid: Boolean,
    val deepResearchNotiEnabled: Boolean = true,
)
@Service
class InstallFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: InstallAggHandler,
) {
    fun createInstall(ctx: ActionContext, deviceInfo: Map<String, Any?>?): InstallAggHandler.CreateInstallRes =
        handler.createInstall(mcFactory.forProject(ctx), deviceInfo)

    fun updateInstall(
        ctx: ActionContext,
        installId: UUID,
        firebaseInstallId: String?,
        fcmToken: String?,
        deviceInfo: Map<String, Any?>?,
        scanResultNotiEnabled: Boolean?,
        deepResearchNotiEnabled: Boolean?,
    ): Boolean = handler.updateInstall(
        mcFactory.forProject(ctx), installId, firebaseInstallId, fcmToken, deviceInfo,
        scanResultNotiEnabled, deepResearchNotiEnabled,
    )

    fun findNotificationTarget(ctx: ActionContext, installId: UUID): InstallNotificationTarget? =
        handler.findNotificationTarget(mcFactory.forProject(ctx), installId)

    fun invalidateFcmToken(ctx: ActionContext, installId: UUID, sentToken: String): Int =
        handler.invalidateFcmToken(mcFactory.forProject(ctx), installId, sentToken)

    // ===== 关系维护（供 AuthAggHandler 在 auth 流程内调用） =====

    fun bind(ctx: ActionContext, installId: UUID, customerId: UUID) =
        handler.bind(mcFactory.forProject(ctx), ctx.mustGetProjectId(), installId, customerId)

    fun unbind(ctx: ActionContext, installId: UUID, customerId: UUID) =
        handler.unbind(mcFactory.forProject(ctx), ctx.mustGetProjectId(), installId, customerId)

    fun unbindAllForCustomer(ctx: ActionContext, customerId: UUID) =
        handler.unbindAllForCustomer(mcFactory.forProject(ctx), ctx.mustGetProjectId(), customerId)
}
