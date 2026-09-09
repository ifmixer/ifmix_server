package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.dto.ai.AiScanResult
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.OperationContext
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.entity.ai.ScanRecord
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

    /** 批量按 scanRecordId 查询 DeepResearch（DataLoader 用）。 */
    fun findDeepResearchByScanRecordIds(opCtx: OperationContext, scanRecordIds: Collection<UUID>): List<com.ifmix.core.api.entity.ai.ScanDeepResearch> =
        scanHandler.findDeepResearchByScanRecordIds(mcFactory.forApp(opCtx), scanRecordIds)

    fun findMyScans(opCtx: OperationContext, findOptions: CommonFindOptions?): Page<ScanRecord> =
        scanHandler.findMyScans(mcFactory.forApp(opCtx), findOptions)

    /** AI 调用在事务外 */
    fun runAiScan(opCtx: OperationContext, input: NewScanInput): AiScanResult =
        scanHandler.runAiScan(opCtx, input)

    /** DB 写入在事务内 */
    fun saveScanRecord(opCtx: OperationContext, result: AiScanResult): ScanRecord =
        scanHandler.saveNewScan(mcFactory.forApp(opCtx), result)

    /** DeepResearch 第一步：先更新图片（事务内，AI 失败也已提交） */
    fun updateDeepResearchImages(opCtx: OperationContext, input: com.ifmix.core.api.generated.types.RunDeepResearchInput) =
        scanHandler.updateDeepResearchImages(mcFactory.forApp(opCtx), input)

    /** DeepResearch AI 调用在事务外（mc 在此构建） */
    fun runDeepResearch(opCtx: OperationContext, input: com.ifmix.core.api.generated.types.RunDeepResearchInput): com.ifmix.core.api.dto.ai.DeepResearchResult =
        scanHandler.runDeepResearch(mcFactory.forApp(opCtx), input)

    /** DeepResearch DB 写入在事务内 */
    fun saveDeepResearch(opCtx: OperationContext, result: com.ifmix.core.api.dto.ai.DeepResearchResult): Boolean =
        scanHandler.saveDeepResearch(mcFactory.forApp(opCtx), result)

    fun updateScan(opCtx: OperationContext, input: UpdateScanInput): Boolean =
        scanHandler.updateScan(mcFactory.forApp(opCtx), input)

    fun batchUpdateScan(opCtx: OperationContext, input: com.ifmix.core.api.generated.types.BatchUpdateScanInput): Int =
        scanHandler.batchUpdateScan(mcFactory.forApp(opCtx), input)

    fun deleteScan(opCtx: OperationContext, id: UUID): Boolean =
        scanHandler.deleteScan(mcFactory.forApp(opCtx), id)

    fun presignedUploadUrl(opCtx: OperationContext, objectKey: String, contentType: String, duration: Duration): String =
        scanHandler.presignedUploadUrl(mcFactory.forApp(opCtx), objectKey, contentType, duration)

    fun presignedDownloadUrl(opCtx: OperationContext, objectKey: String, duration: Duration): String =
        scanHandler.presignedDownloadUrl(mcFactory.forApp(opCtx), objectKey, duration)

    fun getPublicUrl(opCtx: OperationContext, objectKey: String): String =
        scanHandler.getPublicUrl(mcFactory.forApp(opCtx), objectKey)
}
