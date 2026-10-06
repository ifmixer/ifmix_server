package com.ifmix.core.api.bff.api.customer.auth

import com.ifmix.core.api.dto.customer.CreateAnonymousRes
import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.entity.customer.Customer
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.VerifiedToken
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ActionContextFactory
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ApiRequestBody
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.LogContext
import com.ifmix.core.api.infra.http.NoInput
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.AuthFacade
import com.ifmix.core.api.modules.auth.handler.CreateAnonymousRes as HandlerCreateAnonymousRes
import com.ifmix.core.api.modules.customer.CustomerFacade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.reflect.full.declaredMemberFunctions

/**
 * [CustomerController] 单测：限流部分整体承接原 CustomerFetcherRateLimitTest（attest 规格 §4.6 + §7）：
 * - 有可信 iid（tokenInstallId）：install 层（5/install/UTC 天）→ IP 层（100/60s + 1000/天）；
 *   install 层拒绝不碰 IP 计数器；IP 层拒绝时 install 额度已扣、不退；install 层 key 只含 iid。
 * - 无 token iid → 401000（进事务前、任何计数器之前；RPC 路径 legacyInstallId 恒为 null）。
 * - key 按 projectId 隔离；数值都从 RateLimitProperties 读取。
 * ctxFactory 为真实 [ActionContextFactory]（JWT mock 返回 install token / customer token）。
 */
class CustomerControllerTest {

    private val pid = "p1x"
    private val ip = "1.2.3.4"
    private val iid = UUID.randomUUID()
    private val reqId = "req-cust-1"

    private val jwt = mock<AuthJwtService>()
    private val authFacade = mock<AuthFacade>()
    private val customerFacade = mock<CustomerFacade>()
    private val globalTx = mock<GlobalTxRunner>()
    private val rateLimiter = mock<RateLimiter>()

    private lateinit var controller: CustomerController
    private lateinit var objectMapper: ObjectMapper

    private val installDayKey = "ratelimit:$pid:anonymous:install:day:$iid"
    private val ipMinKey = "ratelimit:$pid:anonymous:ip:min:$ip"
    private val ipDayKey = "ratelimit:$pid:anonymous:ip:day:$ip"

    @BeforeEach
    fun setUp() {
        objectMapper = JsonMapper.builder().build()
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = null,
                projectId = pid,
                actorType = ActorTypes.CUSTOMER,
                tokenType = AuthJwtService.TOKEN_TYPE_INSTALL,
                installId = iid.toString(),
            ),
        )
        whenever(rateLimiter.check(any(), any(), any())).thenReturn(RateLimitResult.Allowed)
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer {
            ((it.arguments[1]) as (ActionContext) -> Any)(it.arguments[0] as ActionContext)
        }
        controller = CustomerController(
            ActionContextFactory(jwt, strict = true), authFacade, customerFacade, globalTx, rateLimiter, RateLimitProperties(),
        )
    }

    /** install token（type=5，带可信 iid）+ projectId 的合法 meta。 */
    private fun installMeta() = RequestMeta(reqId = reqId, projectId = pid, accessToken = "SECRET-install")

    private fun request() = MockHttpServletRequest().apply {
        LogContext.start(this)
        addHeader("X-Forwarded-For", ip)
    }

    private fun call() = controller.createAnonymousCustomer(request(), ApiRequestBody<NoInput>(installMeta()))

    private fun stubHappyPath(): UUID {
        val customerId = UUID.randomUUID()
        whenever(authFacade.createAnonymousCustomer(any())).thenReturn(
            HandlerCreateAnonymousRes(customerId, "a", "r", Instant.parse("2027-10-06T00:00:00Z"), 3600L),
        )
        whenever(customerFacade.findById(any(), eq(customerId))).thenReturn(
            Customer {
                id = customerId
                projectId = pid
                anonymous = true
            },
        )
        return customerId
    }

    // ===== 成功用例 =====

    @Test
    fun `success returns envelope with customer view and echoes reqId`() {
        val customerId = stubHappyPath()
        val resp = call()

        assertEquals("200000", resp.body!!.code)
        assertEquals(customerId, resp.body!!.data?.customerId)
        assertEquals(customerId, resp.body!!.data?.customer?.id)
        assertEquals(true, resp.body!!.data?.customer?.anonymous)
        assertEquals("2027-10-06T00:00:00Z", resp.body!!.data?.refreshExpiresAt)
        assertEquals(3600, resp.body!!.data?.expiresIn)
        assertEquals(reqId, resp.body!!.reqId, "Envelope.reqId must echo meta.reqId on success")
        verify(globalTx).withTx<Any>(any(), any())
        LogContext.clear()
    }

    // ===== 限流顺序与语义（承接原 CustomerFetcherRateLimitTest）=====

    @Test
    fun `with iid order is install day then ip min then ip day`() {
        stubHappyPath()
        call()
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(installDayKey), eq(5))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(ipMinKey), eq(100))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(ipDayKey), eq(1000))
        LogContext.clear()
    }

    @Test
    fun `with iid install day limited rejects without touching IP counters`() {
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(installDayKey), eq(5))).thenReturn(RateLimitResult.Limited(59))
        val e = assertThrows<ApiError> { call() }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(59L)
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(ipMinKey), any())
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(ipDayKey), any())
        LogContext.clear()
    }

    @Test
    fun `with iid ip min limited means install counter was already deducted no refund`() {
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(ipMinKey), eq(100))).thenReturn(RateLimitResult.Limited(30))
        val e = assertThrows<ApiError> { call() }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(installDayKey), eq(5)) // install 层已 +1，不退
        LogContext.clear()
    }

    @Test
    fun `with iid ip day limited rejects with retryAfterSec`() {
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(ipDayKey), eq(1000))).thenReturn(RateLimitResult.Limited(7200))
        val e = assertThrows<ApiError> { call() }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(7200L)
        LogContext.clear()
    }

    @Test
    fun `without trusted iid to 401000 before any counter is touched`() {
        // customer token 无 iid → mustGetTokenInstallId 抛 UNAUTHORIZED（进限流与事务之前）
        whenever(jwt.verify(any())).thenReturn(
            VerifiedToken(
                actorId = UUID.randomUUID().toString(),
                projectId = pid,
                actorType = ActorTypes.CUSTOMER,
                tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER,
                installId = null,
            ),
        )
        val e = assertThrows<ApiError> { call() }
        assertThat(e.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        verify(rateLimiter, never()).check(any(), any(), any())
        verify(globalTx, never()).withTx<Any>(any(), any())
        LogContext.clear()
    }

    @Test
    fun `install layer budget is shared across ips for the same install`() {
        // 同 iid 换 IP：install 层 key 只含 iid（额度不变），IP 层 key 换了
        stubHappyPath()
        val req = MockHttpServletRequest().apply {
            LogContext.start(this)
            addHeader("X-Forwarded-For", "9.9.9.9")
        }
        controller.createAnonymousCustomer(req, ApiRequestBody<NoInput>(installMeta()))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(installDayKey), eq(5))
        verify(rateLimiter).check(eq(Window.MINUTE), eq("ratelimit:$pid:anonymous:ip:min:9.9.9.9"), eq(100))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(ipMinKey), eq(100))
        LogContext.clear()
    }

    // ===== 命名一致性护栏 =====

    @Test
    fun `route matches CustomerSpecs and mutation prefix matches isMutation`() {
        val spec = CustomerSpecs.CREATE_ANONYMOUS
        assertEquals(true, spec.isMutation)
        assertEquals("m_", spec.reqName.take(2))
        assertEquals("auth", spec.reqName.split("_")[1])

        val postings = CustomerController::class.declaredMemberFunctions
            .filter { it.annotations.any { a -> a is org.springframework.web.bind.annotation.PostMapping } }
        assertEquals(1, postings.size)
        val path = postings.single().annotations.filterIsInstance<org.springframework.web.bind.annotation.PostMapping>().single().value.first()
        val specConst = postings.single().annotations.filterIsInstance<io.swagger.v3.oas.annotations.Operation>().single().operationId
        assertEquals(spec.reqName, path)
        assertEquals(spec.reqName, specConst)
    }

    // ===== ActionSpec ↔ 原 fromDfe 实参对照 =====
    // 原 CustomerFetcher: ctxProvider.fromDfe(dfe, requireActorType = null)（requireAppId=true 默认）
    // ↔ actor=INSTALL_OR_CUSTOMER（不要求登录、token 照校验、install/customer token 皆可）、requireProjectId=true。

    @Test
    fun `spec matches original fromDfe arguments`() {
        assertThat(CustomerSpecs.CREATE_ANONYMOUS.actor)
            .isEqualTo(com.ifmix.core.api.infra.http.ActorRequirement.INSTALL_OR_CUSTOMER)
        assertThat(CustomerSpecs.CREATE_ANONYMOUS.requireProjectId).isTrue()
    }
}
