package com.ifmix.core.api.bff.api.customer.cs

import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.common.PageInfo
import com.ifmix.core.api.dto.cs.SubmitFeedbackInput
import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.entity.common.MediaRef
import com.ifmix.core.api.entity.cs.SupportRequest
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.cs.CsFacade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.reflect.full.declaredMemberFunctions

/**
 * [CsController] 单测：仿 DemoControllerTest 模式（真实 [ActionContextFactory] + mock JWT +
 * MockHttpServletRequest；facade mock、globalTx mock 直通）。
 */
class CsControllerTest {

    private val projectId = "ifmix-app"
    private val reqId = "req-cs-1"
    private val ctx = ActionContext(projectId = projectId, requestId = reqId)
    private val now = Instant.parse("2026-10-06T00:00:00Z")

    private val jwt = mock<AuthJwtService>()
    private val csService = mock<CsFacade>()
    private val globalTx = mock<GlobalTxRunner>()

    private lateinit var objectMapper: ObjectMapper
    private lateinit var controller: CsController

    @BeforeEach
    fun setUp() {
        objectMapper = JsonMapper.builder().build()
        val ctxFactory = ActionContextFactory(jwt, strict = true)
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = UUID.randomUUID().toString(),
                projectId = projectId,
                actorType = ActorTypes.CUSTOMER,
                tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER,
            ),
        )
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer {
            ((it.arguments[1]) as (ActionContext) -> Any)(ctx)
        }
        controller = CsController(ctxFactory, csService, globalTx)
    }

    private fun validMeta() = RequestMeta(reqId = reqId, projectId = projectId, accessToken = "SECRET-customer")

    private inline fun <reified T : Any> body(input: Map<String, Any?>?): ApiRequestBody<T> =
        ApiRequestBody(validMeta(), input?.let { objectMapper.convertValue(it, T::class.java) })

    private fun request() = MockHttpServletRequest().apply { LogContext.start(this) }

    private fun entity(id: UUID): SupportRequest {
        val pid = projectId // Jimmer DSL 内裸引用 projectId 会解析到未加载的 draft 属性
        return SupportRequest {
            this.id = id
            this.projectId = pid
            this.title = "t"
            this.message = "m"
            email = null; phone = null
            this.category = 10
            this.status = 10
            this.attachments = listOf(MediaRef(key = "k", type = 10))
            locale = "zh-Hans"; country = "CN"; currency = "CNY"
            installId = null; customerId = UUID.randomUUID()
            appVersion = null; otaVersion = null
            firstRepliedAt = null; lastAgentRepliedAt = null; lastCustomerRepliedAt = null
            resolvedAt = null; closedAt = null
            createdAt = now; updatedAt = now
        }
    }

    // ===== 每个 action 一条成功用例 =====

    @Test
    fun `submitFeedback success wraps facade in tx and echoes reqId`() {
        val id = UUID.randomUUID()
        whenever(csService.submit(any(), any())).thenReturn(id)
        val resp = controller.submitFeedback(request(), body(mapOf("topic" to 10, "reasons" to listOf(10), "section" to "valuation")))

        assertEquals("200000", resp.body!!.code)
        assertEquals(id, resp.body!!.data?.id)
        assertEquals(reqId, resp.body!!.reqId, "Envelope.reqId must echo meta.reqId on success")
        verify(globalTx).withTx<Any>(any(), any())
        val captor = argumentCaptor<SubmitFeedbackInput>()
        // controller 先 input→SubmitFeedbackInput，再 toReq；此处直接断言 facade 收到的 Req
        val reqCaptor = argumentCaptor<com.ifmix.core.api.dto.cs.SubmitFeedbackReq>()
        verify(csService).submit(any(), reqCaptor.capture())
        assertEquals(10, reqCaptor.firstValue.topic)
        assertEquals(listOf(10), reqCaptor.firstValue.reasons)
        // schema 的 section 字段仅保留在 wire（SubmitFeedbackInput），不进 Req——原 CsFetcher.toReq 语义
        LogContext.clear()
    }

    @Test
    fun `createSupportRequest success maps attachments to MediaRef`() {
        val id = UUID.randomUUID()
        whenever(csService.createSupportRequest(any(), any())).thenReturn(id)
        val input = mapOf(
            "title" to "bug", "message" to "m", "category" to 10,
            "attachments" to listOf(mapOf("key" to "k", "type" to 10)),
        )
        val resp = controller.createSupportRequest(request(), body(input))

        assertEquals("200000", resp.body!!.code)
        assertEquals(id, resp.body!!.data?.id)
        assertEquals(reqId, resp.body!!.reqId)
        verify(globalTx).withTx<Any>(any(), any())
        // toReq 在 controller 内联调用；直接断言 Req 内容
        val reqCaptor = argumentCaptor<com.ifmix.core.api.dto.cs.CreateSupportRequestReq>()
        verify(csService).createSupportRequest(any(), reqCaptor.capture())
        assertEquals("bug", reqCaptor.firstValue.title)
        assertEquals(10, reqCaptor.firstValue.category)
        assertEquals(listOf(MediaRef(key = "k", type = 10)), reqCaptor.firstValue.attachments)
        LogContext.clear()
    }

    @Test
    fun `mySupportRequestById success returns res without tx`() {
        val id = UUID.randomUUID()
        whenever(csService.findMySupportRequestById(any(), eq(id))).thenReturn(entity(id))
        val resp = controller.mySupportRequestById(request(), body(mapOf("id" to id.toString())))

        assertEquals("200000", resp.body!!.code)
        assertEquals(id, resp.body!!.data?.id)
        assertEquals("2026-10-06T00:00:00Z", resp.body!!.data?.createdAt)
        assertEquals(reqId, resp.body!!.reqId)
        verifyNoInteractions(globalTx)
        LogContext.clear()
    }

    @Test
    fun `mySupportRequests success maps page items and null input passes null`() {
        val id = UUID.randomUUID()
        whenever(csService.findMySupportRequests(any(), anyOrNull()))
            .thenReturn(Page(listOf(entity(id)), PageInfo(nextCursor = id.toString(), hasMore = true)))
        val resp = controller.mySupportRequests(request(), body(null))

        assertEquals("200000", resp.body!!.code)
        assertEquals(listOf(id), resp.body!!.data?.items?.map { it.id })
        assertEquals(id.toString(), resp.body!!.data?.pageInfo?.nextCursor)
        assertEquals(true, resp.body!!.data?.pageInfo?.hasMore)
        assertEquals(reqId, resp.body!!.reqId)
        verifyNoInteractions(globalTx)
        LogContext.clear()
    }

    // ===== 关键错误用例 =====

    @Test
    fun `mySupportRequestById miss throws NOT_FOUND from facade unchanged`() {
        val id = UUID.randomUUID()
        whenever(csService.findMySupportRequestById(any(), eq(id)))
            .thenThrow(ApiError(ErrorCode.NOT_FOUND, "support request not found"))
        val ex = assertThrows(ApiError::class.java) {
            controller.mySupportRequestById(request(), body(mapOf("id" to id.toString())))
        }
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode)
        assertEquals("404000", ex.errorCode.externalCode)
        LogContext.clear()
    }

    @Test
    fun `submitFeedback missing required field fails with 400 semantic`() {
        val ex = assertThrows(ApiError::class.java) {
            controller.submitFeedback(request(), ApiRequestBody(validMeta(), null))
        }
        assertEquals(ErrorCode.INVALID_REQUEST, ex.errorCode)
        LogContext.clear()
    }

    // ===== 命名一致性护栏 =====

    @Test
    fun `routes one-to-one with controller companion constants`() {
        // rpc-rollout-client.md §1 R1：cs 6 action 里的 4 个
        val expected = setOf(
            "m_cs_feedback_createOne", "m_cs_supportRequest_createOne",
            "q_cs_supportRequest_getById", "q_cs_supportRequest_list",
        )
        val postings = CsController::class.declaredMemberFunctions
            .filter { it.annotations.any { a -> a is org.springframework.web.bind.annotation.PostMapping } }
        assertEquals(4, postings.size)
        val names = postings.map { f ->
            val path = f.annotations.filterIsInstance<org.springframework.web.bind.annotation.PostMapping>().single().value.first()
            val operationId = f.annotations.filterIsInstance<io.swagger.v3.oas.annotations.Operation>().single().operationId
            assertEquals(path, operationId, "path must equal operationId: " + path)
            path
        }
        assertEquals(expected, names.toSet())
        names.forEach { n ->
            val segs = n.split("_")
            assertEquals(4, segs.size, "four-segment format: " + n)
            assertTrue(segs[0] in setOf("q", "m"), "q_/m_ prefix: " + n)
            assertEquals("cs", segs[1], "module segment: " + n)
            assertTrue(segs[2] in setOf("feedback", "supportRequest"), "resource segment: " + n)
        }
        // companion 常量与路由 path 同源
        assertEquals("m_cs_feedback_createOne", CsController.SUBMIT_FEEDBACK)
        assertEquals("q_cs_supportRequest_list", CsController.MY_SUPPORT_REQUESTS)
    }

    // 原「spec matches original fromDfe arguments」对照测试删除：ActionSpec 已撤销，
    // requireActorType 实参映射（全部 CUSTOMER）见 controller 调用点 + 实施单 §1.2 裁决表。
}
