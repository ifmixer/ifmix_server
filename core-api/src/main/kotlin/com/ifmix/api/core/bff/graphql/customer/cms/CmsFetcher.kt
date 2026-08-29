package com.ifmix.api.core.bff.graphql.customer.cms

import com.ifmix.api.core.generated.types.SubmitFeedbackInput
import com.ifmix.api.core.generated.types.SubmitFeedbackResult
import com.ifmix.api.core.infra.graphql.OperationContextProvider
import com.ifmix.api.core.infra.tx.GlobalTxRunner
import com.ifmix.api.core.dto.cms.SubmitFeedbackReq
import com.ifmix.api.core.modules.cms.CmsFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument
import java.util.UUID

/**
 * CMS GraphQL DataFetcher。
 * Feedback 是 append-only，无需缓存淘汰。
 */
@DgsComponent
class CmsFetcher(
    private val cmsService: CmsFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsMutation(field = "m_cms_submitFeedback")
    fun submitFeedback(dfe: DgsDataFetchingEnvironment, @InputArgument input: SubmitFeedbackInput): SubmitFeedbackResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val id = globalTx.withTx(ctx) { txCtx -> cmsService.submit(txCtx, input.toReq()) }
        return SubmitFeedbackResult(id = id)
    }
}

private fun SubmitFeedbackInput.toReq() = SubmitFeedbackReq(
    category = category,
    comment = comment,
    scanRecordId = scanRecordId,
    spm = spm,
)
