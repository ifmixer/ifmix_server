package com.ifmix.core.api.bff.api.customer.ai

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
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.ai.AiFacade
import com.ifmix.core.api.modules.ai.DeepResearchTaskService
import com.ifmix.core.api.modules.ai.ScanCollectionFacade
import com.ifmix.core.api.modules.ai.ScanTaskService
import com.ifmix.core.api.entity.common.ActorTypes
import org.assertj.core.api.Assertions.assertThat
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

/**
 * scan / DeepResearch 下游 install 层限流（attest 规格 §4.6「下游接口的 install 层」+ §7「下游两层数值」），
 * 自 AiFetcherRateLimitTest 移植到 RPC controller（限流逻辑原样搬运，仅入口从 DFE 换成 meta+factory）：
 * - 有 iid（customer token 的 iid）：install 层（5/min + 100/天）→ IP 层（100/min + 1000/天）；
 *   install 层拒绝不碰 IP 计数器；IP 拒绝 install 已扣不退；scan 与 DR 分别计数。
 * - 无 iid（legacy fallback 期）：legacy 独立计数器（scan 5/min+500/天、DR 3/min+300/天），不占大额 IP 计数器。
 * 数值从 RateLimitProperties 读取（默认值 = 规格 §4.6 yaml）。
 */
class AiRateLimitTest {

    private val pid = "p12"
    private val ip = "1.2.3.4"
    private val iid = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private val scanInstallMinKey = "ratelimit:$pid:scan:install:min:$iid"
    private val scanInstallDayKey = "ratelimit:$pid:scan:install:day:$iid"
    private val scanIpMinKey = "ratelimit:$pid:scan:ip:min:$ip"
    private val scanIpDayKey = "ratelimit:$pid:scan:ip:day:$ip"
    private val scanLegacyMinKey = "ratelimit:$pid:scan:legacy:ip:min:$ip"
    private val scanLegacyDayKey = "ratelimit:$pid:scan:legacy:ip:day:$ip"
    private val drInstallMinKey = "ratelimit:$pid:deep-research:install:min:$iid"
    private val drInstallDayKey = "ratelimit:$pid:deep-research:install:day:$iid"
    private val drIpMinKey = "ratelimit:$pid:deep-research:ip:min:$ip"
    private val drIpDayKey = "ratelimit:$pid:deep-research:ip:day:$ip"
    private val drLegacyMinKey = "ratelimit:$pid:deep-research:legacy:ip:min:$ip"
    private val drLegacyDayKey = "ratelimit:$pid:deep-research:legacy:ip:day:$ip"

    private val jwt = mock<AuthJwtService>()
    private val aiService = mock<AiFacade>()
    private val scanTaskService = mock<ScanTaskService>()
    private val globalTx = mock<GlobalTxRunner>()
    private val rateLimiter = mock<RateLimiter>()

    private lateinit var objectMapper: ObjectMapper
    private lateinit var controller: AiController

    /** 是否携带 iid claim（false = legacy 分支）。 */
    private var withIid = true

    @BeforeEach
    fun setUp() {
        objectMapper = JsonMapper.builder().build()
        whenever(rateLimiter.check(any(), any(), any())).thenReturn(RateLimitResult.Allowed)
        val ctxFactory = ActionContextFactory(jwt, strict = true)
        whenever(jwt.verify(any())).thenAnswer {
            VerifiedToken(
                actorId = customerId.toString(),
                projectId = pid,
                actorType = ActorTypes.CUSTOMER,
                tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER,
                installId = if (withIid) iid.toString() else null,
            )
        }
        val scanCtx = ScanTaskContext(
            projectId = pid, customerId = customerId, installId = iid, scanId = UUID.randomUUID(),
            locale = null, country = null, currency = null, images = emptyList(), collected = false,
            promptVersion = "v1", createdAt = Instant.now(),
        )
        whenever(aiService.createScanTask(any(), any())).thenAnswer { scanCtx }
        whenever(aiService.createDeepResearchTask(any(), any())).thenAnswer {
            DeepResearchTaskContext(
                projectId = pid, customerId = customerId,
                deepResearchId = UUID.randomUUID(), scanRecordId = UUID.randomUUID(),
                images = emptyList(), locale = null, country = null, currency = null, promptVersion = "v1",
                createdAt = Instant.now(),
            )
        }
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer {
            val c = it.arguments[0] as ActionContext
            ((it.arguments[1]) as (ActionContext) -> Any)(c)
        }
        controller = AiController(
            ctxFactory, aiService, mock<ScanCollectionFacade>(), mock<DeepResearchTaskService>(),
            scanTaskService, mock<AiQueryService>(), globalTx, rateLimiter, RateLimitProperties(),
        )
    }

    private fun meta() = RequestMeta(reqId = "req-rl", projectId = pid, accessToken = "SECRET")

    private fun request() = MockHttpServletRequest().apply {
        addHeader("X-Forwarded-For", ip)
        LogContext.start(this)
    }

    private fun scanBody() = ApiRequestBody(
        meta(),
        objectMapper.convertValue(mapOf("images" to emptyList<Any?>()), com.ifmix.core.api.dto.ai.NewScanInput::class.java),
    )

    private fun drBody() = ApiRequestBody(
        meta(),
        objectMapper.convertValue(
            mapOf("scanRecordId" to UUID.randomUUID().toString(), "images" to emptyList<Any?>()),
            com.ifmix.core.api.dto.ai.RunDeepResearchInput::class.java,
        ),
    )

    // ===== scan：install 层 → IP 层 顺序与数值 =====

    @Test
    fun `scan with iid order is install min install day ip min ip day`() {
        controller.createScan(request(), scanBody())
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanInstallMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(scanInstallDayKey), eq(100))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanIpMinKey), eq(100))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(scanIpDayKey), eq(1000))
    }

    @Test
    fun `scan with iid install minute limited rejects without touching IP counters`() {
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(scanInstallMinKey), eq(5))).thenReturn(RateLimitResult.Limited(45))
        val e = assertThrows<ApiError> { controller.createScan(request(), scanBody()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(45L)
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanIpMinKey), any())
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(scanIpDayKey), any())
    }

    @Test
    fun `scan with iid install day 101st request rejects 100 install limit`() {
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(scanInstallDayKey), eq(100))).thenReturn(RateLimitResult.Limited(3600))
        val e = assertThrows<ApiError> { controller.createScan(request(), scanBody()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
    }

    @Test
    fun `scan with iid ip limited means install counters already deducted`() {
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(scanIpMinKey), eq(100))).thenReturn(RateLimitResult.Limited(30))
        assertThrows<ApiError> { controller.createScan(request(), scanBody()) }
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanInstallMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(scanInstallDayKey), eq(100))
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(scanIpDayKey), any())
    }

    @Test
    fun `scan legacy fallback only legacy counters used with strict thresholds`() {
        withIid = false
        controller.createScan(request(), scanBody())
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanLegacyMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(scanLegacyDayKey), eq(500))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanInstallMinKey), any())
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanIpMinKey), any())
    }

    @Test
    fun `scan legacy minute limited rejects with retryAfterSec`() {
        withIid = false
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(scanLegacyMinKey), eq(5))).thenReturn(RateLimitResult.Limited(20))
        val e = assertThrows<ApiError> { controller.createScan(request(), scanBody()) }
        assertThat(e.retryAfterSec).isEqualTo(20L)
    }

    @Test
    fun `scan without token iid uses legacy counters (401 comes from mustGetTokenInstallId in the service layer, e2e covered)`() {
        // 限流层语义：无 tokenInstallId → legacy 独立计数器；401000 由业务层 mustGetTokenInstallId 抛（本单测 mock 掉 service）
        withIid = false
        controller.createScan(request(), scanBody())
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanLegacyMinKey), eq(5))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanInstallMinKey), any())
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanIpMinKey), any())
    }

    // ===== DeepResearch：同结构、独立计数 =====

    @Test
    fun `dr with iid install and ip counters with correct keys and limits`() {
        controller.runDeepResearch(request(), drBody())
        verify(rateLimiter).check(eq(Window.MINUTE), eq(drInstallMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(drInstallDayKey), eq(100))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(drIpMinKey), eq(100))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(drIpDayKey), eq(1000))
    }

    @Test
    fun `dr and scan counters are independent per action`() {
        val scanOnly = controller.createScan(request(), scanBody())
        assertThat(scanOnly.body!!.data!!.scanId).isNotNull
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(drInstallMinKey), any())
    }

    @Test
    fun `dr legacy fallback 3 per minute and 300 per day legacy counters`() {
        withIid = false
        controller.runDeepResearch(request(), drBody())
        verify(rateLimiter).check(eq(Window.MINUTE), eq(drLegacyMinKey), eq(3))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(drLegacyDayKey), eq(300))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(drInstallMinKey), any())
    }

    @Test
    fun `dr ip day 1001st request rejects`() {
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(drIpDayKey), eq(1000))).thenReturn(RateLimitResult.Limited(10))
        val e = assertThrows<ApiError> { controller.runDeepResearch(request(), drBody()) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
    }

    @Test
    fun `install layer budget is shared across ips for the same install`() {
        // 全新 request（不带默认 ip header），仅 9.9.9.9：install 层计数不变，IP 层换 key
        val otherIpRequest = MockHttpServletRequest().apply {
            addHeader("X-Forwarded-For", "9.9.9.9")
            LogContext.start(this)
        }
        controller.createScan(otherIpRequest, scanBody())
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanInstallMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.MINUTE), eq("ratelimit:$pid:scan:ip:min:9.9.9.9"), eq(100))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanIpMinKey), eq(100))
    }
}
