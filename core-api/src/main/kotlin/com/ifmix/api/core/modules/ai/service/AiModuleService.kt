package com.ifmix.api.core.modules.ai.service

import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.infra.db.SvcCtxFactory
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.modules.ai.service.internal.ScanEntityService
import com.ifmix.api.core.entity.ai.ScanRecord
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class AiModuleService(
    private val svcCtxFactory: SvcCtxFactory,
    private val entityService: ScanEntityService,
    private val tx: TxRunner,
) {
    fun findById(opCtx: OperationContext, id: UUID): ScanRecord? = entityService.findById(svcCtxFactory.forApp(opCtx), id)
    fun findByCursor(opCtx: OperationContext, cursor: String?, limit: Int?): Page<ScanRecord> =
        entityService.findByCursorFiltered(svcCtxFactory.forApp(opCtx), cursor, limit, null)
    fun findByCursorFiltered(opCtx: OperationContext, cursor: String?, limit: Int?, collected: Boolean?): Page<ScanRecord> =
        entityService.findByCursorFiltered(svcCtxFactory.forApp(opCtx), cursor, limit, collected)
    fun findByFilter(opCtx: OperationContext, filter: FilterGroup?, cursor: String?, limit: Int?): Page<ScanRecord> =
        entityService.findByFilter(svcCtxFactory.forApp(opCtx), filter, cursor, limit)
    fun newScan(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.NewScanInput): ScanRecord =
        tx.withTx(svcCtxFactory.forApp(opCtx)) { sc -> entityService.newScan(sc, input) }
    fun updateScan(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.UpdateScanInput): Boolean =
        tx.withTx(svcCtxFactory.forApp(opCtx)) { sc -> entityService.updateScan(sc, input) }
    fun deleteScan(opCtx: OperationContext, id: UUID): Boolean =
        tx.withTx(svcCtxFactory.forApp(opCtx)) { sc -> entityService.deleteScan(sc, id) }
    fun presignedUploadUrl(opCtx: OperationContext, objectKey: String, contentType: String, duration: Duration): String =
        entityService.presignedUploadUrl(svcCtxFactory.forApp(opCtx), objectKey, contentType, duration)
    fun presignedDownloadUrl(opCtx: OperationContext, objectKey: String, duration: Duration): String =
        entityService.presignedDownloadUrl(svcCtxFactory.forApp(opCtx), objectKey, duration)
    fun getPublicUrl(opCtx: OperationContext, objectKey: String): String =
        entityService.getPublicUrl(svcCtxFactory.forApp(opCtx), objectKey)
}
