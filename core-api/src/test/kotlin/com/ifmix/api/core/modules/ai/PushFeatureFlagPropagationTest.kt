package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ifmix.core.api.dto.ai.DeepResearchFeatureFlagsInput
import com.ifmix.core.api.dto.ai.DeepResearchImageInput
import com.ifmix.core.api.dto.ai.NewScanImageInput
import com.ifmix.core.api.dto.ai.NewScanInput
import com.ifmix.core.api.dto.ai.RunDeepResearchInput
import com.ifmix.core.api.dto.ai.ScanFeatureFlagsInput
import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.entity.ai.ScanRecord
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
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.util.UUID

class PushFeatureFlagPropagationTest {
    private val projectId = "test-app"
    private val customerId = UUID.randomUUID()
    private val installId = UUID.randomUUID()
    private val scanRepo = mock<ScanRecordRepository>()
    private val metricsRepo = mock<CustomerScanMetricsRepository>()
    private val deepRepo = mock<ScanDeepResearchRepository>()
    private val handler = ScanAggHandler(
        scanRunner = mock(),
        objectStorage = object : ObjectStorage {
            override fun presignUpload(bucketId: String, objectKey: String, contentType: String, duration: Duration) = ""
            override fun presignDownload(bucketId: String, objectKey: String, duration: Duration) = ""
            override fun getPublicUrl(bucketId: String, objectKey: String) = "https://cdn/$objectKey"
            override fun upload(bucketId: String, objectKey: String, data: ByteArray, contentType: String) = Unit
        },
        scanRepo = scanRepo,
        deepResearchRepo = deepRepo,
        scanPrompt = ScanPrompt("v10"),
        scanMetricsRepo = metricsRepo,
        scanQuota = ScanQuotaConfig(scan = 5, deepResearch = 3),
        messages = com.ifmix.api.core.testsupport.TestMessages.source,    )

    private fun mc() = ModuleCtx(
        action = ActionContext(
            projectId = projectId,
            actorId = customerId,
            tokenInstallId = installId,
            isMutation = true,
        ),
        sql = mock<KSqlClient>(),
    )

    private fun scanInput(flags: ScanFeatureFlagsInput? = null) = NewScanInput(
        images = listOf(NewScanImageInput("main.jpg", 0, "image/jpeg")),
        collected = false,
        featureFlags = flags,
    )

    @Test
    fun `scan feature flag defaults off and propagates true`() {
        whenever(scanRepo.findStaleInProgress(any(), eq(projectId), eq(customerId), any())).thenReturn(emptyList())
        whenever(metricsRepo.reserveScan(any(), eq(projectId), eq(customerId), eq(5))).thenReturn(1)
        whenever(scanRepo.insert(any(), any<ScanRecord>())).thenReturn(true)

        assertThat(handler.createScanTask(mc(), scanInput()).scanResultPushEnabled).isFalse()
        assertThat(
            handler.createScanTask(mc(), scanInput(ScanFeatureFlagsInput(scanResultPushEnabled = true)))
                .scanResultPushEnabled
        ).isTrue()
    }

    @Test
    fun `deep research feature flag defaults off and propagates true`() {
        val scan = mock<ScanRecord>()
        whenever(scan.images).thenReturn(emptyList())
        whenever(scan.locale).thenReturn(null)
        whenever(scan.country).thenReturn(null)
        whenever(scan.currency).thenReturn(null)
        whenever(scanRepo.updateImages(any(), eq(projectId), eq(customerId), any(), any())).thenReturn(1)
        whenever(scanRepo.findByIdOwned(any(), eq(projectId), eq(customerId), any())).thenReturn(scan)
        whenever(metricsRepo.findCounts(any(), eq(projectId), eq(customerId))).thenReturn(0 to 0)

        val defaultFlags = handler.createDeepResearchTask(
            mc(),
            RunDeepResearchInput(
                scanRecordId = UUID.randomUUID(),
                images = listOf(DeepResearchImageInput("main.jpg", 0, "image/jpeg")),
            ),
        )
        val enabledFlags = handler.createDeepResearchTask(
            mc(),
            RunDeepResearchInput(
                scanRecordId = UUID.randomUUID(),
                images = listOf(DeepResearchImageInput("main.jpg", 0, "image/jpeg")),
                featureFlags = DeepResearchFeatureFlagsInput(deepResearchPushEnabled = true),
            ),
        )

        assertThat(defaultFlags.deepResearchPushEnabled).isFalse()
        assertThat(enabledFlags.deepResearchPushEnabled).isTrue()
    }
}
