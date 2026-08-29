package com.ifmix.api.core.modules.ai.handler

import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.generated.types.CommonFindOptions
import com.ifmix.api.core.dto.ai.AiScanResult
import com.ifmix.api.core.dto.ai.ScanInput
import com.ifmix.api.core.dto.ai.ScanMediaItem
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.entity.ai.ImageRef
import com.ifmix.api.core.entity.ai.ScanRecord
import com.ifmix.api.core.modules.ai.ScanRunner
import com.ifmix.api.core.modules.ai.repo.ScanRecordRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class ScanAggHandler(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val scanRepo: ScanRecordRepository,
    private val deepResearchRepo: com.ifmix.api.core.modules.ai.repo.ScanDeepResearchRepository,
    private val mcFactory: com.ifmix.api.core.infra.db.ModuleCtxFactory,
) {
    /** 外部 AI 调用（无事务）— 解析 images、运行 AI、返回结果 DTO */
    fun runAiScan(opCtx: OperationContext, input: NewScanInput): AiScanResult {
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
            locale = opCtx.locale,
            country = opCtx.country,
            currency = opCtx.currency,
        )
        val aiResponse = scanRunner.run(opCtx, scanInput)

        @Suppress("UNCHECKED_CAST")
        val basicResult = aiResponse["basic_result"] as? Map<String, Any?> ?: aiResponse

        return AiScanResult(
            scanId = scanId,
            appId = opCtx.mustGetAppId(),
            locale = opCtx.locale,
            country = opCtx.country,
            currency = opCtx.currency,
            clientIp = opCtx.clientIp,
            images = input.images,
            basicResult = basicResult,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 在事务内将 AiScanResult 持久化为 ScanRecord */
    fun saveNewScan(sc: ModuleCtx, result: AiScanResult): ScanRecord {
        val record = ScanRecord {
            id = result.scanId
            this.appId = result.appId
            this.images = result.images.map { ImageRef(key = it.imageKey) }
            this.basicResult = result.basicResult
            this.status = 20
            this.clientIp = result.clientIp
            this.installId = sc.op.mustGetInstallId()
            this.userId = sc.op.userId
            this.locale = result.locale
            this.country = result.country
            this.currency = result.currency
            this.userDisplayName = null
            this.userNotes = null
            this.collected = false
            this.hasDeepSearch = false
            this.createdAt = result.createdAt
            this.updatedAt = result.updatedAt
        }
        scanRepo.save(sc, record)
        return record
    }

    fun updateScan(sc: ModuleCtx, input: UpdateScanInput): Boolean {
        val appId = sc.op.mustGetAppId()
        if (!scanRepo.exists(sc, appId, input.id)) throw com.ifmix.api.core.infra.http.ApiError(
            com.ifmix.api.core.infra.http.ErrorCode.NOT_FOUND
        )
        scanRepo.partialUpdate(sc, appId, input.id, input)
        return true
    }

    fun deleteScan(sc: ModuleCtx, id: UUID): Boolean {
        val appId = sc.op.mustGetAppId()
        scanRepo.deleteById(sc, appId, id)
        return true
    }

    fun findById(sc: ModuleCtx, id: UUID): ScanRecord? =
        scanRepo.findById(sc, sc.op.mustGetAppId(), id)

    /**
     * DeepResearch 外部 AI 调用（无事务）。
     * 校验 scanRecord 归属，按 position 排序图片，用 deep-research 提示词跑 AI，返回结果 DTO。
     */
    fun runDeepResearch(opCtx: OperationContext, input: com.ifmix.api.core.generated.types.RunDeepResearchInput): com.ifmix.api.core.dto.ai.DeepResearchResult {
        val appId = opCtx.mustGetAppId()
        val mc = mcFactory.forApp(opCtx)
        val existing = scanRepo.findById(mc, appId, input.scanRecordId)
            ?: throw com.ifmix.api.core.infra.http.ApiError(com.ifmix.api.core.infra.http.ErrorCode.NOT_FOUND)

        // 按 position 排序后落库 & 送 AI
        val sorted = input.images.sortedBy { it.position }
        val imageRefs = sorted.map { ImageRef(key = it.imageKey, position = it.position) }
        val resolved = sorted.map { img ->
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
            deepResearch = true,
        )
        val aiResponse = scanRunner.run(opCtx, scanInput)

        @Suppress("UNCHECKED_CAST")
        val basicResult = aiResponse["basic_result"] as? Map<String, Any?> ?: aiResponse
        @Suppress("UNCHECKED_CAST")
        val premiumResult = aiResponse["premium_result"] as? Map<String, Any?>

        return com.ifmix.api.core.dto.ai.DeepResearchResult(
            scanRecordId = input.scanRecordId,
            appId = appId,
            images = imageRefs,
            basicResult = basicResult,
            premiumResult = premiumResult,
        )
    }

    /**
     * 事务内持久化 DeepResearch 结果：
     * 1) 回写 scan_record 的 images + basicResult + hasDeepSearch
     * 2) 按 scanRecordId upsert ai_scan_deep_research 的 premiumResult
     */
    fun saveDeepResearch(sc: ModuleCtx, result: com.ifmix.api.core.dto.ai.DeepResearchResult): Boolean {
        val updated = scanRepo.updateAfterDeepResearch(
            sc, result.appId, result.scanRecordId, result.images, result.basicResult,
        )
        if (updated == 0) throw com.ifmix.api.core.infra.http.ApiError(com.ifmix.api.core.infra.http.ErrorCode.NOT_FOUND)

        val existing = deepResearchRepo.findByScanRecordId(sc, result.appId, result.scanRecordId)
        val entity = com.ifmix.api.core.entity.ai.ScanDeepResearch {
            id = existing?.id ?: UuidV7.generate()
            this.appId = result.appId
            this.scanRecordId = result.scanRecordId
            this.premiumResult = result.premiumResult
        }
        deepResearchRepo.upsert(sc, entity)
        return true
    }

    fun presignedUploadUrl(sc: ModuleCtx, objectKey: String, contentType: String, duration: Duration): String =
        objectStorage.presignUpload("ugc", objectKey, contentType, duration)

    fun presignedDownloadUrl(sc: ModuleCtx, objectKey: String, duration: Duration): String =
        objectStorage.presignDownload("ugc", objectKey, duration)

    fun getPublicUrl(sc: ModuleCtx, objectKey: String): String =
        objectStorage.getPublicUrl("ugc", objectKey)

    fun findMyScans(sc: ModuleCtx, findOptions: CommonFindOptions?): Page<ScanRecord> {
        val appId = sc.op.mustGetAppId()
        val installId = sc.op.mustGetInstallId()
        return scanRepo.findMyScans(sc, appId, installId, findOptions)
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
