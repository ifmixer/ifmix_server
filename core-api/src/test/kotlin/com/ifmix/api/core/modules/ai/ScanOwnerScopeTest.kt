package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.generated.types.RunDeepResearchInput
import com.ifmix.core.api.generated.types.DeepResearchImageInput
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.generated.types.UpdateScanSetInput
import com.ifmix.core.api.dto.ai.DeepResearchResult
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
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
    private val deepResearchRepo = mock<ScanDeepResearchRepository>()
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
        deepResearchRepo = deepResearchRepo,
        scanPrompt = ScanPrompt("v10"),
        scanMetricsRepo = scanMetricsRepo,
        scanQuota = quota,
        messages = com.ifmix.api.core.testsupport.TestMessages.source,    )

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

    // ---- createDeepResearchTask（异步化，设计 §3.3） ----

    @Test
    fun `createDeepResearchTask cross-customer throws NOT_FOUND`() {
        val id = UUID.randomUUID()
        whenever(scanRepo.updateImages(any(), eq(projectId), eq(owner), eq(id), any())).thenReturn(1)
        whenever(scanMetricsRepo.findCounts(any<ModuleCtx>(), any(), eq(owner))).thenReturn(0 to 0)
        whenever(scanRepo.findByIdOwned(any(), eq(projectId), eq(owner), eq(id))).thenReturn(null)
        val input = RunDeepResearchInput(
            scanRecordId = id,
            images = listOf(DeepResearchImageInput(imageKey = "k.jpg", category = 0, mediaType = "image/jpeg")),
        )
        val err = assertThrows<ApiError> { handler.createDeepResearchTask(ctx(), input) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
    }

    @Test
    fun `createDeepResearchTask over quota throws QUOTA_EXCEEDED`() {
        val id = UUID.randomUUID()
        whenever(scanRepo.updateImages(any(), eq(projectId), eq(owner), eq(id), any())).thenReturn(1)
        whenever(scanMetricsRepo.findCounts(any<ModuleCtx>(), any(), eq(owner))).thenReturn(0 to 3)
        val input = RunDeepResearchInput(
            scanRecordId = id,
            images = listOf(DeepResearchImageInput(imageKey = "k.jpg", category = 0, mediaType = "image/jpeg")),
        )
        val err = assertThrows<ApiError> { handler.createDeepResearchTask(ctx(), input) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.QUOTA_EXCEEDED)
    }

    // ---- finalizeDeepResearchSuccess：终态 CAS 幂等 + 配额跟随 latest（设计 §3.4） ----

    private fun taskCtx(scanRecordId: UUID) = DeepResearchTaskContext(
        projectId = projectId,
        customerId = owner,
        deepResearchId = UUID.randomUUID(),
        scanRecordId = scanRecordId,
        images = emptyList(),
        locale = null,
        country = null,
        currency = null,
        promptVersion = "v10",
        createdAt = java.time.Instant.now(),
    )

    private fun drResult(scanRecordId: UUID) = DeepResearchResult(
        scanRecordId = scanRecordId,
        projectId = projectId,
        basicResult = emptyMap(),
        premiumResult = null,
        promptVersion = "v10",
    )

    @Test
    fun `finalize loses CAS when already finalized - no quota increment`() {
        val id = UUID.randomUUID()
        val ctx = taskCtx(id)
        whenever(deepResearchRepo.casSuccess(any(), eq(ctx.deepResearchId), anyOrNull(), anyOrNull())).thenReturn(0)
        // 已被终结：不触碰 scan_record，不扣配额（CAS 幂等，设计决策 9）
        val finalized = handler.finalizeDeepResearchSuccess(ctx(), ctx, drResult(id))
        assertThat(finalized).isEqualTo(false)
        verify(scanMetricsRepo, never()).tryIncrementDeepResearchCount(any(), any(), any(), any())
    }

    @Test
    fun `finalize wins CAS but old-task write loses - stored as history without quota`() {
        val id = UUID.randomUUID()
        val ctx = taskCtx(id)
        whenever(deepResearchRepo.casSuccess(any(), eq(ctx.deepResearchId), anyOrNull(), anyOrNull())).thenReturn(1)
        whenever(
            scanRepo.updateAiFieldsAndPointerIfNewer(any(), eq(projectId), eq(owner), eq(id), any(), any(), any(), any()),
        ).thenReturn(false)
        // 旧任务晚完成：仅存历史，不动 scan_record、不扣配额（设计决策 8）；finalize 返回 isLatest=false
        val finalized = handler.finalizeDeepResearchSuccess(ctx(), ctx, drResult(id))
        assertThat(finalized).isEqualTo(false)
        verify(scanMetricsRepo, never()).tryIncrementDeepResearchCount(any(), any(), any(), any())
    }

    @Test
    fun `finalize wins and moves pointer - quota incremented once`() {
        val id = UUID.randomUUID()
        val ctx = taskCtx(id)
        whenever(deepResearchRepo.casSuccess(any(), eq(ctx.deepResearchId), anyOrNull(), anyOrNull())).thenReturn(1)
        whenever(
            scanRepo.updateAiFieldsAndPointerIfNewer(any(), eq(projectId), eq(owner), eq(id), any(), any(), any(), any()),
        ).thenReturn(true)
        val finalized = handler.finalizeDeepResearchSuccess(ctx(), ctx, drResult(id))
        verify(deepResearchRepo).casSuccess(any(), eq(ctx.deepResearchId), anyOrNull(), anyOrNull())
        verify(scanRepo).updateAiFieldsAndPointerIfNewer(any(), eq(projectId), eq(owner), eq(id), any(), any(), any(), any())
        assertThat(finalized).isEqualTo(true)
        verify(scanMetricsRepo, times(1)).tryIncrementDeepResearchCount(any(), any(), any(), any())
    }

    // ---- (created_at, id) latest 排序纯函数（设计 §3.4） ----

    @Test
    fun `latest comparison is (created_at, id) tuple order`() {
        val repo = ScanRecordRepository(mock<javax.sql.DataSource>())
        val t1 = java.time.Instant.parse("2026-10-03T10:00:00Z")
        val t2 = java.time.Instant.parse("2026-10-03T11:00:00Z")
        val idA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val idB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

        // 晚时间胜
        assertThat(repo.isNewer(t2, idA, t1, idB)).isEqualTo(true)
        assertThat(repo.isNewer(t1, idA, t2, idB)).isEqualTo(false)
        // 同时间 id 兜底
        assertThat(repo.isNewer(t1, idB, t1, idA)).isEqualTo(true)
        assertThat(repo.isNewer(t1, idA, t1, idB)).isEqualTo(false)
        // 相等不算新（严格大于）
        assertThat(repo.isNewer(t1, idA, t1, idA)).isEqualTo(false)
    }
}
