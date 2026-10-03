package com.ifmix.api.core.modules

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.dto.ai.AddItemReq
import com.ifmix.core.api.dto.ai.AiScanResult
import com.ifmix.core.api.dto.cs.CreateSupportRequestReq
import com.ifmix.core.api.dto.cs.SubmitFeedbackReq
import com.ifmix.core.api.entity.ai.ScanCollection
import com.ifmix.core.api.entity.ai.ScanRecord
import com.ifmix.core.api.infra.ratelimit.ScanQuotaConfig
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.ai.ScanRunner
import com.ifmix.core.api.modules.ai.repo.ScanDeepResearchRepository
import com.ifmix.core.api.modules.ai.service.ScanPrompt
import com.ifmix.core.api.modules.ai.repo.CustomerScanMetricsRepository
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.entity.cs.Feedback
import com.ifmix.core.api.entity.cs.SupportRequest
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.ai.handler.ScanCollectionAggHandler
import com.ifmix.core.api.modules.ai.repo.ScanCollectionItemRepository
import com.ifmix.core.api.modules.ai.repo.ScanCollectionRepository
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import com.ifmix.core.api.modules.cs.handler.FeedbackAggHandler
import com.ifmix.core.api.modules.cs.handler.SupportRequestAggHandler
import com.ifmix.core.api.modules.cs.repo.FeedbackRepository
import com.ifmix.core.api.modules.cs.repo.SupportRequestRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.UUID

/**
 * 可信 install_id：Feedback/Support/Collection 新写入的 install_id 必须来自已验签 token 的 iid
 * （ActionContext.mustGetTokenInstallId），伪造的 x-install-id header 不影响；缺失 token iid 时拒绝。
 */
class TrustedInstallIdWriteTest {

    private val projectId = "test-app"
    private val actor = UUID.randomUUID()
    private val tokenIid = UUID.randomUUID()
    private val forgedHeader = "ffffffff-ffff-ffff-ffff-ffffffffffff"

    private class CapturingFeedbackRepo : FeedbackRepository() {
        var saved: Feedback? = null
        override fun save(mc: ModuleCtx, entity: Feedback): Boolean { saved = entity; return true }
    }
    private class CapturingSupportRepo : SupportRequestRepository() {
        var saved: SupportRequest? = null
        override fun save(mc: ModuleCtx, entity: SupportRequest): Boolean { saved = entity; return true }
    }
    private class CapturingCollectionRepo : ScanCollectionRepository() {
        var saved: ScanCollection? = null
        override fun save(mc: ModuleCtx, entity: ScanCollection): Boolean { saved = entity; return true }
    }
    private class CapturingScanRepo : ScanRecordRepository(org.mockito.kotlin.mock<javax.sql.DataSource>()) {
        var saved: ScanRecord? = null
        override fun save(mc: ModuleCtx, entity: ScanRecord): Boolean { saved = entity; return true }
    }

    private fun ctx(tokenInstallId: UUID?) = ModuleCtx(
        action = ActionContext(
            projectId = projectId,
            actorId = actor,
            installId = forgedHeader,           // 伪造的 x-install-id
            tokenInstallId = tokenInstallId,    // 可信 iid
        ),
        sql = mock<KSqlClient>(),
    )

    // ---- Feedback ----
    @Test
    fun `feedback write uses token iid, not forged x-install-id`() {
        val repo = CapturingFeedbackRepo()
        FeedbackAggHandler(repo).submit(ctx(tokenIid), SubmitFeedbackReq(topic = 10, reasons = listOf(10)))
        assertThat(repo.saved!!.installId).isEqualTo(tokenIid)
    }

    @Test
    fun `feedback rejects when token iid missing`() {
        val err = assertThrows<ApiError> {
            FeedbackAggHandler(CapturingFeedbackRepo()).submit(ctx(null), SubmitFeedbackReq(topic = 10, reasons = listOf(10)))
        }
        assertThat(err.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    // ---- Support ----
    @Test
    fun `support write uses token iid, not forged x-install-id`() {
        val repo = CapturingSupportRepo()
        SupportRequestAggHandler(repo).create(ctx(tokenIid), CreateSupportRequestReq(title = "t", message = "m"))
        assertThat(repo.saved!!.installId).isEqualTo(tokenIid)
    }

    @Test
    fun `support rejects when token iid missing`() {
        val err = assertThrows<ApiError> {
            SupportRequestAggHandler(CapturingSupportRepo()).create(ctx(null), CreateSupportRequestReq(title = "t", message = "m"))
        }
        assertThat(err.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    // ---- Collection ----
    @Test
    fun `default collection write uses token iid, not forged x-install-id`() {
        val repo = CapturingCollectionRepo()
        val handler = ScanCollectionAggHandler(repo, mock<ScanCollectionItemRepository>(), mock<ScanRecordRepository>())
        handler.createDefaultCollection(ctx(tokenIid))
        assertThat(repo.saved!!.installId).isEqualTo(tokenIid)
    }

    // ---- ScanRecord ----
    private fun scanResult() = AiScanResult(
        scanId = UUID.randomUUID(),
        projectId = projectId,
        locale = null, country = null, currency = null, clientIp = null,
        images = emptyList(),
        basicResult = emptyMap(),
        collected = false,
        promptVersion = "v1",
        createdAt = java.time.Instant.now(),
        updatedAt = java.time.Instant.now(),
    )

    private fun scanHandler(scanRepo: ScanRecordRepository): ScanAggHandler {
        val scanMetricsRepo = mock<CustomerScanMetricsRepository> {
            on { tryIncrementScanCount(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any()) } doReturn 1
        }
        return ScanAggHandler(
            scanRunner = mock<ScanRunner>(),
            objectStorage = mock<ObjectStorage>(),
            scanRepo = scanRepo,
            deepResearchRepo = mock<ScanDeepResearchRepository>(),
            scanPrompt = mock<ScanPrompt>(),
            scanMetricsRepo = scanMetricsRepo,
            scanQuota = ScanQuotaConfig(),
            messages = com.ifmix.api.core.testsupport.TestMessages.source,        )
    }

    @Test
    fun `scan record write uses token iid, not forged x-install-id`() {
        val repo = CapturingScanRepo()
        scanHandler(repo).saveNewScan(ctx(tokenIid), scanResult())
        assertThat(repo.saved!!.installId).isEqualTo(tokenIid)
    }

    @Test
    fun `scan record write rejects when token iid missing`() {
        val err = assertThrows<ApiError> {
            scanHandler(CapturingScanRepo()).saveNewScan(ctx(null), scanResult())
        }
        assertThat(err.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
}
