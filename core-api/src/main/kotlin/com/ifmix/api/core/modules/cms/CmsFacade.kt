package com.ifmix.api.core.modules.cms

import com.ifmix.api.core.common.http.ModuleCtx
import com.ifmix.api.core.common.http.OperationContext
import com.ifmix.api.core.modules.cms.handler.FeedbackEntityHandler
import com.ifmix.api.core.graphql.generated.types.SubmitFeedbackInput
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/** CMS 门面：提交反馈。组合 FeedbackEntityHandler，不继承。 */
@Service
class CmsFacade(
    private val feedbackHandler: FeedbackEntityHandler,
) {
    private fun mc(opCtx: OperationContext) = ModuleCtx.from(opCtx)

    fun submitFeedback(opCtx: OperationContext, input: SubmitFeedbackInput): ObjectId {
        return feedbackHandler.create(mc(opCtx), input)
    }
}
