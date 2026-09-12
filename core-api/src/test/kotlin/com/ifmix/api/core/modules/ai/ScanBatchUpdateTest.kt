package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.generated.types.BatchUpdateScanInput
import com.ifmix.core.api.generated.types.BatchUpdateScanSetInput
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.modules.ai.repo.ScanDeepResearchRepository
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import com.ifmix.core.api.modules.ai.service.ScanPrompt
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import java.time.Duration
import java.util.UUID

/**
 * 批量更新 owner 校验与无操作短路（不触库）。
 * - repo.batchPartialUpdate 对 ids 空 / set 全空的短路分支在触碰 sql 前返回 0，用 mock sql 断言零交互。
 * - handler.batchUpdateScan 对空 ids 抛 INVALID_REQUEST。
 */
class ScanBatchUpdateTest {

    private val repo = ScanRecordRepository()
    private val projectId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private fun ctx(sql: KSqlClient) = ModuleCtx(
        op = OperationContext(projectId = projectId, actorId = customerId),
        sql = sql,
    )

    @Test
    fun `empty ids short-circuits without touching sql`() {
        val sql = mock<KSqlClient>()
        val n = repo.batchPartialUpdate(ctx(sql), projectId, customerId, emptyList(), collected = true, isPublic = null)
        assertThat(n).isEqualTo(0)
        verifyNoInteractions(sql)
    }

    @Test
    fun `all-null set short-circuits without touching sql`() {
        val sql = mock<KSqlClient>()
        val n = repo.batchPartialUpdate(ctx(sql), projectId, customerId, listOf(UUID.randomUUID()), collected = null, isPublic = null)
        assertThat(n).isEqualTo(0)
        verifyNoInteractions(sql)
    }

    @Test
    fun `handler rejects empty ids`() {
        val handler = ScanAggHandler(
            scanRunner = object : ScanRunner {
                override fun run(ctx: OperationContext, input: com.ifmix.core.api.dto.ai.ScanInput) = emptyMap<String, Any?>()
            },
            objectStorage = object : ObjectStorage {
                override fun presignUpload(bucketId: String, objectKey: String, contentType: String, duration: Duration) = ""
                override fun presignDownload(bucketId: String, objectKey: String, duration: Duration) = ""
                override fun getPublicUrl(bucketId: String, objectKey: String) = ""
                override fun upload(bucketId: String, objectKey: String, data: ByteArray, contentType: String) {}
            },
            scanRepo = repo,
            deepResearchRepo = ScanDeepResearchRepository(),
            scanPrompt = ScanPrompt("v10"),
        )
        val sql = mock<KSqlClient>()
        val input = BatchUpdateScanInput(ids = emptyList(), set = BatchUpdateScanSetInput(collected = true, isPublic = null))
        val err = assertThrows<ApiError> { handler.batchUpdateScan(ctx(sql), input) }
        assertThat(err.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
        verifyNoInteractions(sql)
    }
}
