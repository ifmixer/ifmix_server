package com.ifmix.core.api.bff.api.customer.pay

import com.ifmix.core.api.dto.payment.VerifyReq
import com.ifmix.core.api.dto.payment.VerifyRes
import com.ifmix.core.api.dto.payment.SubscriptionState
import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.modules.pay.PaymentFacade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.reflect.full.declaredMemberFunctions

/**
 * [PayController] 单测：仿 DemoControllerTest 模式（真实 [ActionContextFactory] + mock JWT +
 * MockHttpServletRequest；facade mock）。无 GlobalTxRunner——原 fetcher 未包事务。
 */
class PayControllerTest {

    private val projectId = "ifmix-app"
    private val reqId = "req-pay-1"

    private val jwt = mock<AuthJwtService>()
    private val paymentService = mock<PaymentFacade>()

    private lateinit var objectMapper: ObjectMapper
    private lateinit var controller: PayController

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
        controller = PayController(ctxFactory, paymentService, objectMapper)
    }

    private fun validMeta() = RequestMeta(reqId = reqId, projectId = projectId, accessToken = "SECRET-customer")

    private fun body(input: Map<String, Any?>): ApiRequestBody =
        ApiRequestBody(validMeta(), objectMapper.convertValue(input, tools.jackson.databind.node.ObjectNode::class.java))

    private fun request() = MockHttpServletRequest().apply { LogContext.start(this) }

    // ===== 成功用例 =====

    @Test
    fun `verifyIapPurchase success maps tier and epoch to ISO expiresAt`() {
        val expiresAtMs = Instant.parse("2027-01-01T00:00:00Z").toEpochMilli()
        whenever(paymentService.verifyIapPurchase(any(), any()))
            .thenReturn(VerifyRes(expiresAt = expiresAtMs, state = SubscriptionState.ACTIVE, productId = "pro_monthly", tier = 20))
        val resp = controller.verifyIapPurchase(
            request(),
            body(mapOf("platform" to 10, "productId" to "pro_monthly", "signedTransaction" to "signed")),
        )

        assertEquals("200000", resp.body!!.code)
        assertEquals(20, resp.body!!.data?.tier)
        assertEquals("2027-01-01T00:00:00Z", resp.body!!.data?.expiresAt)
        assertEquals(reqId, resp.body!!.reqId, "Envelope.reqId must echo meta.reqId on success")
        val captor = argumentCaptor<VerifyReq>()
        verify(paymentService).verifyIapPurchase(any(), captor.capture())
        assertEquals(10, captor.firstValue.platform)
        assertEquals("pro_monthly", captor.firstValue.productId)
        assertEquals("signed", captor.firstValue.signedTransaction)
        LogContext.clear()
    }

    // ===== 关键错误用例 =====

    @Test
    fun `verifyIapPurchase missing required field fails with 400 semantic`() {
        val ex = assertThrows(Exception::class.java) {
            controller.verifyIapPurchase(request(), ApiRequestBody(validMeta(), null))
        }
        // platform/productId 必填缺失 → Jackson InvalidNullException（Spring 边界映射 400000）
        assertTrue(
            ex is tools.jackson.core.JacksonException || ex.message?.contains("Invalid null") == true,
            "got: ${ex::class.qualifiedName} ${ex.message}",
        )
        LogContext.clear()
    }

    @Test
    fun `facade ApiError propagates unchanged`() {
        whenever(paymentService.verifyIapPurchase(any(), any()))
            .thenThrow(ApiError(ErrorCode.IAP_VERIFY_FAILED, "purchase verification failed"))
        val ex = assertThrows(ApiError::class.java) {
            controller.verifyIapPurchase(request(), body(mapOf("platform" to 10, "productId" to "x")))
        }
        assertEquals(ErrorCode.IAP_VERIFY_FAILED, ex.errorCode)
        assertEquals("402000", ex.errorCode.externalCode)
        LogContext.clear()
    }

    // ===== 命名一致性护栏 =====

    @Test
    fun `route matches PaySpecs and mutation prefix matches isMutation`() {
        val spec = PaySpecs.IAP_VERIFY
        assertEquals(true, spec.isMutation)
        assertEquals("m_", spec.reqName.take(2))
        assertEquals("pay", spec.reqName.split("_")[1])

        val postings = PayController::class.declaredMemberFunctions
            .filter { it.annotations.any { a -> a is org.springframework.web.bind.annotation.PostMapping } }
        assertEquals(1, postings.size)
        val path = postings.single().annotations.filterIsInstance<org.springframework.web.bind.annotation.PostMapping>().single().value.first()
        val specConst = postings.single().annotations.filterIsInstance<io.swagger.v3.oas.annotations.Operation>().single().operationId
        assertEquals(spec.reqName, path)
        assertEquals(spec.reqName, specConst)
    }
}
