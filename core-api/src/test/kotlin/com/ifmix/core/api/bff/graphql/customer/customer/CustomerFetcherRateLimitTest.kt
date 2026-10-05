package com.ifmix.core.api.bff.graphql.customer.customer

import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.auth.AuthFacade
import com.ifmix.core.api.modules.auth.handler.CreateAnonymousRes
import com.ifmix.core.api.modules.customer.CustomerFacade
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
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
import java.time.Instant
import java.util.UUID

/**
 * 下游 install 层限流（attest 规格 §4.6「下游接口的 install 层」+ §7「下游的顺序和策略」）：
 * - 有可信 iid（tokenInstallId）：install 层（5/install/UTC 天）→ IP 层（100/60s + 1000/天）；
 *   install 层拒绝不碰 IP 计数器；IP 层拒绝时 install 额度已扣、不退；install 层 key 只含 iid（换 IP 额度保持）。
 * - 无 iid（legacy fallback 期，靠可伪造 x-install-id）：legacy 独立计数器（10/60s 严格阈值），不占新大额 IP 计数器。
 * - key 按 projectId 隔离。
 * 数值都从 RateLimitProperties 读取（默认值 = 规格 §4.6 yaml）。
 */
class CustomerFetcherRateLimitTest {

    private val pid = "p1"
    private val ip = "1.2.3.4"
    private val iid = UUID.randomUUID()

    private lateinit var dfe: DgsDataFetchingEnvironment
    private lateinit var ctxProvider: ActionContextProvider
    private lateinit var rateLimiter: RateLimiter
    private lateinit var fetcher: CustomerFetcher

    private val installDayKey = "ratelimit:$pid:anonymous:install:day:$iid"
    private val ipMinKey = "ratelimit:$pid:anonymous:ip:min:$ip"
    private val ipDayKey = "ratelimit:$pid:anonymous:ip:day:$ip"
    private val legacyMinKey = "ratelimit:$pid:anonymous:legacy:ip:min:$ip"

    private fun ctxWithIid() = ActionContext(projectId = pid, clientIp = ip, tokenInstallId = iid, tokenType = 5, actorId = null)
    private fun ctxWithLegacyOnly() = ActionContext(projectId = pid, clientIp = ip, tokenInstallId = null, legacyInstallId = iid, actorId = null)
    private fun ctxWithoutIid() = ActionContext(projectId = pid, clientIp = ip, tokenInstallId = null, legacyInstallId = null, actorId = null)

    private fun stubDfe(ctx: ActionContext) {
        whenever(ctxProvider.fromDfe(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(ctx)
    }

    @BeforeEach
    fun setUp() {
        dfe = mock()
        ctxProvider = mock()
        val authFacade = mock<AuthFacade>()
        whenever(authFacade.createAnonymousCustomer(any())).thenReturn(
            CreateAnonymousRes(UUID.randomUUID(), "a", "r", Instant.now(), 3600L),
        )
        val customerFacade = mock<CustomerFacade>()
        val globalTx = mock<GlobalTxRunner>()
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer {
            val c = it.arguments[0] as ActionContext
            ((it.arguments[1]) as (ActionContext) -> Any)(c)
        }
        rateLimiter = mock()
        whenever(rateLimiter.check(any(), any(), any())).thenReturn(RateLimitResult.Allowed)
        fetcher = CustomerFetcher(authFacade, customerFacade, globalTx, ctxProvider, rateLimiter, RateLimitProperties())
    }

    @Test
    fun `with iid order is install day then ip min then ip day`() {
        stubDfe(ctxWithIid())
        fetcher.createAnonymousCustomer(dfe)
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(installDayKey), eq(5))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(ipMinKey), eq(100))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(ipDayKey), eq(1000))
    }

    @Test
    fun `with iid install day limited rejects without touching IP counters`() {
        stubDfe(ctxWithIid())
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(installDayKey), eq(5))).thenReturn(RateLimitResult.Limited(59))
        val e = assertThrows<ApiError> { fetcher.createAnonymousCustomer(dfe) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(59L)
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(ipMinKey), any())
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(ipDayKey), any())
    }

    @Test
    fun `with iid ip min limited means install counter was already deducted no refund`() {
        stubDfe(ctxWithIid())
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(ipMinKey), eq(100))).thenReturn(RateLimitResult.Limited(30))
        val e = assertThrows<ApiError> { fetcher.createAnonymousCustomer(dfe) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(installDayKey), eq(5)) // install 层已 +1，不退
    }

    @Test
    fun `with iid ip day limited rejects`() {
        stubDfe(ctxWithIid())
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(ipDayKey), eq(1000))).thenReturn(RateLimitResult.Limited(7200))
        val e = assertThrows<ApiError> { fetcher.createAnonymousCustomer(dfe) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(7200L)
    }

    @Test
    fun `without any trusted iid to 401000 before any counter is touched legacy fallback closed`() {
        // mustGetTokenInstallId 的 installIdOrNull 回退 legacyInstallId；无 iid 且 legacy fallback 关 → 401000（现有语义）
        val noLegacy = ActionContext(projectId = pid, clientIp = ip, tokenInstallId = null, legacyInstallId = null, actorId = null)
        stubDfe(noLegacy)
        val e = assertThrows<ApiError> { fetcher.createAnonymousCustomer(dfe) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        verify(rateLimiter, never()).check(any(), any(), any())
    }

    @Test
    fun `legacy fallback period no token iid but legacy install id uses legacy counter only`() {
        // legacyInstallId 有值（可伪造 x-install-id）：mustGetTokenInstallId 经 installIdOrNull 通过，
        // tokenInstallId==null → 走 legacy 独立计数器（10/60s 严格阈值），不占新大额 IP 计数器
        stubDfe(ctxWithLegacyOnly())
        fetcher.createAnonymousCustomer(dfe)
        verify(rateLimiter).check(eq(Window.MINUTE), eq(legacyMinKey), eq(10))
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(installDayKey), any())
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(ipMinKey), any())
    }

    @Test
    fun `legacy counter limited rejects with retryAfterSec`() {
        stubDfe(ctxWithLegacyOnly())
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(legacyMinKey), eq(10))).thenReturn(RateLimitResult.Limited(15))
        val e = assertThrows<ApiError> { fetcher.createAnonymousCustomer(dfe) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(15L)
    }

    @Test
    fun `project A exhaustion does not touch project B counters projectId scoped keys`() {
        val pidB = "p2"
        val ctxA = ctxWithIid().copy(projectId = pid)
        val ctxB = ctxWithIid().copy(projectId = pidB)
        stubDfe(ctxA)
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(installDayKey), eq(5))).thenReturn(RateLimitResult.Limited(60))
        assertThrows<ApiError> { fetcher.createAnonymousCustomer(dfe) } // A 的 install 日额度耗尽
        stubDfe(ctxB)
        fetcher.createAnonymousCustomer(dfe) // B 正常
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq("ratelimit:$pidB:anonymous:install:day:$iid"), eq(5))
        verify(rateLimiter).check(eq(Window.MINUTE), eq("ratelimit:$pidB:anonymous:ip:min:$ip"), eq(100))
    }

    @Test
    fun `install layer budget is shared across ips for the same install`() {
        // 同 iid 换 IP：install 层 key 只含 iid（额度不变），IP 层 key 换了
        val ctxNewIp = ctxWithIid().copy(clientIp = "9.9.9.9")
        stubDfe(ctxNewIp)
        fetcher.createAnonymousCustomer(dfe)
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(installDayKey), eq(5))
        verify(rateLimiter).check(eq(Window.MINUTE), eq("ratelimit:$pid:anonymous:ip:min:9.9.9.9"), eq(100))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(ipMinKey), eq(100))
    }
}
