package com.ifmix.core.api.modules.ai.handler

import com.ifmix.core.api.generated.types.NewScanInput
import com.ifmix.core.api.generated.types.UpdateScanInput
import com.ifmix.core.api.generated.types.CommonFindOptions
import com.ifmix.core.api.dto.ai.AiScanResult
import com.ifmix.core.api.dto.ai.ScanInput
import com.ifmix.core.api.dto.ai.ScanMediaItem
import com.ifmix.core.api.dto.common.Page
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.http.ActionContext
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.entity.ai.ImageRef
import com.ifmix.core.api.entity.ai.ImageCategories
import com.ifmix.core.api.entity.ai.ScanRecord
import com.ifmix.core.api.modules.ai.ScanRunner
import com.ifmix.core.api.modules.ai.repo.ScanRecordRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class ScanAggHandler(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val scanRepo: ScanRecordRepository,
    private val deepResearchRepo: com.ifmix.core.api.modules.ai.repo.ScanDeepResearchRepository,
    private val scanPrompt: com.ifmix.core.api.modules.ai.service.ScanPrompt,
    private val scanMetricsRepo: com.ifmix.core.api.modules.ai.repo.CustomerScanMetricsRepository,
    private val scanQuota: com.ifmix.core.api.infra.ratelimit.ScanQuotaConfig,
) {
    /** 外部 AI 调用（无事务）— 解析 images、运行 AI、返回结果 DTO */
    fun runAiScan(mc: ModuleCtx, input: NewScanInput): AiScanResult {
        val actionCtx = mc.action
        // 前置配额校验（AI 调用前拒绝，省下 AI 成本）；并发兜底见 saveNewScan 的原子自增。
        val actorId = actionCtx.mustGetActorId()
        val used = scanMetricsRepo.findCounts(mc, actionCtx.mustGetProjectId(), actorId)?.first ?: 0
        if (used >= scanQuota.scan) throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.QUOTA_EXCEEDED, "scan quota exhausted"
        )
        val scanId = UuidV7.generate()
        val now = Instant.now()

        val resolved = input.images.map { img ->
            ScanMediaItem(
                imageUrl = objectStorage.getPublicUrl("ugc", img.imageKey),
                mediaType = guessMediaType(img.imageKey, img.mediaType),
            )
        }

        val scanInput = ScanInput(
            scanId=scanId,
            items = resolved,
            locale = actionCtx.locale,
            country = actionCtx.country,
            currency = actionCtx.currency,
        )
        val aiResponse = scanRunner.run(actionCtx, scanInput)

        @Suppress("UNCHECKED_CAST")
        val basicResult = aiResponse["basic_result"] as? Map<String, Any?> ?: aiResponse

        return AiScanResult(
            scanId = scanId,
            projectId = actionCtx.mustGetProjectId(),
            locale = actionCtx.locale,
            country = actionCtx.country,
            currency = actionCtx.currency,
            clientIp = actionCtx.clientIp,
            images = input.images,
            basicResult = basicResult,
            collected = input.collected ?: false,
            promptVersion = scanPrompt.promptVersion,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 在事务内将 AiScanResult 持久化为 ScanRecord */
    fun saveNewScan(sc: ModuleCtx, result: AiScanResult): ScanRecord {
        val record = ScanRecord {
            id = result.scanId
            this.projectId = result.projectId
            this.images = result.images.map { ImageRef(key = it.imageKey, category = it.category ?: ImageCategories.MAIN) }
            this.basicResult = result.basicResult
            this.status = com.ifmix.core.api.entity.ai.ScanStatuses.READY
            this.clientIp = result.clientIp
            this.customerId = sc.action.actorId
            this.installId = sc.action.mustGetTokenInstallId()
            this.locale = result.locale
            this.country = result.country
            this.currency = result.currency
            this.userDisplayName = null
            this.userNotes = null
            this.collected = result.collected
            this.isPublic = true
            this.hasDeepSearch = false
            this.promptVersion = result.promptVersion
            this.createdAt = result.createdAt
            this.updatedAt = result.updatedAt
        }
        scanRepo.save(sc, record)
        // 原子自增并兜底并发：仅当 scan_count < limit 时 +1；达上限则拒绝（整个事务回滚）。
        val actorId = sc.action.mustGetActorId()
        if (scanMetricsRepo.tryIncrementScanCount(sc, sc.action.mustGetProjectId(), actorId, scanQuota.scan) == 0) {
            throw com.ifmix.core.api.infra.http.ApiError(
                com.ifmix.core.api.infra.http.ErrorCode.QUOTA_EXCEEDED, "scan quota exhausted"
            )
        }
        return record
    }

    fun updateScan(sc: ModuleCtx, input: UpdateScanInput): Boolean {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        // owner-scoped：非本人拥有（或不存在）统一 NOT_FOUND；存在则执行更新（no-op set 也算成功）。
        if (!scanRepo.existsOwned(sc, projectId, customerId, input.id)) {
            throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
        }
        scanRepo.partialUpdate(sc, projectId, customerId, input.id, input)
        return true
    }

    /**
     * 批量更新 scan（owner-scoped）：仅影响调用者本人拥有的记录，返回实际更新数。
     * ids 为空视为非法请求。
     */
    fun batchUpdateScan(sc: ModuleCtx, input: com.ifmix.core.api.generated.types.BatchUpdateScanInput): Int {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        if (input.ids.isEmpty()) throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.INVALID_REQUEST, "ids cannot be empty"
        )
        return scanRepo.batchPartialUpdate(
            sc, projectId, customerId, input.ids,
            collected = input.set.collected,
            isPublic = input.set.isPublic,
        )
    }

    fun deleteScan(sc: ModuleCtx, id: UUID): Boolean {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        // owner-scoped：非本人拥有（或不存在）统一 NOT_FOUND。
        if (!scanRepo.deleteByIdOwned(sc, projectId, customerId, id)) {
            throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
        }
        return true
    }

    fun findById(sc: ModuleCtx, id: UUID): ScanRecord? =
        scanRepo.findByIdOwned(sc, sc.action.mustGetProjectId(), sc.action.mustGetActorId(), id)

    /** 批量按 scanRecordId 查询 DeepResearch（DataLoader 用，owner-scoped：先过滤出本人名下的 scanRecordId）。 */
    fun findDeepResearchByScanRecordIds(sc: ModuleCtx, scanRecordIds: Collection<UUID>): List<com.ifmix.core.api.entity.ai.ScanDeepResearch> {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        val ownedIds = scanRepo.findOwnedIdsByIds(sc, projectId, customerId, scanRecordIds)
        if (ownedIds.isEmpty()) return emptyList()
        return deepResearchRepo.findByScanRecordIds(sc, projectId, ownedIds)
    }

    /**
     * DeepResearch 第一步（事务内）：校验归属并整体替换 images。
     * 即使随后的 AI 调用失败，图片也已提交。images 顺序即数组顺序；category 为图片分类。
     */
    fun updateDeepResearchImages(sc: ModuleCtx, input: com.ifmix.core.api.generated.types.RunDeepResearchInput) {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        val imageRefs = input.images.map { ImageRef(key = it.imageKey, category = it.category ?: ImageCategories.MAIN) }
        val updated = scanRepo.updateImages(sc, projectId, customerId, input.scanRecordId, imageRefs)
        if (updated == 0) throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)
    }

    /**
     * DeepResearch 第二步（无事务，mc 由 Facade 构建）：用 deep-research 提示词跑 AI。
     * 图片已在 updateDeepResearchImages 提交，这里只读取归属信息并调用 AI。
     */
    fun runDeepResearch(sc: ModuleCtx, input: com.ifmix.core.api.generated.types.RunDeepResearchInput): com.ifmix.core.api.dto.ai.DeepResearchResult {
        val projectId = sc.action.mustGetProjectId()
        // 前置配额校验（AI 调用前拒绝）；并发兜底见 saveDeepResearch 的原子自增。
        val actorId = sc.action.mustGetActorId()
        val used = scanMetricsRepo.findCounts(sc, projectId, actorId)?.second ?: 0
        if (used >= scanQuota.deepResearch) throw com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.QUOTA_EXCEEDED, "deep research quota exhausted"
        )
        val existing = scanRepo.findByIdOwned(sc, projectId, actorId, input.scanRecordId)
            ?: throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)

        val resolved = input.images.map { img ->
            ScanMediaItem(
                imageUrl = objectStorage.getPublicUrl("ugc", img.imageKey),
                mediaType = guessMediaType(img.imageKey, img.mediaType),
            )
        }

        val scanInput = ScanInput(
            scanId = input.scanRecordId,
            items = resolved,
            locale = existing.locale,
            country = existing.country,
            currency = existing.currency,
            type = com.ifmix.core.api.dto.ai.ScanType.DEEP_RESEARCH,
        )
        val aiResponse = scanRunner.run(sc.action, scanInput)

        @Suppress("UNCHECKED_CAST")
        val basicResult = aiResponse["basic_result"] as? Map<String, Any?> ?: aiResponse
        @Suppress("UNCHECKED_CAST")
        val premiumResult = aiResponse["premium_result"] as? Map<String, Any?>

        return com.ifmix.core.api.dto.ai.DeepResearchResult(
            scanRecordId = input.scanRecordId,
            projectId = projectId,
            basicResult = basicResult,
            premiumResult = premiumResult,
            promptVersion = scanPrompt.promptVersion,
        )
    }

    /**
     * DeepResearch 第三步（事务内）：
     * 1) 回写 scan_record 的 basicResult + hasDeepSearch + promptVersion
     * 2) 按 scanRecordId upsert ai_scan_deep_research 的 premiumResult
     */
    fun saveDeepResearch(sc: ModuleCtx, result: com.ifmix.core.api.dto.ai.DeepResearchResult): Boolean {
        val customerId = sc.action.mustGetActorId()
        val updated = scanRepo.updateResultAfterDeepResearch(
            sc, result.projectId, customerId, result.scanRecordId, result.basicResult, result.promptVersion,
        )
        if (updated == 0) throw com.ifmix.core.api.infra.http.ApiError(com.ifmix.core.api.infra.http.ErrorCode.NOT_FOUND)

        val existing = deepResearchRepo.findByScanRecordId(sc, result.projectId, result.scanRecordId)
        val entity = com.ifmix.core.api.entity.ai.ScanDeepResearch {
            id = existing?.id ?: UuidV7.generate()
            this.projectId = result.projectId
            this.scanRecordId = result.scanRecordId
            this.premiumResult = result.premiumResult
            this.promptVersion = result.promptVersion
        }
        deepResearchRepo.upsert(sc, entity)
        // 原子自增并兜底并发：仅当 deep_research_count < limit 时 +1；达上限则拒绝（事务回滚）。
        val actorId = sc.action.mustGetActorId()
        if (scanMetricsRepo.tryIncrementDeepResearchCount(sc, sc.action.mustGetProjectId(), actorId, scanQuota.deepResearch) == 0) {
            throw com.ifmix.core.api.infra.http.ApiError(
                com.ifmix.core.api.infra.http.ErrorCode.QUOTA_EXCEEDED, "deep research quota exhausted"
            )
        }
        return true
    }

    fun presignedUploadUrl(sc: ModuleCtx, objectKey: String, contentType: String, duration: Duration): String =
        objectStorage.presignUpload("ugc", objectKey, contentType, duration)

    fun presignedDownloadUrl(sc: ModuleCtx, objectKey: String, duration: Duration): String =
        objectStorage.presignDownload("ugc", objectKey, duration)

    fun getPublicUrl(sc: ModuleCtx, objectKey: String): String =
        objectStorage.getPublicUrl("ugc", objectKey)

    fun findMyScans(sc: ModuleCtx, findOptions: CommonFindOptions?): Page<ScanRecord> {
        val projectId = sc.action.mustGetProjectId()
        val customerId = sc.action.mustGetActorId()
        return scanRepo.findMyScans(sc, projectId, customerId, findOptions)
    }

    private fun guessMediaType(key: String, mediaType: String?): String =
        mediaType ?: run {
            val ext = key.substringAfterLast('.', "").lowercase()
            when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "heic" -> "image/heic"
                else -> "application/octet-stream"
            }
        }

}
