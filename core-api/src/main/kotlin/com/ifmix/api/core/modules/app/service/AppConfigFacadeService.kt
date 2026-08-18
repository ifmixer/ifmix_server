package com.ifmix.api.core.modules.app.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppConfigFacadeService(
    private val commands: AppConfigCommands,
    private val tx: TxRunner,
) {
    private fun svc(ctx: OperationContext) = SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl)

    fun createOneRevision(ctx: OperationContext, req: AppConfigCommands.CreateRevisionInput): AppConfigCommands.RevisionDto =
        tx.withTx(svc(ctx)) { sc -> commands.createOneRevision(sc, req) }

    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): AppConfigCommands.RevisionDto =
        tx.withTx(svc(ctx)) { sc -> commands.toggleRevision(sc, revisionId, enabled) }
}
