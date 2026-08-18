package com.ifmix.api.core.modules.app.service

import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.modules.app.service.internal.AppConfigEntityService
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AppConfigModuleService(
    private val svcCtxFactory: SvcCtxFactory,
    private val entityService: AppConfigEntityService,
    private val tx: TxRunner,
) {
    fun createOneRevision(ctx: OperationContext, req: AppConfigEntityService.CreateRevisionInput): AppConfigEntityService.RevisionDto =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> entityService.createOneRevision(sc, req) }

    fun toggleRevision(ctx: OperationContext, revisionId: UUID, enabled: Boolean): AppConfigEntityService.RevisionDto =
        tx.withTx(svcCtxFactory.forApp(ctx)) { sc -> entityService.toggleRevision(sc, revisionId, enabled) }
}
