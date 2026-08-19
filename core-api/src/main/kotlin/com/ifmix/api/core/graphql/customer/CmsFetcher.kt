package com.ifmix.api.core.graphql.customer

import com.ifmix.api.core.common.http.OperationContext
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.cms.CmsFacade
import com.ifmix.api.core.graphql.generated.types.SubmitFeedbackInput
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext

@DgsComponent
class CmsFetcher(
    private val cmsFacade: CmsFacade,
) {
    @DgsMutation(field = "mutation_cms_submitFeedback")
    fun submitFeedback(
        @InputArgument input: SubmitFeedbackInput,
        dfe: DgsDataFetchingEnvironment,
    ): String {
        val reqCtx = DgsContext.getCustomContext<RequestContext>(dfe)
        val opCtx = OperationContext(
            req = reqCtx,
            opName = "cms_submitFeedback",
            isMutation = true,
        )
        val id = cmsFacade.submitFeedback(opCtx, input)
        return id.toHexString()
    }
}
