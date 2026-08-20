package com.ifmix.api.core.modules.cms

import com.ifmix.api.core.dto.common.CreateOneRes
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.tx.TxRunner
import com.ifmix.api.core.dto.cms.SubmitFeedbackReq
import com.ifmix.api.core.modules.cms.handler.FeedbackHandler
import org.springframework.stereotype.Service

@Service
class CmsFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: FeedbackHandler,
    private val tx: TxRunner,
) {
    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): CreateOneRes =
        tx.withTx(mcFactory.forApp(ctx)) { sc ->
            CreateOneRes(id = handler.submit(sc, req))
        }
}
