package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ifmix.core.api.generated.types.NewScanImageInput
import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.ai.repo.ScanDeepResearchRepository
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import com.ifmix.core.api.modules.ai.service.ScanPrompt
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * 验证 NewScanInput.collected 在 runAiScan 中的透传与缺省行为（AI/存储用假实现，不触库）。
 */
class ScanCollectedTest {

    private val fakeStorage = object : ObjectStorage {
        override fun presignUpload(bucketId: String, objectKey: String, contentType: String, duration: Duration) = "up"
        override fun presignDownload(bucketId: String, objectKey: String, duration: Duration) = "down"
        override fun getPublicUrl(bucketId: String, objectKey: String) = "https://cdn/$objectKey"
        override fun upload(bucketId: String, objectKey: String, data: ByteArray, contentType: String) {}
    }

    private val fakeRunner = object : ScanRunner {
        override fun run(ctx: OperationContext, input: com.ifmix.core.api.dto.ai.ScanInput): Map<String, Any?> =
            mapOf("basic_result" to mapOf("scan_status" to mapOf("status" to "SUCCESS")))
    }

    private val handler = ScanAggHandler(
        scanRunner = fakeRunner,
        objectStorage = fakeStorage,
        scanRepo = ScanRecordRepository(),
        deepResearchRepo = ScanDeepResearchRepository(),
        scanPrompt = ScanPrompt("v10"),
    )

    private fun opCtx() = OperationContext(appId = UUID.randomUUID(), actorId = UUID.randomUUID())

    private fun input(collected: Boolean?) = NewScanInput(
        images = listOf(NewScanImageInput(imageKey = "k.jpg", category = 0, mediaType = "image/jpeg")),
        collected = collected,
    )

    @Test
    fun `collected true passes through`() {
        assertThat(handler.runAiScan(opCtx(), input(true)).collected).isTrue()
    }

    @Test
    fun `collected false passes through`() {
        assertThat(handler.runAiScan(opCtx(), input(false)).collected).isFalse()
    }

    @Test
    fun `collected omitted defaults to false`() {
        assertThat(handler.runAiScan(opCtx(), input(null)).collected).isFalse()
    }
}
