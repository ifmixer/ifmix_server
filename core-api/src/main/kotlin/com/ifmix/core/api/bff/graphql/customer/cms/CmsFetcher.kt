package com.ifmix.core.api.bff.graphql.customer.cms

import com.ifmix.core.api.generated.types.SubmitFeedbackInput
import com.ifmix.core.api.generated.types.SubmitFeedbackResult
import com.ifmix.core.api.infra.graphql.OperationContextProvider
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.dto.cms.SubmitFeedbackReq
import com.ifmix.core.api.modules.cms.CmsFacade
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
