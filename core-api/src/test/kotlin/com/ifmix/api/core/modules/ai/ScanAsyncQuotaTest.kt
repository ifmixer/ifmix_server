package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.generated.types.NewScanImageInput
import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.ratelimit.ScanQuotaConfig
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.ai.repo.CustomerScanMetricsRepository
import com.ifmix.core.api.modules.ai.repo.ScanDeepResearchRepository
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import com.ifmix.core.api.modules.ai.service.ScanPrompt
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ScanAsyncQuotaTest {
    private val projectId = "test-app"
    private val customerId = UUID.randomUUID()
    private val installId = UUID.randomUUID()
    private val scanRepo = mock<ScanRecordRepository>()
    private val metricsRepo = mock<CustomerScanMetricsRepository>()
    private val quota = ScanQuotaConfig(scan = 1, deepResearch = 3)
    private val handler = ScanAggHandler(
        scanRunner = mock(),
        objectStorage = object : ObjectStorage {
            override fun presignUpload(bucketId: String, objectKey: String, contentType: String, duration: Duration) = ""
            override fun presignDownload(bucketId: String, objectKey: String, duration: Duration) = ""
            override fun getPublicUrl(bucketId: String, objectKey: String) = "https://cdn/$objectKey"
            override fun upload(bucketId: String, objectKey: String, data: ByteArray, contentType: String) = Unit
        },
        scanRepo = scanRepo,
        deepResearchRepo = ScanDeepResearchRepository(),
        scanPrompt = ScanPrompt("v10"),
        scanMetricsRepo = metricsRepo,
        scanQuota = quota,
        messages = com.ifmix.api.core.testsupport.TestMessages.source,    )

    private fun mc() = ModuleCtx(
        action = ActionContext(
            projectId = projectId,
            actorId = customerId,
            installId = installId,
            isMutation = true,
        ),
        sql = mock<KSqlClient>(),
    )

    @Test
    fun `create scan reserves quota before inserting in progress record`() {
        whenever(scanRepo.findStaleInProgress(any(), eq(projectId), eq(customerId), any())).thenReturn(emptyList())
        whenever(scanRepo.insert(any(), any())).thenReturn(true)
        whenever(metricsRepo.reserveScan(any(), eq(projectId), eq(customerId), eq(1))).thenReturn(1)

        val input = NewScanInput(
            images = listOf(NewScanImageInput(imageKey = "main.jpg", category = 0, mediaType = "image/jpeg")),
            collected = false,
        )
        val task = handler.createScanTask(mc(), input)

        assertThat(task.projectId).isEqualTo(projectId)
        assertThat(task.customerId).isEqualTo(customerId)
        verify(metricsRepo).reserveScan(any(), eq(projectId), eq(customerId), eq(1))
        verify(scanRepo).insert(any(), any())
    }

    @Test
    fun `ai success transfers pending reservation into scan count`() {
        val scanId = UUID.randomUUID()
        whenever(scanRepo.casSuccess(any(), eq(projectId), eq(customerId), eq(scanId), any())).thenReturn(1)
        whenever(metricsRepo.completeScan(any(), eq(projectId), eq(customerId))).thenReturn(1)

        val won = handler.finalizeScanSuccess(
            mc(),
            ScanTaskContext(
                projectId = projectId,
                customerId = customerId,
                installId = installId,
                scanId = scanId,
                locale = "en",
                country = "US",
                currency = "USD",
                images = emptyList(),
                collected = false,
                promptVersion = "v10",
                createdAt = Instant.now(),
            ),
            basicResult = mapOf("scan_status" to mapOf("status" to "INSUFFICIENT_IMAGE")),
        )

        assertThat(won).isTrue()
        verify(metricsRepo).completeScan(any(), eq(projectId), eq(customerId))
    }
}
