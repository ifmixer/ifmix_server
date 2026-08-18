package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.model.scan.ScanRecord
import com.ifmix.api.core.model.ImageRef
import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.UpdateScanInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.infra.service.CrudServiceOps
import com.ifmix.api.core.infra.service.CrudServiceOpsFactory
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.modules.scan.ScanRunner
import com.ifmix.api.core.modules.scan.dto.ScanInput
import com.ifmix.api.core.modules.scan.dto.ScanMediaItem
import com.ifmix.api.core.modules.scan.repo.ScanRecordRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 扫描模块 jOOQ 版服务。
 */
@Service
class AntiqueService(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val scanRepo: ScanRecordRepository,
    private val tx: TxRunner,
    factory: CrudServiceOpsFactory,
) {
    private val ops: CrudServiceOps<ScanRecord> = factory.create(ScanRecord::class.java, "scan") { it.id }

    private fun svcCtx(opCtx: OperationContext): SvcCtx = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun newScan(ctx: OperationContext, input: NewScanInput): ScanRecord = tx.withTx(svcCtx(ctx)) { txCtx ->
        val appId = ctx.appId!!
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
            lang = ctx.lang,
            country = ctx.country,
            currency = ctx.currency,
        )
        val result = scanRunner.run(ctx, scanInput)

        val record = ScanRecord(
            id = scanId,
            appId = appId,
            imageKeys = input.images.map { ImageRef(key = it.imageKey) },
            result = result,
            status = ScanRecord.Status.COMPLETED,
            clientIp = ctx.clientIp,
            lang = ctx.lang,
            country = ctx.country,
            currency = ctx.currency,
            userDisplayName = null,
            userNotes = null,
            collected = false,
            createdAt = now,
            updatedAt = now,
        )
        scanRepo.insert(txCtx, record)
        ops.evict(txCtx, scanId)
        record
    }

    fun findById(ctx: OperationContext, id: UUID): ScanRecord? =
        ops.findById(svcCtx(ctx), id, scanRepo::findById)

    fun findByCursor(ctx: OperationContext, cursor: String?, limit: Int?): Page<ScanRecord> =
        ops.findByCursor(svcCtx(ctx), cursor, limit) { svcCtx, appId, cursorUuid, limitVal ->
            scanRepo.findByCursor(svcCtx, appId, null, cursorUuid, limitVal)
        }

    /** 带 collected 过滤的游标查询，不走缓存。 */
    fun findByCursorFiltered(ctx: OperationContext, cursor: String?, limit: Int?, collected: Boolean?): Page<ScanRecord> {
        val svcCtx = svcCtx(ctx)
        val appId = ctx.mustGetAppId()
        val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
        val cursorUuid = cursor?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = scanRepo.findByCursor(svcCtx, appId, collected, cursorUuid, effectiveLimit + 1)
        val hasMore = items.size > effectiveLimit
        val resultItems = items.take(effectiveLimit)
        return Page(
            items = resultItems,
            nextCursor = resultItems.lastOrNull()?.let { it.id.toString() },
            hasMore = hasMore,
        )
    }

    fun updateScan(ctx: OperationContext, input: UpdateScanInput): Boolean = tx.withTx(svcCtx(ctx)) { txCtx ->
        val appId = txCtx.mustGetAppId()
        if (!scanRepo.exists(txCtx, appId, input.id)) throw ApiError(ErrorCode.NOT_FOUND)
        scanRepo.partialUpdate(txCtx, appId, input.id, input)
        ops.evict(txCtx, input.id)
        true
    }

    fun deleteScan(ctx: OperationContext, id: UUID): Boolean = tx.withTx(svcCtx(ctx)) { txCtx ->
        ops.deleteById(txCtx, id, scanRepo::deleteById)
    }

    fun presignedUploadUrl(ctx: OperationContext, objectKey: String, contentType: String, duration: Duration): String =
        objectStorage.presignUpload("ugc", objectKey, contentType, duration)

    fun presignedDownloadUrl(ctx: OperationContext, objectKey: String, duration: Duration): String =
        objectStorage.presignDownload("ugc", objectKey, duration)

    fun getPublicUrl(ctx: OperationContext, objectKey: String): String =
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

private fun OperationContext.mustGetAppId(): UUID = appId ?: throw ApiError(ErrorCode.UNAUTHORIZED, "appId required")
