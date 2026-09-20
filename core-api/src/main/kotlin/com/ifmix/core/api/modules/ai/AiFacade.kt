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
    fun findById(actionCtx: ActionContext, id: UUID): ScanRecord? =
        scanHandler.findById(mcFactory.forProject(actionCtx), id)

    /** 批量按 scanRecordId 查询 DeepResearch（DataLoader 用）。 */
    fun findDeepResearchByScanRecordIds(actionCtx: ActionContext, scanRecordIds: Collection<UUID>): List<com.ifmix.core.api.entity.ai.ScanDeepResearch> =
        scanHandler.findDeepResearchByScanRecordIds(mcFactory.forProject(actionCtx), scanRecordIds)

    fun findMyScans(actionCtx: ActionContext, findOptions: CommonFindOptions?): Page<ScanRecord> =
        scanHandler.findMyScans(mcFactory.forProject(actionCtx), findOptions)

    /** AI 调用在事务外 */
    fun runAiScan(actionCtx: ActionContext, input: NewScanInput): AiScanResult =
        scanHandler.runAiScan(actionCtx, input)

    /** DB 写入在事务内 */
    fun saveScanRecord(actionCtx: ActionContext, result: AiScanResult): ScanRecord =
        scanHandler.saveNewScan(mcFactory.forProject(actionCtx), result)

    /** DeepResearch 第一步：先更新图片（事务内，AI 失败也已提交） */
    fun updateDeepResearchImages(actionCtx: ActionContext, input: com.ifmix.core.api.generated.types.RunDeepResearchInput) =
        scanHandler.updateDeepResearchImages(mcFactory.forProject(actionCtx), input)

    /** DeepResearch AI 调用在事务外（mc 在此构建） */
    fun runDeepResearch(actionCtx: ActionContext, input: com.ifmix.core.api.generated.types.RunDeepResearchInput): com.ifmix.core.api.dto.ai.DeepResearchResult =
        scanHandler.runDeepResearch(mcFactory.forProject(actionCtx), input)

    /** DeepResearch DB 写入在事务内 */
    fun saveDeepResearch(actionCtx: ActionContext, result: com.ifmix.core.api.dto.ai.DeepResearchResult): Boolean =
        scanHandler.saveDeepResearch(mcFactory.forProject(actionCtx), result)

    fun updateScan(actionCtx: ActionContext, input: UpdateScanInput): Boolean =
        scanHandler.updateScan(mcFactory.forProject(actionCtx), input)

    fun batchUpdateScan(actionCtx: ActionContext, input: com.ifmix.core.api.generated.types.BatchUpdateScanInput): Int =
        scanHandler.batchUpdateScan(mcFactory.forProject(actionCtx), input)

    fun deleteScan(actionCtx: ActionContext, id: UUID): Boolean =
        scanHandler.deleteScan(mcFactory.forProject(actionCtx), id)

    fun presignedUploadUrl(actionCtx: ActionContext, objectKey: String, contentType: String, duration: Duration): String =
        scanHandler.presignedUploadUrl(mcFactory.forProject(actionCtx), objectKey, contentType, duration)

    fun presignedDownloadUrl(actionCtx: ActionContext, objectKey: String, duration: Duration): String =
        scanHandler.presignedDownloadUrl(mcFactory.forProject(actionCtx), objectKey, duration)

    fun getPublicUrl(actionCtx: ActionContext, objectKey: String): String =
        scanHandler.getPublicUrl(mcFactory.forProject(actionCtx), objectKey)
}
