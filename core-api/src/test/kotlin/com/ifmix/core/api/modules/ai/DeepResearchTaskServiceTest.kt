package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.dto.ai.DeepResearchResult
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.entity.ai.AiTaskErrorCodes
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.tx.TxRunner
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.notification.NotificationFacade
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
 * DeepResearch 后台任务的关键分支（P1 修复 2026-10-06）：
 * - AI 正常返回但缺 `premium_result` → 判 AI_FAILED（决策 6），不走 SUCCESS 回写；
 * - premium_result 在 → 正常 finalize SUCCESS。
 */
class DeepResearchTaskServiceTest {

    private val mcFactory = mock<ModuleCtxFactory>()
    private val txRunner = mock<TxRunner>()
    private val scanRunner = mock<ScanRunner>()
    private val scanAggHandler = mock<ScanAggHandler>()
    private val notificationFacade = mock<NotificationFacade>()

    private val drId = UUID.randomUUID()
    private val scanRecordId = UUID.randomUUID()

    init {
        whenever(mcFactory.forProject(any())).thenReturn(mock<ModuleCtx>())
        // 直通短事务：执行传入 block（返回 Boolean）
        whenever(txRunner.withTx<Boolean>(any(), anyOrNull(), any())).thenAnswer {
            (it.arguments[2] as (ModuleCtx) -> Boolean)(it.arguments[0] as ModuleCtx)
        }
    }

    private fun ctx() = DeepResearchTaskContext(
        projectId = "p1", customerId = UUID.randomUUID(), deepResearchId = drId, scanRecordId = scanRecordId,
        images = emptyList(), locale = null, country = null, currency = null,
        promptVersion = "v1", createdAt = Instant.now(), deepResearchPushEnabled = false,
    )

    private fun service() =
        DeepResearchTaskService(mcFactory, txRunner, scanRunner, scanAggHandler, notificationFacade) { it.run() }

    private fun stubResult(premiumResult: Map<String, Any?>?) {
        whenever(scanRunner.run(any(), anyOrNull())).thenReturn(mapOf("basic_result" to mapOf("scan_status" to "SUCCESS")))
        whenever(
            scanAggHandler.toDeepResearchResult(any(), anyOrNull()),
        ).thenReturn(
            DeepResearchResult(
                scanRecordId = scanRecordId, projectId = "p1",
                basicResult = mapOf("scan_status" to "SUCCESS"), premiumResult = premiumResult, promptVersion = "v1",
            ),
        )
    }

    @Test
    fun `missing premium_result fails with AI_FAILED and skips success finalize`() {
        stubResult(premiumResult = null)
        service().submit(ctx())
        verify(scanAggHandler).casDeepResearchFailed(any(), eq(drId), eq(AiTaskErrorCodes.AI_FAILED), any())
        verify(scanAggHandler, never()).finalizeDeepResearchSuccess(any(), any(), any())
    }

    @Test
    fun `premium_result present finalizes success`() {
        stubResult(premiumResult = mapOf("report" to "ok"))
        whenever(scanAggHandler.finalizeDeepResearchSuccess(any(), any(), any())).thenReturn(true)
        service().submit(ctx())
        verify(scanAggHandler).finalizeDeepResearchSuccess(any(), any(), any())
        verify(scanAggHandler, never()).casDeepResearchFailed(any(), eq(drId), eq(AiTaskErrorCodes.AI_FAILED), any())
        assertTrue(true)
    }
}
