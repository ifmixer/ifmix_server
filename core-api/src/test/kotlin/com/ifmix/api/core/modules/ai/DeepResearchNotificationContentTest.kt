package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.ifmix.core.api.dto.ai.DeepResearchResult
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.entity.ai.ImageCategories
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

class DeepResearchNotificationContentTest {
    private val projectId = "test-app"
    private val scanRecordId = UUID.randomUUID()
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
        messages = com.ifmix.api.core.testsupport.TestMessages.source,    )

    private val ctx = DeepResearchTaskContext(
        projectId = projectId,
        customerId = UUID.randomUUID(),
        deepResearchId = UUID.randomUUID(),
        scanRecordId = scanRecordId,
        images = listOf(DeepResearchTaskContext.ImageRefItem("main.jpg", ImageCategories.MAIN)),
        locale = "en",
        country = "US",
        currency = "USD",
        promptVersion = "v10",
        createdAt = Instant.now(),
        installId = UUID.randomUUID(),
    )

    private fun result(status: String, name: Any? = "Bronze vase") = DeepResearchResult(
        scanRecordId = scanRecordId,
        projectId = projectId,
        basicResult = mapOf(
            "scan_status" to mapOf("status" to status),
            "object_overview" to mapOf("name" to name),
        ),
        premiumResult = mapOf("report" to "ready"),
        promptVersion = "v10",
    )

    @Test
    fun `deep research content uses report theme name main image and scan result link`() {
        val content = handler.buildDeepResearchNotificationContent(ctx, result("SUCCESS"))

        assertThat(content.title).isEqualTo("Deep research complete")
        assertThat(content.body).contains("Bronze vase")
        assertThat(content.imageUrl).isEqualTo("https://cdn/main.jpg")
        assertThat(content.link).isEqualTo("/p/$projectId/scan-result/$scanRecordId")
    }

    @Test
    fun `advice status is treated as normal and missing name falls back to unnamed`() {
        // advice 分支已移除：INSUFFICIENT_IMAGE 等状态按正常处理；空名 → 无名回退文案。
        val content = handler.buildDeepResearchNotificationContent(ctx, result("INSUFFICIENT_IMAGE", "  "))

        assertThat(content.title).isEqualTo("Deep research complete")
        assertThat(content.body).isEqualTo("Your deep research report is ready")
        assertThat(content.imageUrl).isEqualTo("https://cdn/main.jpg")
    }

    @Test
    fun `missing main image omits notification image`() {
        val noMain = ctx.copy(images = listOf(DeepResearchTaskContext.ImageRefItem("front.jpg", 10)))

        assertThat(handler.buildDeepResearchNotificationContent(noMain, result("SUCCESS")).imageUrl).isNull()
    }
}
