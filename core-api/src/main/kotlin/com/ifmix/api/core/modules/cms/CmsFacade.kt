package com.ifmix.api.core.modules.cms

import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.dto.cms.SubmitFeedbackReq
import com.ifmix.api.core.modules.cms.handler.FeedbackAggHandler
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
