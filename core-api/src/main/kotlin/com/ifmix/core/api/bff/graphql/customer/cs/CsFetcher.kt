package com.ifmix.core.api.bff.graphql.customer.cs

import com.ifmix.core.api.generated.types.SubmitFeedbackInput
import com.ifmix.core.api.generated.types.SubmitFeedbackResult
import com.ifmix.core.api.generated.types.CreateSupportRequestInput
import com.ifmix.core.api.generated.types.CreateSupportRequestResult
import com.ifmix.core.api.generated.types.ListSupportRequestsInput
import com.ifmix.core.api.generated.types.MediaInput
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.cs.CreateSupportRequestReq
import com.ifmix.core.api.dto.cs.ListSupportRequestsReq
import com.ifmix.core.api.entity.common.MediaRef
import com.ifmix.core.api.entity.cs.SupportRequest
import com.ifmix.core.api.infra.graphql.OperationContextProvider
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.dto.cs.SubmitFeedbackReq
import com.ifmix.core.api.modules.cs.CsFacade
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
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

    @DgsMutation(field = "m_cs_createSupportRequest")
    fun createSupportRequest(dfe: DgsDataFetchingEnvironment, @InputArgument input: CreateSupportRequestInput): CreateSupportRequestResult {
        // 需登录：ctxProvider 默认 requireActorType=customer，未登录/非 customer 直接抛。
        val ctx = ctxProvider.fromDfe(dfe)
        val id = globalTx.withTx(ctx) { txCtx -> csService.createSupportRequest(txCtx, input.toReq()) }
        return CreateSupportRequestResult(id = id)
    }

    @DgsQuery(field = "q_cs_mySupportRequestById")
    fun mySupportRequestById(dfe: DgsDataFetchingEnvironment, @InputArgument id: UUID): SupportRequest {
        val ctx = ctxProvider.fromDfe(dfe)
        return csService.findMySupportRequestById(ctx, id)
    }

    @DgsQuery(field = "q_cs_mySupportRequests")
    fun mySupportRequests(dfe: DgsDataFetchingEnvironment, @InputArgument input: ListSupportRequestsInput?): Page<SupportRequest> {
        val ctx = ctxProvider.fromDfe(dfe)
        val req = input?.let { ListSupportRequestsReq(cursor = it.cursor, limit = it.limit) }
        return csService.findMySupportRequests(ctx, req)
    }
}

private fun MediaInput.toRef() = MediaRef(key = key, type = type, category = category)

private fun CreateSupportRequestInput.toReq() = CreateSupportRequestReq(
    title = title,
    message = message,
    category = category,
    email = email,
    phone = phone,
    attachments = attachments?.map { it.toRef() },
)

private fun SubmitFeedbackInput.toReq() = SubmitFeedbackReq(
    topic = topic,
    reasons = reasons,
    comment = comment,
    email = email,
    phone = phone,
    scanRecordId = scanRecordId,
    spm = spm,
)
