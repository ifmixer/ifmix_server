package com.ifmix.core.api.bff.api.customer.ai

import com.ifmix.core.api.dto.ai.AddItemRes
import com.ifmix.core.api.dto.ai.AddCollectionItemRes
import com.ifmix.core.api.dto.ai.CreateScanRes
import com.ifmix.core.api.dto.ai.DeepResearchStatusRes
import com.ifmix.core.api.dto.ai.RemoveItemsRes
import com.ifmix.core.api.dto.ai.ScanRecordListRes
import com.ifmix.core.api.dto.ai.ScanRecordRes
import com.ifmix.core.api.dto.ai.ScanCollectionItemRes
import com.ifmix.core.api.dto.ai.ScanStatusRes
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.common.PageInfo
import com.ifmix.core.api.entity.ai.DeepResearchStatuses
import com.ifmix.core.api.entity.ai.ScanStatuses
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.ai.AiFacade
import com.ifmix.core.api.modules.ai.DeepResearchTaskService
import com.ifmix.core.api.modules.ai.ScanCollectionFacade
import com.ifmix.core.api.modules.ai.ScanTaskService
import com.ifmix.core.api.entity.common.ActorTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.reflect.full.declaredMemberFunctions

/**
 * [AiController] 单测（仿 DemoControllerTest）：真实 [ActionContextFactory]（JWT mock）+
 * mock facade/queryService/globalTx/rateLimiter。
 *
 * 覆盖：13 个 action 各一条成功用例（信封 200000 + reqId 回显 + 事务边界）、
 * NOT_FOUND / 配额 429001 / 限流 429000 / manager 403 / 缺 input 段 400、命名一致性护栏。
 * 限流键序/阈值的细粒度断言见 [AiRateLimitTest]。
 */
class AiControllerTest {

    private val projectId = "ifmix-demo"
    private val reqId = "req-ai-1"
    private val customerId = UUID.randomUUID()
    private val installId = UUID.randomUUID()
    /** factory 解析 meta 后产出的 ctx 形状（mutation withTx 直通原样回传）。 */
    private val ctx = ActionContext(
        projectId = projectId, actorId = customerId, tokenInstallId = installId,
        clientIp = "1.2.3.4", requestId = reqId,
    )
    private val now = Instant.parse("2026-10-06T00:00:00Z")

    private val jwt = mock<AuthJwtService>()
    private val aiService = mock<AiFacade>()
    private val collectionService = mock<ScanCollectionFacade>()
    private val deepResearchTaskService = mock<DeepResearchTaskService>()
    private val scanTaskService = mock<ScanTaskService>()
    private val queryService = mock<AiQueryService>()
    private val globalTx = mock<GlobalTxRunner>()
    private val rateLimiter = mock<RateLimiter>()

    private lateinit var objectMapper: ObjectMapper
    private lateinit var controller: AiController

    @BeforeEach
    fun setUp() {
        objectMapper = JsonMapper.builder().build()
        val ctxFactory = ActionContextFactory(jwt, strict = true)
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = customerId.toString(),
                projectId = projectId,
                actorType = ActorTypes.CUSTOMER,
                tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER,
                installId = installId.toString(),
            ),
        )
        whenever(rateLimiter.check(any(), any(), any())).thenReturn(RateLimitResult.Allowed)
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer {
            ((it.arguments[1]) as (ActionContext) -> Any)(ctx)
        }
        controller = AiController(
            ctxFactory, aiService, collectionService, deepResearchTaskService, scanTaskService,
            queryService, globalTx, rateLimiter, RateLimitProperties(),
        )
    }

    // ===== 公共构造 =====

    private fun validMeta() = RequestMeta(reqId = reqId, projectId = projectId, accessToken = "SECRET-customer")

    private inline fun <reified T : Any> body(input: Map<String, Any?>): ApiRequestBody<T> =
        ApiRequestBody(validMeta(), objectMapper.convertValue(input, T::class.java))

    private fun request() = MockHttpServletRequest().apply {
        addHeader("X-Forwarded-For", "1.2.3.4")
        LogContext.start(this)
    }

    private fun scanTaskCtx() = ScanTaskContext(
        projectId = projectId, customerId = customerId, installId = installId, scanId = UUID.randomUUID(),
        locale = null, country = null, currency = null, images = emptyList(), collected = false,
        promptVersion = "v1", createdAt = now,
    )

    private fun drTaskCtx() = DeepResearchTaskContext(
        projectId = projectId, customerId = customerId,
        deepResearchId = UUID.randomUUID(), scanRecordId = UUID.randomUUID(),
        images = emptyList(), locale = null, country = null, currency = null, promptVersion = "v1",
        createdAt = now,
    )

    private fun detailRes(id: UUID) = ScanRecordRes(
        id = id, createdAt = now.toString(), updatedAt = now.toString(), isPublic = true,
        status = ScanStatuses.SUCCESS, errorCode = null, locale = null, country = null, currency = null,
        userDisplayName = null, userNotes = null, collected = false, hasDeepSearch = false,
        images = emptyList(), basicResult = mapOf("scan_status" to "SUCCESS"), latestDeepResearch = null,
    )

    // ===== 1. 每个 action 一条成功用例 =====

    @Test
    fun `q_ai_scan_getById success returns detail and bypasses tx`() {
        val id = UUID.randomUUID()
        whenever(queryService.findScanById(any(), eq(id))).thenReturn(detailRes(id))
        val resp = controller.findScanById(request(), body(mapOf("id" to id.toString())))
        assertEquals("200000", resp.body!!.code)
        assertEquals(id, resp.body!!.data?.id)
        assertEquals(reqId, resp.body!!.reqId, "Envelope.reqId must echo meta.reqId on success")
        verifyNoInteractions(globalTx, aiService)
        LogContext.clear()
    }

    @Test
    fun `q_ai_scan_list success returns page`() {
        whenever(queryService.findScans(any(), anyOrNull())).thenReturn(Page(emptyList(), PageInfo()))
        val resp = controller.findScans(request(), body(mapOf("findOptions" to mapOf("limit" to 10))))
        assertEquals("200000", resp.body!!.code)
        assertEquals(emptyList<ScanRecordListRes>(), resp.body!!.data?.items)
        verifyNoInteractions(globalTx)
        LogContext.clear()
    }

    @Test
    fun `q_ai_scan_getStatus success wraps lazy-timeout read in tx`() {
        val scanId = UUID.randomUUID()
        whenever(queryService.getScanStatus(any(), eq(scanId))).thenReturn(ScanStatusRes(scanId, ScanStatuses.SUCCESS, null))
        val resp = controller.getScanStatus(request(), body(mapOf("scanId" to scanId.toString())))
        assertEquals("200000", resp.body!!.code)
        assertEquals(ScanStatuses.SUCCESS, resp.body!!.data?.status)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `m_ai_scan_createOne success - task created in tx, submit outside, envelope 200000`() {
        val taskCtx = scanTaskCtx()
        whenever(aiService.createScanTask(any(), any())).thenReturn(taskCtx)
        val resp = controller.createScan(
            request(),
            body(mapOf("images" to listOf(mapOf("imageKey" to "a.jpg", "category" to 0)), "collected" to true)),
        )
        assertEquals("200000", resp.body!!.code)
        assertEquals(taskCtx.scanId, resp.body!!.data?.scanId)
        assertEquals(ScanStatuses.IN_PROGRESS, resp.body!!.data?.status)
        assertNull(resp.body!!.data?.errorCode)
        verify(scanTaskService).submit(taskCtx)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `m_ai_scan_updateOne success reads detail from writer inside tx`() {
        val id = UUID.randomUUID()
        whenever(aiService.updateScan(any(), any())).thenReturn(true)
        whenever(aiService.findById(any(), eq(id))).thenReturn(mock())
        whenever(queryService.toDetailRes(any(), any())).thenReturn(detailRes(id))
        val resp = controller.updateScan(request(), body(mapOf("id" to id.toString(), "set" to mapOf("collected" to true))))
        assertEquals("200000", resp.body!!.code)
        assertEquals(true, resp.body!!.data?.success)
        assertEquals(id, resp.body!!.data?.scanRecord?.id)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `m_ai_scan_updateMany success returns updated count`() {
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        whenever(aiService.batchUpdateScan(any(), any())).thenReturn(2)
        val resp = controller.batchUpdateScan(
            request(),
            body(mapOf("ids" to ids.map { it.toString() }, "set" to mapOf("collected" to true))),
        )
        assertEquals("200000", resp.body!!.code)
        assertEquals(2, resp.body!!.data?.updatedCount)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `m_ai_scan_deleteOne success`() {
        val id = UUID.randomUUID()
        whenever(aiService.deleteScan(any(), eq(id))).thenReturn(true)
        val resp = controller.deleteScan(request(), body(mapOf("id" to id.toString())))
        assertEquals("200000", resp.body!!.code)
        assertEquals(true, resp.body!!.data?.success)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `m_ai_deepResearch_run success - task created in tx, submit outside`() {
        val taskCtx = drTaskCtx()
        whenever(aiService.createDeepResearchTask(any(), any())).thenReturn(taskCtx)
        val resp = controller.runDeepResearch(
            request(),
            body(mapOf("scanRecordId" to UUID.randomUUID().toString(), "images" to listOf(mapOf("imageKey" to "a.jpg")))),
        )
        assertEquals("200000", resp.body!!.code)
        assertEquals(taskCtx.deepResearchId, resp.body!!.data?.deepResearchId)
        assertEquals(DeepResearchStatuses.IN_PROGRESS, resp.body!!.data?.status)
        verify(deepResearchTaskService).submit(taskCtx)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `q_ai_deepResearch_getStatus success wraps lazy-timeout read in tx`() {
        val drId = UUID.randomUUID()
        whenever(queryService.getDeepResearchStatus(any(), eq(drId)))
            .thenReturn(DeepResearchStatusRes(drId, DeepResearchStatuses.FAILED, "TIMEOUT"))
        val resp = controller.getDeepResearchStatus(request(), body(mapOf("deepResearchId" to drId.toString())))
        assertEquals("200000", resp.body!!.code)
        assertEquals("TIMEOUT", resp.body!!.data?.errorCode)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `q_ai_collection_getDefault success bypasses tx`() {
        val id = UUID.randomUUID()
        whenever(queryService.getDefaultCollection(any()))
            .thenReturn(com.ifmix.core.api.dto.ai.ScanCollectionRes(id, true, now.toString()))
        val resp = controller.getDefaultCollection(request(), body(emptyMap()))
        assertEquals("200000", resp.body!!.code)
        assertEquals(id, resp.body!!.data?.id)
        assertEquals(now.toString(), resp.body!!.data?.createdAt)
        verifyNoInteractions(globalTx, collectionService)
        LogContext.clear()
    }

    @Test
    fun `m_ai_collectionItem_add success keeps historical collectionId=item id semantics`() {
        val itemId = UUID.randomUUID()
        whenever(collectionService.addItem(any(), any())).thenReturn(AddItemRes(id = itemId))
        val resp = controller.addCollectionItem(request(), body(mapOf("scanRecordId" to UUID.randomUUID().toString())))
        assertEquals("200000", resp.body!!.code)
        val data = resp.body!!.data!!
        assertEquals(itemId, data.collectionId, "旧 GraphQL 语义：collectionId 实际承载 item id")
        assertEquals(false, data.alreadyExists)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `m_ai_collectionItem_removeMany success returns removed count`() {
        whenever(collectionService.removeItems(any(), any())).thenReturn(RemoveItemsRes(removed = 3))
        val resp = controller.removeCollectionItems(
            request(),
            body(mapOf("scanRecordIds" to listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).map { it.toString() })),
        )
        assertEquals("200000", resp.body!!.code)
        assertEquals(3, resp.body!!.data?.removedCount)
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `q_ai_collectionItem_list success returns assembled page`() {
        whenever(queryService.findCollectionItems(any(), anyOrNull())).thenReturn(Page(emptyList(), PageInfo()))
        val resp = controller.findCollectionItems(request(), body(mapOf("limit" to 20)))
        assertEquals("200000", resp.body!!.code)
        assertEquals(emptyList<ScanCollectionItemRes>(), resp.body!!.data?.items)
        verifyNoInteractions(globalTx, collectionService)
        LogContext.clear()
    }

    // ===== 2. 错误语义 =====

    @Test
    fun `q_ai_scan_getById miss throws NOT_FOUND 404000`() {
        val id = UUID.randomUUID()
        whenever(queryService.findScanById(any(), eq(id))).thenReturn(null)
        val ex = assertThrows(ApiError::class.java) { controller.findScanById(request(), body(mapOf("id" to id.toString()))) }
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode)
        assertEquals("404000", ex.errorCode.externalCode)
        LogContext.clear()
    }

    @Test
    fun `scan create quota exhausted maps to 429001 QUOTA_EXCEEDED`() {
        whenever(aiService.createScanTask(any(), any())).thenThrow(
            ApiError(ErrorCode.QUOTA_EXCEEDED, "scan quota exhausted"),
        )
        val ex = assertThrows(ApiError::class.java) {
            controller.createScan(request(), body(mapOf("images" to listOf(mapOf("imageKey" to "a.jpg")))))
        }
        assertEquals(ErrorCode.QUOTA_EXCEEDED, ex.errorCode)
        assertEquals("429001", ex.errorCode.externalCode)
        LogContext.clear()
    }

    @Test
    fun `scan rate limited maps to 429000 with retryAfterSec`() {
        whenever(rateLimiter.check(any(), any(), any())).thenReturn(RateLimitResult.Limited(45))
        val ex = assertThrows(ApiError::class.java) {
            controller.createScan(request(), body(mapOf("images" to listOf(mapOf("imageKey" to "a.jpg")))))
        }
        assertEquals(ErrorCode.RATE_LIMITED, ex.errorCode)
        assertEquals("429000", ex.errorCode.externalCode)
        assertEquals(45L, ex.retryAfterSec)
        verifyNoInteractions(aiService)
        LogContext.clear()
    }

    @Test
    fun `manager token maps to 403000 FORBIDDEN at ctx factory`() {
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = customerId.toString(), projectId = projectId,
                actorType = ActorTypes.MANAGER, tokenType = AuthJwtService.TOKEN_TYPE_MANAGER,
            ),
        )
        val ex = assertThrows(ApiError::class.java) {
            controller.findScanById(request(), body(mapOf("id" to UUID.randomUUID().toString())))
        }
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode)
        assertEquals("403000", ex.errorCode.externalCode)
        LogContext.clear()
    }

    @Test
    fun `missing input segment fails with invalid request (400 语义)`() {
        val ex = assertThrows(ApiError::class.java) {
            controller.findScanById(request(), ApiRequestBody(validMeta(), null))
        }
        assertEquals(ErrorCode.INVALID_REQUEST, ex.errorCode)
        LogContext.clear()
    }

    @Test
    fun `invalid unset field maps to 400 400000 via global handler`() {
        val handler = com.ifmix.core.api.infra.http.GlobalExceptionHandler(exposeErrors = true)
        val ex = run {
            try {
                com.ifmix.core.api.dto.ai.requireScanUnsetFields(listOf("NOT_A_FIELD")); throw AssertionError("expected ApiError")
            } catch (e: ApiError) { e }
        }
        assertEquals(ErrorCode.INVALID_REQUEST, ex.errorCode)
        val resp = handler.handleApiError(ex, request())
        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertEquals("400000", (resp.body as com.ifmix.core.api.infra.http.Envelope<*>).code)
        LogContext.clear()
    }

    // ===== 3. 命名一致性护栏（实施单 §1.3：四段格式 + module 段 + action 在 rpc-rollout-client §1 表内）=====

    @Test
    fun `routes one-to-one with controller companion constants`() {
        // rpc-rollout-client.md §1 R3 新名总表
        val expected = setOf(
            "m_ai_scan_createOne", "m_ai_scan_updateOne", "m_ai_scan_deleteOne", "m_ai_scan_updateMany",
            "q_ai_scan_list", "q_ai_scan_getById", "q_ai_scan_getStatus",
            "m_ai_deepResearch_run", "q_ai_deepResearch_getStatus",
            "q_ai_collection_getDefault", "m_ai_collectionItem_add", "m_ai_collectionItem_removeMany",
            "q_ai_collectionItem_list",
        )

        val postings = AiController::class.declaredMemberFunctions
            .filter { it.annotations.any { a -> a is org.springframework.web.bind.annotation.PostMapping } }
        assertEquals(13, postings.size)
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
            assertEquals("ai", segs[1], "module segment must be ai: " + n)
        }
        // companion 常量与路由 path 同源
        assertEquals("q_ai_scan_getById", AiController.SCAN_GET_BY_ID)
        assertEquals("m_ai_scan_updateMany", AiController.SCAN_UPDATE_MANY)
    }
}
