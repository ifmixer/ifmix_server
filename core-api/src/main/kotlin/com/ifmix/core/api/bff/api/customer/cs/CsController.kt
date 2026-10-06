package com.ifmix.core.api.bff.api.customer.cs

import com.ifmix.core.api.bff.api.customer.demo.DemoController
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.cs.CreateSupportRequestInput
import com.ifmix.core.api.dto.cs.CreateSupportRequestRes
import com.ifmix.core.api.dto.cs.ListSupportRequestsInput
import com.ifmix.core.api.dto.cs.ListSupportRequestsReq
import com.ifmix.core.api.dto.cs.SubmitFeedbackInput
import com.ifmix.core.api.dto.cs.SubmitFeedbackRes
import com.ifmix.core.api.dto.cs.SupportRequestByIdInput
import com.ifmix.core.api.dto.cs.SupportRequestRes
import com.ifmix.core.api.dto.cs.toReq
import com.ifmix.core.api.dto.cs.toRes
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.Envelope
import com.ifmix.core.api.infra.http.requireInput
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.cs.CsFacade
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * cs 模块 API controller：4 个 `POST /api/customer/core/{actionName}`（rollout-server §4 M1）。
 *
 * 模式与 [DemoController] 完全一致：[ActionContextFactory.fromRpc] 构造 ctx；两个 mutation 包
 * [GlobalTxRunner.withTx]（对照原 CsFetcher），两个 query 直调 [CsFacade]。
 */
@RestController
@RequestMapping("/api/customer/core", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "CS API", description = "customer support 模块 API（GraphQL 去化 M1）")
class CsController(
    private val ctxFactory: ActionContextFactory,
    private val csService: CsFacade,
    private val globalTx: GlobalTxRunner,
) {

    companion object {
        // cs 模块 action 常量（原 CsSpecs 机械搬移；全部 CUSTOMER + requireProjectId=true，
        // 对照原 CsFetcher `fromDfe(dfe)` 全默认实参）。
        const val SUBMIT_FEEDBACK = "m_cs_feedback_createOne"
        const val CREATE_SUPPORT_REQUEST = "m_cs_supportRequest_createOne"
        const val MY_SUPPORT_REQUEST_BY_ID = "q_cs_supportRequest_getById"
        const val MY_SUPPORT_REQUESTS = "q_cs_supportRequest_list"
    }

    @Operation(operationId = SUBMIT_FEEDBACK)
    @PostMapping(SUBMIT_FEEDBACK, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submitFeedback(request: HttpServletRequest, @RequestBody body: ApiRequestBody<SubmitFeedbackInput>): ResponseEntity<Envelope<SubmitFeedbackRes>> {
        val ctx = ctxFactory.fromRpc(request, SUBMIT_FEEDBACK, isMutation = true, body = body)
        val input = body.requireInput()
        val id = globalTx.withTx(ctx) { txCtx -> csService.submit(txCtx, input.toReq()) }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, SubmitFeedbackRes(id = id)))
    }

    @Operation(operationId = CREATE_SUPPORT_REQUEST)
    @PostMapping(CREATE_SUPPORT_REQUEST, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createSupportRequest(request: HttpServletRequest, @RequestBody body: ApiRequestBody<CreateSupportRequestInput>): ResponseEntity<Envelope<CreateSupportRequestRes>> {
        val ctx = ctxFactory.fromRpc(request, CREATE_SUPPORT_REQUEST, isMutation = true, body = body)
        val input = body.requireInput()
        val id = globalTx.withTx(ctx) { txCtx -> csService.createSupportRequest(txCtx, input.toReq()) }
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, CreateSupportRequestRes(id = id)))
    }

    @Operation(operationId = MY_SUPPORT_REQUEST_BY_ID)
    @PostMapping(MY_SUPPORT_REQUEST_BY_ID, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun mySupportRequestById(request: HttpServletRequest, @RequestBody body: ApiRequestBody<SupportRequestByIdInput>): ResponseEntity<Envelope<SupportRequestRes>> {
        val ctx = ctxFactory.fromRpc(request, MY_SUPPORT_REQUEST_BY_ID, isMutation = false, body = body)
        val input = body.requireInput()
        return ResponseEntity.ok(Envelope.ok(ctx.requestId, csService.findMySupportRequestById(ctx, input.id).toRes()))
    }

    @Operation(operationId = MY_SUPPORT_REQUESTS)
    @PostMapping(MY_SUPPORT_REQUESTS, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun mySupportRequests(request: HttpServletRequest, @RequestBody body: ApiRequestBody<ListSupportRequestsInput>): ResponseEntity<Envelope<Page<SupportRequestRes>>> {
        val ctx = ctxFactory.fromRpc(request, MY_SUPPORT_REQUESTS, isMutation = false, body = body)
        // 原 GraphQL 侧 input 整段可空：缺段/显式 null → null req 透传（此处不用 requireInput）
        val input = body.input
        val page = csService.findMySupportRequests(ctx, input?.let { ListSupportRequestsReq(cursor = it.cursor, limit = it.limit) })
        return ResponseEntity.ok(
            Envelope.ok(ctx.requestId, Page(items = page.items.map { it.toRes() }, pageInfo = page.pageInfo)),
        )
    }
}
