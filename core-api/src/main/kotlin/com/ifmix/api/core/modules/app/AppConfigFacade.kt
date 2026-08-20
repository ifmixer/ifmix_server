package com.ifmix.api.core.modules.app

import com.ifmix.api.core.entity.app.AppConfigRevision
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.tx.TxRunner
import com.ifmix.api.core.modules.app.handler.AppConfigHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppConfigFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: AppConfigHandler,
    private val tx: TxRunner,
) {
    fun createOneRevision(ctx: OperationContext, req: AppConfigHandler.CreateRevisionInput): AppConfigRevision =
        tx.withTx(mcFactory.forApp(ctx)) { sc -> handler.createOneRevision(sc, req) }

    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): AppConfigRevision =
        tx.withTx(mcFactory.forApp(ctx)) { sc -> handler.toggleRevision(sc, revisionId, enabled) }
}
