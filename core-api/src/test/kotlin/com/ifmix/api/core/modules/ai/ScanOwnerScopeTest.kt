package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.generated.types.RunDeepResearchInput
import com.ifmix.core.api.generated.types.DeepResearchImageInput
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.generated.types.UpdateScanSetInput
import com.ifmix.core.api.dto.ai.DeepResearchResult
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
import com.ifmix.core.api.modules.ai.repo.CustomerScanMetricsRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.util.UUID

/**
 * Scan owner-scope 安全边界：跨 customer 的读/改/删/deep-research 一律 NOT_FOUND，
 * 且 owner-scoped repo 方法必须收到调用者本人的 customerId。
 *
 * 用 mock repo 断言 handler 转发 customerId 且在 owner-scoped 查询/更新返回 null/0 时抛 NOT_FOUND。
 */
class ScanOwnerScopeTest {

    private val projectId = "test-app"
    private val owner = UUID.randomUUID()
    private val scanRepo = mock<ScanRecordRepository>()
    private val scanMetricsRepo = mock<CustomerScanMetricsRepository>()
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
        scanMetricsRepo = scanMetricsRepo,
        scanQuota = quota,
    )

    private fun ctx() = ModuleCtx(
        action = ActionContext(projectId = projectId, actorId = owner),
        sql = mock<KSqlClient>(),
    )

    // ---- read ----

    @Test
    fun `findById is owner-scoped by projectId + customerId + id`() {
        val id = UUID.randomUUID()
        // owner-scoped lookup returns null -> not the caller's record
        whenever(scanRepo.findByIdOwned(any(), eq(projectId), eq(owner), eq(id))).thenReturn(null)
        assertThat(handler.findById(ctx(), id)).isEqualTo(null)
    }

    // ---- update ----

    @Test
    fun `updateScan cross-customer throws NOT_FOUND`() {
        val id = UUID.randomUUID()
        // owner-scoped existence check fails -> not owned by caller
        whenever(scanRepo.existsOwned(any(), eq(projectId), eq(owner), eq(id))).thenReturn(false)
        val input = UpdateScanInput(id = id, set = UpdateScanSetInput(collected = true), unset = null)
        val err = assertThrows<ApiError> { handler.updateScan(ctx(), input) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
    }

    // ---- delete ----

    @Test
    fun `deleteScan cross-customer throws NOT_FOUND`() {
        val id = UUID.randomUUID()
        whenever(scanRepo.deleteByIdOwned(any(), eq(projectId), eq(owner), eq(id))).thenReturn(false)
        val err = assertThrows<ApiError> { handler.deleteScan(ctx(), id) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
    }

    // ---- deep research image update ----

    @Test
    fun `updateDeepResearchImages cross-customer throws NOT_FOUND`() {
        val id = UUID.randomUUID()
        whenever(scanRepo.updateImages(any(), eq(projectId), eq(owner), eq(id), any())).thenReturn(0)
        val input = RunDeepResearchInput(
            scanRecordId = id,
            images = listOf(DeepResearchImageInput(imageKey = "k.jpg", category = 0, mediaType = "image/jpeg")),
        )
        val err = assertThrows<ApiError> { handler.updateDeepResearchImages(ctx(), input) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
    }

    // ---- deep research run (reads existing record) ----

    @Test
    fun `runDeepResearch cross-customer throws NOT_FOUND`() {
        val id = UUID.randomUUID()
        whenever(scanMetricsRepo.findCounts(any<ModuleCtx>(), any(), eq(owner))).thenReturn(0 to 0)
        whenever(scanRepo.findByIdOwned(any(), eq(projectId), eq(owner), eq(id))).thenReturn(null)
        val input = RunDeepResearchInput(
            scanRecordId = id,
            images = listOf(DeepResearchImageInput(imageKey = "k.jpg", category = 0, mediaType = "image/jpeg")),
        )
        val err = assertThrows<ApiError> { handler.runDeepResearch(ctx(), input) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
    }

    // ---- saveDeepResearch scan write is owner-scoped ----

    @Test
    fun `saveDeepResearch cross-customer scan write throws NOT_FOUND`() {
        val id = UUID.randomUUID()
        whenever(scanRepo.updateResultAfterDeepResearch(any(), eq(projectId), eq(owner), eq(id), any(), any()))
            .thenReturn(0)
        val result = DeepResearchResult(
            scanRecordId = id,
            projectId = projectId,
            basicResult = emptyMap(),
            premiumResult = null,
            promptVersion = "v10",
        )
        val err = assertThrows<ApiError> { handler.saveDeepResearch(ctx(), result) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
    }
}
