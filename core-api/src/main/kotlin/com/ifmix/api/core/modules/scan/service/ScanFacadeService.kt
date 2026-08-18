package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.entity.scan.ScanRecord
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class ScanFacadeService(
    private val internalService: ScanInternalService,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun findById(opCtx: OperationContext, id: UUID): ScanRecord? = internalService.findById(svc(opCtx), id)
    fun findByCursor(opCtx: OperationContext, cursor: String?, limit: Int?): Page<ScanRecord> =
        internalService.findByCursorFiltered(svc(opCtx), cursor, limit, null)
    fun findByCursorFiltered(opCtx: OperationContext, cursor: String?, limit: Int?, collected: Boolean?): Page<ScanRecord> =
        internalService.findByCursorFiltered(svc(opCtx), cursor, limit, collected)
    fun newScan(opCtx: OperationContext, input: NewScanInput): ScanRecord =
        tx.withTx(svc(opCtx)) { sc -> internalService.newScan(sc, input) }
    fun updateScan(opCtx: OperationContext, input: UpdateScanInput): Boolean =
        tx.withTx(svc(opCtx)) { sc -> internalService.updateScan(sc, input) }
    fun deleteScan(opCtx: OperationContext, id: UUID): Boolean =
        tx.withTx(svc(opCtx)) { sc -> internalService.deleteScan(sc, id) }
    fun presignedUploadUrl(opCtx: OperationContext, objectKey: String, contentType: String, duration: Duration): String =
        internalService.presignedUploadUrl(opCtx, objectKey, contentType, duration)
    fun presignedDownloadUrl(opCtx: OperationContext, objectKey: String, duration: Duration): String =
        internalService.presignedDownloadUrl(opCtx, objectKey, duration)
    fun getPublicUrl(opCtx: OperationContext, objectKey: String): String =
        internalService.getPublicUrl(opCtx, objectKey)
}
