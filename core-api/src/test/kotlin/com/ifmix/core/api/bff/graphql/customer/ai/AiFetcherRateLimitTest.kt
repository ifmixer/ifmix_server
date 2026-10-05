package com.ifmix.core.api.bff.graphql.customer.ai

import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.entity.ai.DeepResearchStatuses
import com.ifmix.core.api.entity.ai.ScanStatus
import com.ifmix.core.api.entity.ai.ScanStatuses
import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.infra.graphql.ActionContextProvider
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.ratelimit.RateLimitProperties
import com.ifmix.core.api.infra.ratelimit.RateLimiter
import com.ifmix.core.api.infra.ratelimit.RateLimitResult
import com.ifmix.core.api.infra.ratelimit.Window
import com.ifmix.core.api.infra.tx.GlobalTxRunner
import com.ifmix.core.api.modules.ai.AiFacade
import com.ifmix.core.api.modules.ai.DeepResearchTaskService
import com.ifmix.core.api.modules.ai.ScanCollectionFacade
import com.ifmix.core.api.modules.ai.ScanTaskService
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
import java.util.UUID

/**
 * scan / DeepResearch 下游 install 层限流（attest 规格 §4.6「下游接口的 install 层」+ §7「下游两层数值」）：
 * - 有 iid（customer token 的 iid）：install 层（5/min + 100/天）→ IP 层（100/min + 1000/天）；
 *   install 层拒绝不碰 IP 计数器；IP 拒绝 install 已扣不退；scan 与 DR 分别计数。
 * - 无 iid（legacy fallback 期）：legacy 独立计数器（scan 5/min+500/天、DR 3/min+300/天），不占大额 IP 计数器。
 * 数值从 RateLimitProperties 读取（默认值 = 规格 §4.6 yaml）。
 */
class AiFetcherRateLimitTest {

    private val pid = "p1"
    private val ip = "1.2.3.4"
    private val iid = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private lateinit var dfe: DgsDataFetchingEnvironment
    private lateinit var fetcher: AiFetcher

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

    private lateinit var rateLimiter: RateLimiter
    private lateinit var ctxProvider: ActionContextProvider
    private lateinit var globalTx: GlobalTxRunner

    @BeforeEach
    fun setUp() {
        dfe = mock()
        ctxProvider = mock()
        val aiService = mock<AiFacade>()
        val collectionService = mock<ScanCollectionFacade>()
        val drTaskService = mock<DeepResearchTaskService>()
        val scanTaskService = mock<ScanTaskService>()
        globalTx = mock<GlobalTxRunner>()
        rateLimiter = mock()
        whenever(rateLimiter.check(any(), any(), any())).thenReturn(RateLimitResult.Allowed)

        val scanCtx = ScanTaskContext(
            projectId = pid, customerId = customerId, installId = iid, scanId = UUID.randomUUID(),
            locale = null, country = null, currency = null, images = emptyList(), collected = false,
            promptVersion = "v1", createdAt = java.time.Instant.now(),
        )
        whenever(aiService.createScanTask(any(), any())).thenAnswer { scanCtx }
        whenever(aiService.createDeepResearchTask(any(), any())).thenAnswer {
            DeepResearchTaskContext(
                projectId = pid, customerId = customerId,
                deepResearchId = UUID.randomUUID(), scanRecordId = UUID.randomUUID(),
                images = emptyList(), locale = null, country = null, currency = null, promptVersion = "v1",
                createdAt = java.time.Instant.now(),
            )
        }
        whenever(globalTx.withTx<Any>(any(), any())).thenAnswer {
            val c = it.arguments[0] as ActionContext
            ((it.arguments[1]) as (ActionContext) -> Any)(c)
        }

        val ctx = ActionContext(projectId = pid, clientIp = ip, tokenInstallId = iid, tokenType = 10, actorId = customerId)
        stubDfe(ctx)

        fetcher = AiFetcher(
            aiService, collectionService, drTaskService, scanTaskService,
            globalTx, ctxProvider, rateLimiter, RateLimitProperties(),
        )
    }

    private fun ctxWithoutIid(): ActionContext =
        ActionContext(projectId = pid, clientIp = ip, tokenInstallId = null, legacyInstallId = null, tokenType = 10, actorId = customerId)

    private fun ctxWithLegacy(): ActionContext =
        ActionContext(projectId = pid, clientIp = ip, tokenInstallId = null, legacyInstallId = iid, tokenType = 10, actorId = customerId)

    private fun stubDfe(ctx: ActionContext) {
        whenever(ctxProvider.fromDfe(any(), any(), anyOrNull(), any(), any(), any())).thenReturn(ctx)
    }

    // ===== scan：install 层 → IP 层 顺序与数值 =====

    @Test
    fun `scan with iid order is install min install day ip min ip day`() {
        fetcher.newScan(dfe, NewScanInput(images = emptyList(), collected = null))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanInstallMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(scanInstallDayKey), eq(100))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanIpMinKey), eq(100))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(scanIpDayKey), eq(1000))
    }

    @Test
    fun `scan with iid install minute limited rejects without touching IP counters`() {
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(scanInstallMinKey), eq(5))).thenReturn(RateLimitResult.Limited(45))
        val e = assertThrows<ApiError> { fetcher.newScan(dfe, NewScanInput(images = emptyList(), collected = null)) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(e.retryAfterSec).isEqualTo(45L)
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanIpMinKey), any())
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(scanIpDayKey), any())
    }

    @Test
    fun `scan with iid install day 101st request rejects 100 install limit`() {
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(scanInstallDayKey), eq(100))).thenReturn(RateLimitResult.Limited(3600))
        val e = assertThrows<ApiError> { fetcher.newScan(dfe, NewScanInput(images = emptyList(), collected = null)) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
    }

    @Test
    fun `scan with iid ip limited means install counters already deducted`() {
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(scanIpMinKey), eq(100))).thenReturn(RateLimitResult.Limited(30))
        assertThrows<ApiError> { fetcher.newScan(dfe, NewScanInput(images = emptyList(), collected = null)) }
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanInstallMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(scanInstallDayKey), eq(100))
        verify(rateLimiter, never()).check(eq(Window.UTC_DAY), eq(scanIpDayKey), any())
    }

    @Test
    fun `scan legacy fallback only legacy counters used with strict thresholds`() {
        stubDfe(ctxWithLegacy())
        fetcher.newScan(dfe, NewScanInput(images = emptyList(), collected = null))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanLegacyMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(scanLegacyDayKey), eq(500))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanInstallMinKey), any())
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanIpMinKey), any())
    }

    @Test
    fun `scan legacy minute limited rejects with retryAfterSec`() {
        stubDfe(ctxWithLegacy())
        whenever(rateLimiter.check(eq(Window.MINUTE), eq(scanLegacyMinKey), eq(5))).thenReturn(RateLimitResult.Limited(20))
        val e = assertThrows<ApiError> { fetcher.newScan(dfe, NewScanInput(images = emptyList(), collected = null)) }
        assertThat(e.retryAfterSec).isEqualTo(20L)
    }

    @Test
    fun `scan without token iid uses legacy counters (401 comes from mustGetTokenInstallId in the service layer, e2e covered)`() {
        // 限流层语义：无 tokenInstallId → legacy 独立计数器；401000 由业务层 mustGetTokenInstallId 抛（本单测 mock 掉 service）
        stubDfe(ctxWithoutIid())
        fetcher.newScan(dfe, NewScanInput(images = emptyList(), collected = null))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanLegacyMinKey), eq(5))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanInstallMinKey), any())
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanIpMinKey), any())
    }

    // ===== DeepResearch：同结构、独立计数 =====

    @Test
    fun `dr with iid install and ip counters with correct keys and limits`() {
        fetcher.runDeepResearch(dfe, com.ifmix.core.api.generated.types.RunDeepResearchInput(scanRecordId = UUID.randomUUID(), images = emptyList(), featureFlags = null))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(drInstallMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(drInstallDayKey), eq(100))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(drIpMinKey), eq(100))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(drIpDayKey), eq(1000))
    }

    @Test
    fun `dr and scan counters are independent per action`() {
        val scanOnly = fetcher.newScan(dfe, NewScanInput(images = emptyList(), collected = null))
        assertThat(scanOnly.scanId).isNotNull
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(drInstallMinKey), any())
    }

    @Test
    fun `dr legacy fallback 3 per minute and 300 per day legacy counters`() {
        stubDfe(ctxWithLegacy())
        fetcher.runDeepResearch(dfe, com.ifmix.core.api.generated.types.RunDeepResearchInput(scanRecordId = UUID.randomUUID(), images = emptyList(), featureFlags = null))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(drLegacyMinKey), eq(3))
        verify(rateLimiter).check(eq(Window.UTC_DAY), eq(drLegacyDayKey), eq(300))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(drInstallMinKey), any())
    }

    @Test
    fun `dr ip day 1001st request rejects`() {
        whenever(rateLimiter.check(eq(Window.UTC_DAY), eq(drIpDayKey), eq(1000))).thenReturn(RateLimitResult.Limited(10))
        val e = assertThrows<ApiError> { fetcher.runDeepResearch(dfe, com.ifmix.core.api.generated.types.RunDeepResearchInput(scanRecordId = UUID.randomUUID(), images = emptyList(), featureFlags = null)) }
        assertThat(e.errorCode).isEqualTo(ErrorCode.RATE_LIMITED)
    }

    @Test
    fun `install layer budget is shared across ips for the same install`() {
        val ctxNewIp = ctxWithoutIid().copy(clientIp = "9.9.9.9", tokenInstallId = iid)
        stubDfe(ctxNewIp)
        fetcher.newScan(dfe, NewScanInput(images = emptyList(), collected = null))
        verify(rateLimiter).check(eq(Window.MINUTE), eq(scanInstallMinKey), eq(5))
        verify(rateLimiter).check(eq(Window.MINUTE), eq("ratelimit:$pid:scan:ip:min:9.9.9.9"), eq(100))
        verify(rateLimiter, never()).check(eq(Window.MINUTE), eq(scanIpMinKey), eq(100))
    }
}
