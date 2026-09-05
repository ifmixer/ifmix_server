package com.ifmix.core.api.bff.graphql.customer.cs

import com.ifmix.core.api.generated.types.SubmitFeedbackInput
import com.ifmix.core.api.generated.types.SubmitFeedbackResult
import com.ifmix.core.api.infra.graphql.OperationContextProvider
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.dto.cs.SubmitFeedbackReq
import com.ifmix.core.api.modules.cs.CsFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.InputArgument
import java.util.UUID

/**
 * Customer Support (cs) GraphQL DataFetcher。
 * Feedback 是 append-only，无需缓存淘汰。
 */
@DgsComponent
class CsFetcher(
    private val csService: CsFacade,
    private val globalTx: GlobalTxRunner,
    private val ctxProvider: OperationContextProvider,
) {

    @DgsMutation(field = "m_cs_submitFeedback")
    fun submitFeedback(dfe: DgsDataFetchingEnvironment, @InputArgument input: SubmitFeedbackInput): SubmitFeedbackResult {
        val ctx = ctxProvider.fromDfe(dfe)
        val id = globalTx.withTx(ctx) { txCtx -> csService.submit(txCtx, input.toReq()) }
        return SubmitFeedbackResult(id = id)
    }
}

private fun SubmitFeedbackInput.toReq() = SubmitFeedbackReq(
    topic = topic,
    reasons = reasons,
    comment = comment,
    email = email,
    phone = phone,
    scanRecordId = scanRecordId,
    spm = spm,
)
