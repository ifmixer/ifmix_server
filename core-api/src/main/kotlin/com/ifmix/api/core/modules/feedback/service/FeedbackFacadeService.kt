package com.ifmix.api.core.modules.feedback.service

import com.ifmix.api.core.dto.common.CreateOneRes
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.dto.feedback.SubmitFeedbackReq
import org.springframework.stereotype.Service

@Service
class FeedbackFacadeService(
    private val commands: FeedbackCommands,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): CreateOneRes =
        tx.withTx(svc(ctx)) { sc ->
            CreateOneRes(id = commands.submit(sc, req))
        }
}
