package com.ifmix.core.api.modules.ai

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ifmix.core.api.dto.ai.AddItemReq
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.modules.ai.handler.ScanCollectionAggHandler
import com.ifmix.core.api.modules.ai.repo.ScanCollectionItemRepository
import com.ifmix.core.api.modules.ai.repo.ScanCollectionRepository
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Collection owner-scope 安全边界：
 * - addItem 若显式 collectionId 不属于当前 customer -> NOT_FOUND
 * - addItem 若 scan 不属于当前 customer -> NOT_FOUND
 * 跨用户统一 NOT_FOUND，不泄露存在性。
 */
class ScanCollectionOwnerScopeTest {

    private val projectId = "test-app"
    private val owner = UUID.randomUUID()
    private val collectionRepo = mock<ScanCollectionRepository>()
    private val itemRepo = mock<ScanCollectionItemRepository>()
    private val scanRepo = mock<ScanRecordRepository>()

    private val handler = ScanCollectionAggHandler(
        collectionRepo = collectionRepo,
        itemRepo = itemRepo,
        scanRepo = scanRepo,
    )

    private fun ctx() = ModuleCtx(
        action = ActionContext(projectId = projectId, actorId = owner),
        sql = mock<KSqlClient>(),
    )

    @Test
    fun `addItem to a collection not owned by caller throws NOT_FOUND`() {
        val foreignCollectionId = UUID.randomUUID()
        val scanId = UUID.randomUUID()
        // collection ownership check fails
        whenever(collectionRepo.existsOwned(any(), eq(projectId), eq(owner), eq(foreignCollectionId))).thenReturn(false)
        val err = assertThrows<ApiError> {
            handler.addItem(ctx(), foreignCollectionId, AddItemReq(scanRecordId = scanId))
        }
        assertThat(err.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
    }

    @Test
    fun `addItem with scan not owned by caller throws NOT_FOUND`() {
        val collectionId = UUID.randomUUID()
        val foreignScanId = UUID.randomUUID()
        whenever(collectionRepo.existsOwned(any(), eq(projectId), eq(owner), eq(collectionId))).thenReturn(true)
        // scan ownership check fails
        whenever(scanRepo.existsOwned(any(), eq(projectId), eq(owner), eq(foreignScanId))).thenReturn(false)
        val err = assertThrows<ApiError> {
            handler.addItem(ctx(), collectionId, AddItemReq(scanRecordId = foreignScanId))
        }
        assertThat(err.errorCode).isEqualTo(ErrorCode.NOT_FOUND)
    }
}
