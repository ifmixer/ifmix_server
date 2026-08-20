package com.ifmix.api.core.modules.ai.handler

import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.generated.types.FilterGroup
import com.ifmix.api.core.dto.common.Page
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.entity.scan.ImageRef
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
class ScanHandler(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val scanRepo: ScanRecordRepository,
) {
    /** 外部 AI 调用（无事务）+ 准备数据，返回记录 ID */
    fun prepareNewScan(input: NewScanInput): UUID {
        val scanId = UuidV7.generate()

        val resolved = input.images.map { img ->
            ScanMediaItem(
                imageUrl = objectStorage.getPublicUrl("ugc", img.imageKey),
                mediaType = guessMediaType(img.imageKey, img.mediaType),
            )
        }

        val scanInput = ScanInput(
            items = resolved,
            lang = null, // populated by caller from opCtx
            country = null,
            currency = null,
        )
        return scanId
    }

    /** 在事务内保存记录 */
    fun saveNewScan(sc: ModuleCtx, scanId: UUID, input: NewScanInput): ScanRecord {
        val appId = sc.op.appId!!
        val now = Instant.now()

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

        val record = ScanRecord {
            id = scanId
            this.appId = appId
            this.imageKeys = input.images.map { ImageRef(key = it.imageKey) }
            this.basicResult = result
            this.status = 200
            this.clientIp = sc.op.clientIp
            this.lang = sc.op.lang
            this.country = sc.op.country
            this.currency = sc.op.currency
            this.userDisplayName = null
            this.userNotes = null
            this.collected = false
            this.createdAt = now
            this.updatedAt = now
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
        // ponytail: FilterGroup 解析暂未实现，fallback 到普通游标查询
        return findByCursorFiltered(sc, cursor, limit, null)
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
