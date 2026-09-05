package com.ifmix.core.api.modules.cs

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.dto.cs.SubmitFeedbackReq
import com.ifmix.core.api.modules.cs.handler.FeedbackAggHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CsFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: FeedbackAggHandler,
) {
    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): UUID =
        handler.submit(mcFactory.forApp(ctx), req)
}
