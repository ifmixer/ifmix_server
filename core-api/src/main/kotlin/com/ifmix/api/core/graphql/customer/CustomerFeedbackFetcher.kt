package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.feedback.FeedbackCategory
import com.ifmix.api.core.modules.feedback.FeedbackService
import com.ifmix.api.core.modules.feedback.SubmitReq
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext

@DgsComponent
class CustomerFeedbackFetcher(private val feedbackService: FeedbackService) {

    @DgsMutation(field = "feedback_submit")
    fun submitFeedback(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): String {
        val ctx = DgsContext.getCustomContext<RequestContext>(dfe)
        val category = input["category"] as String
        val comment = input["comment"] as? String
        val scanRecordId = input["scanRecordId"] as? String
        val cat = FeedbackCategory.valueOf(category.uppercase())
        val req = SubmitReq(category = cat, note = comment, scanRecordId = scanRecordId)
        return feedbackService.submit(ctx, req)
    }
}
