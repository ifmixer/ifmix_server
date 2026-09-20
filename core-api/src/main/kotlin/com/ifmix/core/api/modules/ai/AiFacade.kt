package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.dto.ai.AiScanResult
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
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
    fun findById(opCtx: ActionContext, id: UUID): ScanRecord? =
        scanHandler.findById(mcFactory.forProject(opCtx), id)

    /** 批量按 scanRecordId 查询 DeepResearch（DataLoader 用）。 */
    fun findDeepResearchByScanRecordIds(opCtx: ActionContext, scanRecordIds: Collection<UUID>): List<com.ifmix.core.api.entity.ai.ScanDeepResearch> =
        scanHandler.findDeepResearchByScanRecordIds(mcFactory.forProject(opCtx), scanRecordIds)

    fun findMyScans(opCtx: ActionContext, findOptions: CommonFindOptions?): Page<ScanRecord> =
        scanHandler.findMyScans(mcFactory.forProject(opCtx), findOptions)

    /** AI 调用在事务外 */
    fun runAiScan(opCtx: ActionContext, input: NewScanInput): AiScanResult =
        scanHandler.runAiScan(opCtx, input)

    /** DB 写入在事务内 */
    fun saveScanRecord(opCtx: ActionContext, result: AiScanResult): ScanRecord =
        scanHandler.saveNewScan(mcFactory.forProject(opCtx), result)

    /** DeepResearch 第一步：先更新图片（事务内，AI 失败也已提交） */
    fun updateDeepResearchImages(opCtx: ActionContext, input: com.ifmix.core.api.generated.types.RunDeepResearchInput) =
        scanHandler.updateDeepResearchImages(mcFactory.forProject(opCtx), input)

    /** DeepResearch AI 调用在事务外（mc 在此构建） */
    fun runDeepResearch(opCtx: ActionContext, input: com.ifmix.core.api.generated.types.RunDeepResearchInput): com.ifmix.core.api.dto.ai.DeepResearchResult =
        scanHandler.runDeepResearch(mcFactory.forProject(opCtx), input)

    /** DeepResearch DB 写入在事务内 */
    fun saveDeepResearch(opCtx: ActionContext, result: com.ifmix.core.api.dto.ai.DeepResearchResult): Boolean =
        scanHandler.saveDeepResearch(mcFactory.forProject(opCtx), result)

    fun updateScan(opCtx: ActionContext, input: UpdateScanInput): Boolean =
        scanHandler.updateScan(mcFactory.forProject(opCtx), input)

    fun batchUpdateScan(opCtx: ActionContext, input: com.ifmix.core.api.generated.types.BatchUpdateScanInput): Int =
        scanHandler.batchUpdateScan(mcFactory.forProject(opCtx), input)

    fun deleteScan(opCtx: ActionContext, id: UUID): Boolean =
        scanHandler.deleteScan(mcFactory.forProject(opCtx), id)

    fun presignedUploadUrl(opCtx: ActionContext, objectKey: String, contentType: String, duration: Duration): String =
        scanHandler.presignedUploadUrl(mcFactory.forProject(opCtx), objectKey, contentType, duration)

    fun presignedDownloadUrl(opCtx: ActionContext, objectKey: String, duration: Duration): String =
        scanHandler.presignedDownloadUrl(mcFactory.forProject(opCtx), objectKey, duration)

    fun getPublicUrl(opCtx: ActionContext, objectKey: String): String =
        scanHandler.getPublicUrl(mcFactory.forProject(opCtx), objectKey)
}
