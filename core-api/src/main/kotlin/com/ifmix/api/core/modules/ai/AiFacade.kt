package com.ifmix.api.core.modules.ai

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.infra.db.ModuleCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.tx.TxRunner
import com.ifmix.api.core.modules.ai.handler.ScanCollectionHandler
import com.ifmix.api.core.modules.ai.handler.ScanHandler
import com.ifmix.api.core.entity.ai.ScanRecord
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class AiFacade(
    private val mcFactory: ModuleCtxFactory,
    private val scanHandler: ScanHandler,
    private val tx: TxRunner,
) {
    fun findById(opCtx: OperationContext, id: UUID): ScanRecord? = scanHandler.findById(mcFactory.forApp(opCtx), id)
    fun findByCursor(opCtx: OperationContext, cursor: String?, limit: Int?): Page<ScanRecord> =
        scanHandler.findByCursorFiltered(mcFactory.forApp(opCtx), cursor, limit, null)
    fun findByCursorFiltered(opCtx: OperationContext, cursor: String?, limit: Int?, collected: Boolean?): Page<ScanRecord> =
        scanHandler.findByCursorFiltered(mcFactory.forApp(opCtx), cursor, limit, collected)
    fun findByFilter(opCtx: OperationContext, filter: FilterGroup?, cursor: String?, limit: Int?): Page<ScanRecord> =
        scanHandler.findByFilter(mcFactory.forApp(opCtx), filter, cursor, limit)

    /**
     * newScan: AI 调用在事务外，DB 写入在事务内。
     * 避免外部 HTTP 占住事务连接。
     */
    fun newScan(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.NewScanInput): ScanRecord {
        // Step 1: 外部 AI 调用（无事务）
        val scanId = scanHandler.prepareNewScan(input)
        // Step 2: DB 写入（有事务）
        return tx.withTx(mcFactory.forApp(opCtx)) { sc -> scanHandler.saveNewScan(sc, scanId, input) }
    }

    fun updateScan(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.UpdateScanInput): Boolean =
        tx.withTx(mcFactory.forApp(opCtx)) { sc -> scanHandler.updateScan(sc, input) }
    fun deleteScan(opCtx: OperationContext, id: UUID): Boolean =
        tx.withTx(mcFactory.forApp(opCtx)) { sc -> scanHandler.deleteScan(sc, id) }
    fun presignedUploadUrl(opCtx: OperationContext, objectKey: String, contentType: String, duration: Duration): String =
        scanHandler.presignedUploadUrl(mcFactory.forApp(opCtx), objectKey, contentType, duration)
    fun presignedDownloadUrl(opCtx: OperationContext, objectKey: String, duration: Duration): String =
        scanHandler.presignedDownloadUrl(mcFactory.forApp(opCtx), objectKey, duration)
    fun getPublicUrl(opCtx: OperationContext, objectKey: String): String =
        scanHandler.getPublicUrl(mcFactory.forApp(opCtx), objectKey)
}
