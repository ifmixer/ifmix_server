package com.ifmix.api.core.modules.ai.service.internal

import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.entity.ImageRef
import com.ifmix.api.core.entity.ai.ScanRecord
import com.ifmix.api.core.modules.ai.ScanRunner
import com.ifmix.api.core.dto.ai.ScanInput
import com.ifmix.api.core.dto.ai.ScanMediaItem
import com.ifmix.api.core.modules.ai.repo.ScanRecordRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class ScanEntityService(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val scanRepo: ScanRecordRepository,
) {
    fun newScan(sc: SvcCtx, input: NewScanInput): ScanRecord {
        val appId = sc.op.appId!!
        val now = Instant.now()
        val scanId = UuidV7.generate()

        val resolved = input.images.map { img ->
            ScanMediaItem(
                imageUrl = objectStorage.getPublicUrl("ugc", img.imageKey),
                mediaType = guessMediaType(img.imageKey, img.mediaType),
            )
        }

        val scanInput = ScanInput(
            items = resolved,
            lang = sc.op.lang,
            country = sc.op.country,
            currency = sc.op.currency,
        )
        val result = scanRunner.run(sc.op, scanInput)

        val record = ScanRecord(
            id = scanId,
            appId = appId,
            imageKeys = input.images.map { ImageRef(key = it.imageKey) },
            basicResult = result,
            premiumResult = null,
            status = ScanRecord.Status.COMPLETED,
            clientIp = sc.op.clientIp,
            lang = sc.op.lang,
            country = sc.op.country,
            currency = sc.op.currency,
            userDisplayName = null,
            userNotes = null,
            collected = false,
            createdAt = now,
            updatedAt = now,
        )
        scanRepo.insert(sc, record)
        return record
    }

    fun updateScan(sc: SvcCtx, input: UpdateScanInput): Boolean {
        val appId = sc.op.mustGetAppId()
        if (!scanRepo.exists(sc, appId, input.id)) throw com.ifmix.api.core.infra.http.ApiError(
            com.ifmix.api.core.infra.http.ErrorCode.NOT_FOUND
        )
        scanRepo.partialUpdate(sc, appId, input.id, input)
        return true
    }

    fun deleteScan(sc: SvcCtx, id: UUID): Boolean {
        val appId = sc.op.mustGetAppId()
        scanRepo.deleteById(sc, appId, id)
        return true
    }

    fun findById(sc: SvcCtx, id: UUID): ScanRecord? =
        scanRepo.findById(sc, sc.op.mustGetAppId(), id)

    fun findByCursorFiltered(sc: SvcCtx, cursor: String?, limit: Int?, collected: Boolean?): Page<ScanRecord> {
        val appId = sc.op.mustGetAppId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = scanRepo.findByCursor(sc, appId, collected, cursorUuid, effectiveLimit + 1)
        val hasMore = items.size > effectiveLimit
        val resultItems = items.take(effectiveLimit)
        return Page(
            items = resultItems,
            nextCursor = resultItems.lastOrNull()?.let { it.id.toString() },
            hasMore = hasMore,
        )
    }

    fun findByFilter(sc: SvcCtx, filter: FilterGroup?, cursor: String?, limit: Int?): Page<ScanRecord> {
        val appId = sc.op.mustGetAppId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = scanRepo.findByFilter(sc, appId, filter, cursorUuid, effectiveLimit + 1)
        val hasMore = items.size > effectiveLimit
        val resultItems = items.take(effectiveLimit)
        return Page(
            items = resultItems,
            nextCursor = resultItems.lastOrNull()?.let { it.id.toString() },
            hasMore = hasMore,
        )
    }

    fun presignedUploadUrl(sc: SvcCtx, objectKey: String, contentType: String, duration: Duration): String =
        objectStorage.presignUpload("ugc", objectKey, contentType, duration)

    fun presignedDownloadUrl(sc: SvcCtx, objectKey: String, duration: Duration): String =
        objectStorage.presignDownload("ugc", objectKey, duration)

    fun getPublicUrl(sc: SvcCtx, objectKey: String): String =
        objectStorage.getPublicUrl("ugc", objectKey)

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
