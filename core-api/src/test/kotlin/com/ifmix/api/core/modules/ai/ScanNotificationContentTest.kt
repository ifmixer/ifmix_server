package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.entity.ai.ImageCategories
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.ai.repo.CustomerScanMetricsRepository
import com.ifmix.core.api.modules.ai.repo.ScanDeepResearchRepository
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import com.ifmix.core.api.modules.ai.service.ScanPrompt
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ScanNotificationContentTest {
    private val projectId = "test-app"
    private val scanId = UUID.randomUUID()
    private val installId = UUID.randomUUID()
    private val handler = ScanAggHandler(
        scanRunner = mock(),
        objectStorage = object : ObjectStorage {
            override fun presignUpload(bucketId: String, objectKey: String, contentType: String, duration: Duration) = ""
            override fun presignDownload(bucketId: String, objectKey: String, duration: Duration) = ""
            override fun getPublicUrl(bucketId: String, objectKey: String) = "https://cdn/$objectKey"
            override fun upload(bucketId: String, objectKey: String, data: ByteArray, contentType: String) = Unit
        },
        scanRepo = mock<ScanRecordRepository>(),
        deepResearchRepo = ScanDeepResearchRepository(),
        scanPrompt = ScanPrompt("v10"),
        scanMetricsRepo = mock<CustomerScanMetricsRepository>(),
        scanQuota = com.ifmix.core.api.infra.ratelimit.ScanQuotaConfig(),
    )

    private fun context(images: List<ScanTaskContext.ImageRefItem>) = ScanTaskContext(
        projectId = projectId,
        customerId = UUID.randomUUID(),
        installId = installId,
        scanId = scanId,
        locale = "en",
        country = "US",
        currency = "USD",
        images = images,
        collected = false,
        promptVersion = "v10",
        createdAt = Instant.now(),
    )

    @Test
    fun `content includes non blank object name main image and deep link`() {
        val content = handler.buildScanNotificationRequest(
            context(listOf(ScanTaskContext.ImageRefItem("main.jpg", ImageCategories.MAIN, "image/jpeg"))),
            mapOf("object_overview" to mapOf("name" to "Bronze vase")),
        )!!.content

        assertThat(content.body).contains("Bronze vase")
        assertThat(content.imageUrl).isEqualTo("https://cdn/main.jpg")
        assertThat(content.link).isEqualTo("/p/$projectId/scan-result/$scanId")
    }

    @Test
    fun `content falls back to plain template without main image or blank name`() {
        val content = handler.buildScanNotificationRequest(
            context(listOf(ScanTaskContext.ImageRefItem("front.jpg", 10, "image/jpeg"))),
            mapOf("object_overview" to mapOf("name" to "  ")),
        )!!.content

        assertThat(content.body).isEqualTo("Your scan result is ready")
        assertThat(content.imageUrl).isNull()
    }
}
