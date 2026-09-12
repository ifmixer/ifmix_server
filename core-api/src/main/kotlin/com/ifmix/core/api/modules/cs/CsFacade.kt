package com.ifmix.core.api.modules.cs

import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.dto.cs.SubmitFeedbackReq
import com.ifmix.core.api.dto.cs.CreateSupportRequestReq
import com.ifmix.core.api.dto.cs.ListSupportRequestsReq
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.entity.cs.SupportRequest
import com.ifmix.core.api.modules.cs.handler.FeedbackAggHandler
import com.ifmix.core.api.modules.cs.handler.SupportRequestAggHandler
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CsFacade(
    private val mcFactory: ModuleCtxFactory,
    private val handler: FeedbackAggHandler,
    private val supportRequestHandler: SupportRequestAggHandler,
) {
    fun submit(ctx: OperationContext, req: SubmitFeedbackReq): UUID =
        handler.submit(mcFactory.forProject(ctx), req)

    fun createSupportRequest(ctx: OperationContext, req: CreateSupportRequestReq): UUID =
        supportRequestHandler.create(mcFactory.forProject(ctx), req)

    fun findMySupportRequestById(ctx: OperationContext, id: UUID): SupportRequest =
        supportRequestHandler.findMineById(mcFactory.forProject(ctx), id)

    fun findMySupportRequests(ctx: OperationContext, req: ListSupportRequestsReq?): Page<SupportRequest> =
        supportRequestHandler.findMine(mcFactory.forProject(ctx), req)
}
