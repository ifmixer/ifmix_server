package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.dto.ai.AiScanResult
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.ratelimit.ScanQuotaConfig
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.ai.repo.ScanDeepResearchRepository
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import com.ifmix.core.api.modules.ai.service.ScanPrompt
import com.ifmix.core.api.modules.customer.CustomerFacade
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * saveNewScan 的终身配额兜底：原子自增返回 false（已达上限）时抛 QUOTA_EXCEEDED；
 * 返回 true 时正常返回记录。前置检查在 runAiScan，另测。
 */
class ScanCountersTest {

    private val projectId = "test-app"
    private val scanRepo = mock<ScanRecordRepository>()
    private val customerFacade = mock<CustomerFacade>()
    private val quota = ScanQuotaConfig(scan = 5, deepResearch = 3)

    private val noopStorage = object : ObjectStorage {
        override fun presignUpload(bucketId: String, objectKey: String, contentType: String, duration: Duration) = ""
        override fun presignDownload(bucketId: String, objectKey: String, duration: Duration) = ""
        override fun getPublicUrl(bucketId: String, objectKey: String) = "https://cdn/$objectKey"
        override fun upload(bucketId: String, objectKey: String, data: ByteArray, contentType: String) {}
    }

    private val handler = ScanAggHandler(
        scanRunner = object : ScanRunner {
            override fun run(ctx: ActionContext, input: com.ifmix.core.api.dto.ai.ScanInput) = emptyMap<String, Any?>()
        },
        objectStorage = noopStorage,
        scanRepo = scanRepo,
        deepResearchRepo = ScanDeepResearchRepository(),
        scanPrompt = ScanPrompt("v10"),
        customerFacade = customerFacade,
        scanQuota = quota,
    )

    private val actor = UUID.randomUUID()

    private fun ctx() = ModuleCtx(
        action = ActionContext(projectId = projectId, actorId = actor),
        sql = mock<KSqlClient>(),
    )

    private fun result(): AiScanResult {
        val now = Instant.now()
        return AiScanResult(
            scanId = UUID.randomUUID(),
            projectId = projectId,
            locale = "en", country = "US", currency = "USD", clientIp = null,
            images = emptyList(), basicResult = emptyMap(), collected = false,
            promptVersion = "v10", createdAt = now, updatedAt = now,
        )
    }

    @Test
    fun `saveNewScan succeeds when under quota`() {
        whenever(customerFacade.tryIncrementScanCount(any(), eq(actor), eq(5))).thenReturn(true)
        val rec = handler.saveNewScan(ctx(), result())
        assertThat(rec.projectId).isEqualTo(projectId)
    }

    @Test
    fun `saveNewScan throws QUOTA_EXCEEDED when limit reached`() {
        whenever(customerFacade.tryIncrementScanCount(any(), eq(actor), eq(5))).thenReturn(false)
        val err = assertThrows<ApiError> { handler.saveNewScan(ctx(), result()) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.QUOTA_EXCEEDED)
    }
}
