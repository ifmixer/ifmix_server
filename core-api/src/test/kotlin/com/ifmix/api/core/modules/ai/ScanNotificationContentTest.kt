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
        messages = com.ifmix.api.core.testsupport.TestMessages.source,    )

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

    @Test
    fun `failure notification uses locale text and detail link, no image`() {
        val images = listOf(ScanTaskContext.ImageRefItem("main.jpg", ImageCategories.MAIN, "image/jpeg"))

        val en = handler.buildScanFailureNotificationRequest(context(images))!!.content
        assertThat(en.title).isEqualTo("Scan failed")
        assertThat(en.body).isEqualTo("Please try again later")
        assertThat(en.link).isEqualTo("/p/$projectId/scan-result/$scanId")
        assertThat(en.imageUrl).isNull()

        val zh = handler.buildScanFailureNotificationRequest(context(images).copy(locale = "zh-CN"))!!.content
        assertThat(zh.title).isEqualTo("扫描失败")
        assertThat(zh.body).isEqualTo("请稍后重试")

        val ja = handler.buildScanFailureNotificationRequest(context(images).copy(locale = "ja-JP"))!!.content
        assertThat(ja.title).isEqualTo("スキャンに失敗しました")
        assertThat(ja.body).isEqualTo("しばらくしてから再度お試しください")
    }

    @Test
    fun `failure notification returns null without installId`() {
        val noInstall = context(emptyList()).copy(installId = null)
        assertThat(handler.buildScanFailureNotificationRequest(noInstall)).isNull()
    }
}
