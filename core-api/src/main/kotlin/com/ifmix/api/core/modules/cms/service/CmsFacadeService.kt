package com.ifmix.api.core.modules.cms.service

import com.ifmix.api.core.dto.common.CreateOneRes
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.dto.feedback.SubmitFeedbackReq
import org.springframework.stereotype.Service

@Service
class CmsFacadeService(
    private val internalService: FeedbackInternalService,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): CreateOneRes =
        tx.withTx(svc(ctx)) { sc ->
            CreateOneRes(id = internalService.submit(sc, req))
        }
}
