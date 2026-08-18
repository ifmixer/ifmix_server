package com.ifmix.api.core.modules.scan.service

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.jooq.TxRunner
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.model.ImageRef
import com.ifmix.api.core.model.scan.ScanRecord
import com.ifmix.api.core.modules.scan.ScanRunner
import com.ifmix.api.core.modules.scan.dto.ScanInput
import com.ifmix.api.core.modules.scan.dto.ScanMediaItem
import com.ifmix.api.core.modules.scan.repo.ScanRecordRepository
import com.ifmix.api.core.generated.types.NewScanInput
import com.ifmix.api.core.generated.types.UpdateScanInput
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class ScanCommands(
    private val scanRunner: ScanRunner,
    private val objectStorage: ObjectStorage,
    private val scanRepo: ScanRecordRepository,
    private val tx: TxRunner,
) {
    private fun svc(opCtx: OperationContext) = SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl)

    fun newScan(opCtx: OperationContext, input: NewScanInput): ScanRecord = tx.withTx(svc(opCtx)) { txCtx ->
        val appId = opCtx.appId!!
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
            lang = opCtx.lang,
            country = opCtx.country,
            currency = opCtx.currency,
        )
        val result = scanRunner.run(opCtx, scanInput)

        val record = ScanRecord(
            id = scanId,
            appId = appId,
            imageKeys = input.images.map { ImageRef(key = it.imageKey) },
            result = result,
            status = ScanRecord.Status.COMPLETED,
            clientIp = opCtx.clientIp,
            lang = opCtx.lang,
            country = opCtx.country,
            currency = opCtx.currency,
            userDisplayName = null,
            userNotes = null,
            collected = false,
            createdAt = now,
            updatedAt = now,
        )
        scanRepo.insert(txCtx, record)
        record
    }

    fun updateScan(opCtx: OperationContext, input: UpdateScanInput): Boolean = tx.withTx(svc(opCtx)) { txCtx ->
        val appId = opCtx.mustGetAppId()
        if (!scanRepo.exists(txCtx, appId, input.id)) throw com.ifmix.api.core.infra.http.ApiError(
            com.ifmix.api.core.infra.http.ErrorCode.NOT_FOUND
        )
        scanRepo.partialUpdate(txCtx, appId, input.id, input)
        true
    }

    fun deleteScan(opCtx: OperationContext, id: UUID): Boolean = tx.withTx(svc(opCtx)) { txCtx ->
        val appId = opCtx.mustGetAppId()
        scanRepo.deleteById(txCtx, appId, id)
    }

    fun presignedUploadUrl(opCtx: OperationContext, objectKey: String, contentType: String, duration: Duration): String =
        objectStorage.presignUpload("ugc", objectKey, contentType, duration)

    fun presignedDownloadUrl(opCtx: OperationContext, objectKey: String, duration: Duration): String =
        objectStorage.presignDownload("ugc", objectKey, duration)

    fun getPublicUrl(opCtx: OperationContext, objectKey: String): String =
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
