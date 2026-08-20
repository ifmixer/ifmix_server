package com.ifmix.api.core.modules.ai

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.modules.ai.handler.ScanAggHandler
import com.ifmix.api.core.entity.ai.ScanRecord
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class AiFacade(
    private val mcFactory: ModuleCtxFactory,
    private val scanHandler: ScanAggHandler,
) {
    fun findById(opCtx: OperationContext, id: UUID): ScanRecord? =
        scanHandler.findById(mcFactory.forApp(opCtx), id)

    fun findByCursor(opCtx: OperationContext, cursor: String?, limit: Int?): Page<ScanRecord> =
        scanHandler.findByCursorFiltered(mcFactory.forApp(opCtx), cursor, limit, null)

    fun findByCursorFiltered(opCtx: OperationContext, cursor: String?, limit: Int?, collected: Boolean?): Page<ScanRecord> =
        scanHandler.findByCursorFiltered(mcFactory.forApp(opCtx), cursor, limit, collected)

    fun findByFilter(opCtx: OperationContext, filter: FilterGroup?, cursor: String?, limit: Int?): Page<ScanRecord> =
        scanHandler.findByFilter(mcFactory.forApp(opCtx), filter, cursor, limit)

    /**
     * newScan: AI 调用在事务外，DB 写入也在事务外（事务由 DataFetcher 层 GlobalTxRunner 管理）。
     */
    fun newScan(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.NewScanInput): ScanRecord {
        // Step 1: 外部 AI 调用（无事务）
        val scanId = scanHandler.prepareNewScan(input)
        // Step 2: DB 写入
        return scanHandler.saveNewScan(mcFactory.forApp(opCtx), scanId, input)
    }

    fun updateScan(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.UpdateScanInput): Boolean =
        scanHandler.updateScan(mcFactory.forApp(opCtx), input)

    fun deleteScan(opCtx: OperationContext, id: UUID): Boolean =
        scanHandler.deleteScan(mcFactory.forApp(opCtx), id)

    fun presignedUploadUrl(opCtx: OperationContext, objectKey: String, contentType: String, duration: Duration): String =
        scanHandler.presignedUploadUrl(mcFactory.forApp(opCtx), objectKey, contentType, duration)

    fun presignedDownloadUrl(opCtx: OperationContext, objectKey: String, duration: Duration): String =
        scanHandler.presignedDownloadUrl(mcFactory.forApp(opCtx), objectKey, duration)

    fun getPublicUrl(opCtx: OperationContext, objectKey: String): String =
        scanHandler.getPublicUrl(mcFactory.forApp(opCtx), objectKey)
}
