package com.ifmix.core.api.modules.cms

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.dto.cms.SubmitFeedbackReq
import com.ifmix.core.api.modules.cms.handler.FeedbackAggHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CmsFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: FeedbackAggHandler,
) {
    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): UUID =
        handler.submit(mcFactory.forApp(ctx), req)
}
