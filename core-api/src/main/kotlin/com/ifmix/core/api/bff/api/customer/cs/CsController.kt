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
import tools.jackson.databind.ObjectMapper

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
    private val objectMapper: ObjectMapper,
) {

    /** 缺 input 段 → 空 object node（必填字段缺失走 Jackson InvalidNullException → 400 语义）。 */
    private fun <T> input(body: ApiRequestBody, clazz: Class<T>): T =
        objectMapper.convertValue(body.input ?: objectMapper.createObjectNode(), clazz)

    @Operation(operationId = "m_cs_feedback_createOne")
    @PostMapping("m_cs_feedback_createOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submitFeedback(request: HttpServletRequest, @RequestBody body: ApiRequestBody): ResponseEntity<Envelope<SubmitFeedbackRes>> {
        val ctx = ctxFactory.fromRpc(request, CsSpecs.SUBMIT_FEEDBACK, body.meta)
        val input = input(body, SubmitFeedbackInput::class.java)
        val id = globalTx.withTx(ctx) { txCtx -> csService.submit(txCtx, input.toReq()) }
        return ResponseEntity.ok(Envelope.ok(SubmitFeedbackRes(id = id)).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "m_cs_supportRequest_createOne")
    @PostMapping("m_cs_supportRequest_createOne", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createSupportRequest(request: HttpServletRequest, @RequestBody body: ApiRequestBody): ResponseEntity<Envelope<CreateSupportRequestRes>> {
        val ctx = ctxFactory.fromRpc(request, CsSpecs.CREATE_SUPPORT_REQUEST, body.meta)
        val input = input(body, CreateSupportRequestInput::class.java)
        val id = globalTx.withTx(ctx) { txCtx -> csService.createSupportRequest(txCtx, input.toReq()) }
        return ResponseEntity.ok(Envelope.ok(CreateSupportRequestRes(id = id)).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "q_cs_supportRequest_getById")
    @PostMapping("q_cs_supportRequest_getById", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun mySupportRequestById(request: HttpServletRequest, @RequestBody body: ApiRequestBody): ResponseEntity<Envelope<SupportRequestRes>> {
        val ctx = ctxFactory.fromRpc(request, CsSpecs.MY_SUPPORT_REQUEST_BY_ID, body.meta)
        val input = input(body, SupportRequestByIdInput::class.java)
        return ResponseEntity.ok(Envelope.ok(csService.findMySupportRequestById(ctx, input.id).toRes()).copy(reqId = ctx.requestId))
    }

    @Operation(operationId = "q_cs_supportRequest_list")
    @PostMapping("q_cs_supportRequest_list", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun mySupportRequests(request: HttpServletRequest, @RequestBody body: ApiRequestBody): ResponseEntity<Envelope<Page<SupportRequestRes>>> {
        val ctx = ctxFactory.fromRpc(request, CsSpecs.MY_SUPPORT_REQUESTS, body.meta)
        // 原 GraphQL 侧 input 整段可空：缺段/显式 null → null req 透传
        val input = body.input?.takeIf { !it.isNull }?.let { objectMapper.convertValue(it, ListSupportRequestsInput::class.java) }
        val page = csService.findMySupportRequests(ctx, input?.let { ListSupportRequestsReq(cursor = it.cursor, limit = it.limit) })
        return ResponseEntity.ok(
            Envelope.ok(Page(items = page.items.map { it.toRes() }, pageInfo = page.pageInfo)).copy(reqId = ctx.requestId),
        )
    }
}
