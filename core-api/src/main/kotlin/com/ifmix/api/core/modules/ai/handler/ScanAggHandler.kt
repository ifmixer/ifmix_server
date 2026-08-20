package com.ifmix.api.core.modules.ai.handler

import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.generated.types.FilterGroup
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
            items = resolved,
            lang = opCtx.lang,
            country = opCtx.country,
            currency = opCtx.currency,
        )
        val aiResponse = scanRunner.run(opCtx, scanInput)

        @Suppress("UNCHECKED_CAST")
        val basicResult = aiResponse["basic_result"] as? Map<String, Any?> ?: aiResponse
        @Suppress("UNCHECKED_CAST")
        val premiumResult = aiResponse["premium_result"] as? Map<String, Any?>

        return AiScanResult(
            scanId = scanId,
            appId = opCtx.mustGetAppId(),
            lang = opCtx.lang,
            country = opCtx.country,
            currency = opCtx.currency,
            clientIp = opCtx.clientIp,
            images = input.images,
            basicResult = basicResult,
            premiumResult = premiumResult,
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
            this.premiumResult = result.premiumResult
            this.status = 200
            this.clientIp = result.clientIp
            this.lang = result.lang
            this.country = result.country
            this.currency = result.currency
            this.userDisplayName = null
            this.userNotes = null
            this.collected = false
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

    fun findByCursorFiltered(sc: ModuleCtx, cursor: String?, limit: Int?, collected: Boolean?): Page<ScanRecord> {
        val appId = sc.op.mustGetAppId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = scanRepo.findByCursor(sc, appId, collected, cursorUuid, effectiveLimit + 1)
        val hasMore = items.size > effectiveLimit
        val resultItems = items.take(effectiveLimit)
        return Page(
            items = resultItems,
            nextCursor = resultItems.lastOrNull()?.id?.toString(),
            hasMore = hasMore,
        )
    }

    fun presignedUploadUrl(sc: ModuleCtx, objectKey: String, contentType: String, duration: Duration): String =
        objectStorage.presignUpload("ugc", objectKey, contentType, duration)

    fun presignedDownloadUrl(sc: ModuleCtx, objectKey: String, duration: Duration): String =
        objectStorage.presignDownload("ugc", objectKey, duration)

    fun getPublicUrl(sc: ModuleCtx, objectKey: String): String =
        objectStorage.getPublicUrl("ugc", objectKey)

    fun findByFilter(sc: ModuleCtx, filter: FilterGroup?, cursor: String?, limit: Int?): Page<ScanRecord> {
        val appId = sc.op.mustGetAppId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = scanRepo.findByFilter(sc, appId, filter, cursorUuid, effectiveLimit + 1)
        val hasMore = items.size > effectiveLimit
        val resultItems = items.take(effectiveLimit)
        return Page(
            items = resultItems,
            nextCursor = resultItems.lastOrNull()?.id?.toString(),
            hasMore = hasMore,
        )
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
