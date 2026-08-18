package com.ifmix.api.core.bff.graphql.customer.feedback

import com.ifmix.api.core.generated.types.SubmitFeedbackInput
import com.ifmix.api.core.generated.types.SubmitFeedbackPayload
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.dto.feedback.SubmitFeedbackReq
import com.ifmix.api.core.modules.cms.service.CmsFacadeService
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument

/**
 * Feedback GraphQL DataFetcher（jOOQ 版）。
 * Feedback 是 append-only，无需缓存淘汰。
 */
@DgsComponent
class FeedbackFetcher(
    private val feedbackService: CmsFacadeService,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsMutation(field = "mutation_feedback_submitFeedback")
    fun submitFeedback(dfe: DgsDataFetchingEnvironment, @InputArgument input: SubmitFeedbackInput): SubmitFeedbackPayload {
        val ctx = ctxProvider.fromDfe(dfe)
        val id = feedbackService.submit(ctx, input.toReq()).id
        return SubmitFeedbackPayload(id = id)
    }
}

private fun SubmitFeedbackInput.toReq() = SubmitFeedbackReq(
    category = category,
    comment = comment,
    scanRecordId = scanRecordId,
)
