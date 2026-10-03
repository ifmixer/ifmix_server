package com.ifmix.core.api.modules.ai

import com.ifmix.core.api.dto.ai.AiScanResult
import com.ifmix.core.api.dto.ai.DeepResearchTaskContext
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.dto.ai.ScanStatusSnapshot
import com.ifmix.core.api.dto.ai.ScanTaskContext
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.generated.types.RunDeepResearchInput
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.infra.db.ModuleCtxFactory
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.modules.ai.handler.ScanAggHandler
import com.ifmix.core.api.entity.ai.ScanDeepResearch
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

    fun findMyScans(actionCtx: ActionContext, findOptions: CommonFindOptions?): Page<ScanRecord> =
        scanHandler.findMyScans(mcFactory.forProject(actionCtx), findOptions)

    /** AI 调用在事务外 */
    fun runAiScan(actionCtx: ActionContext, input: NewScanInput): AiScanResult =
        scanHandler.runAiScan(mcFactory.forProject(actionCtx), input)

    /** DB 写入在事务内，返回 scanId（调用方事务外重查全字段 ScanRecord） */
    fun saveScanRecord(actionCtx: ActionContext, result: AiScanResult): UUID =
        scanHandler.saveNewScan(mcFactory.forProject(actionCtx), result)
    /** mutation 事务内：清理 stale、预留 quota、创建 IN_PROGRESS 记录。 */
    fun createScanTask(actionCtx: ActionContext, input: NewScanInput): ScanTaskContext =
        scanHandler.createScanTask(mcFactory.forProject(actionCtx), input)

    /** 轮询状态，包含 owner 校验和惰性超时。 */
    fun getScanStatus(actionCtx: ActionContext, scanId: UUID): ScanStatusSnapshot =
        scanHandler.getScanStatus(mcFactory.forProject(actionCtx), scanId)

    /** executor 提交失败或后台异常时终结 scan。 */
    fun casScanFailed(actionCtx: ActionContext, ctx: ScanTaskContext, errorCode: String, details: Map<String, Any?>?): Boolean =
        scanHandler.casScanFailed(mcFactory.forProject(actionCtx), ctx, errorCode, details)


    // ==================== DeepResearch 异步任务（设计 §3.3/§8） ====================

    /** mutation 事务内：更新 images + 配额预检 + 创建 IN_PROGRESS 记录；返回任务上下文。 */
    fun createDeepResearchTask(actionCtx: ActionContext, input: RunDeepResearchInput): DeepResearchTaskContext =
        scanHandler.createDeepResearchTask(mcFactory.forProject(actionCtx), input)

    /** 轮询状态（含惰性超时判定）。 */
    fun getDeepResearchStatus(actionCtx: ActionContext, deepResearchId: UUID): ScanDeepResearch =
        scanHandler.getDeepResearchStatus(mcFactory.forProject(actionCtx), deepResearchId)

    /** 批量按 deepResearchId 查询（latestDeepResearch DataLoader 用）。 */
    fun findDeepResearchByIds(actionCtx: ActionContext, ids: Collection<UUID>): List<ScanDeepResearch> =
        scanHandler.findDeepResearchByIds(mcFactory.forProject(actionCtx), ids)

    /** 终态 CAS 包装（executor 提交失败等场景）。 */
    fun casDeepResearchFailed(actionCtx: ActionContext, deepResearchId: UUID, errorCode: String, errorDetails: Map<String, Any?>?): Boolean =
        scanHandler.casDeepResearchFailed(mcFactory.forProject(actionCtx), deepResearchId, errorCode, errorDetails)

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
