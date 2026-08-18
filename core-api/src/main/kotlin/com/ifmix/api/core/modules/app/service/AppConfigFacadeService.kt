package com.ifmix.api.core.modules.app.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppConfigFacadeService(
    private val internalService: AppConfigInternalService,
    private val tx: TxRunner,
) {
    private fun svc(ctx: OperationContext) = SvcCtx(op = ctx, dsl = SvcCtx.DEFAULT.dsl)

    fun createOneRevision(ctx: OperationContext, req: AppConfigInternalService.CreateRevisionInput): AppConfigInternalService.RevisionDto =
        tx.withTx(svc(ctx)) { sc -> internalService.createOneRevision(sc, req) }

    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): AppConfigInternalService.RevisionDto =
        tx.withTx(svc(ctx)) { sc -> internalService.toggleRevision(sc, revisionId, enabled) }
}
