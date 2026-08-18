package com.ifmix.api.core.modules.storage.service.internal

import com.ifmix.api.core.dto.storage.PresignDownloadResult
import com.ifmix.api.core.dto.storage.PresignUploadResult
import com.ifmix.api.core.generated.types.PresignDownloadInput
import com.ifmix.api.core.generated.types.PresignUploadInput
import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.entity.storage.UploadRecord
import com.ifmix.api.core.modules.storage.repo.UploadRecordRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class StorageInternalService(
    private val uploadRecordRepo: UploadRecordRepository,
    private val objectStorage: ObjectStorage,
) {
    fun presignUpload(opCtx: OperationContext, input: PresignUploadInput): PresignUploadResult {
        val appId = opCtx.appId!!
        val installId = opCtx.installId!!
        val mediaId = UuidV7.generate()

        val category = "antique_scan"
        val ext = when (input.contentType) {
            100 -> "jpg"
            200 -> "png"
            300 -> "webp"
            else -> throw IllegalArgumentException("unsupported contentType: ${input.contentType}")
        }
        val mimeType = when (input.contentType) {
            100 -> "image/jpeg"
            200 -> "image/png"
            300 -> "image/webp"
            else -> throw IllegalArgumentException("unsupported contentType: ${input.contentType}")
        }

        val objectKey = "app/$appId/$category/install/$installId/$mediaId.$ext"
        val uploadUrl = objectStorage.presignUpload("ugc", objectKey, mimeType, Duration.ofSeconds(300))
        val downloadUrl = objectStorage.getPublicUrl("ugc", objectKey)

        uploadRecordRepo.insert(SvcCtx(op = opCtx, dsl = SvcCtx.DEFAULT.dsl), UploadRecord(
            id = mediaId,
            appId = appId,
            installId = installId,
            userId = opCtx.userId,
            objectKey = objectKey,
            contentType = mimeType,
            category = category,
            clientIp = opCtx.clientIp,
            createdAt = Instant.now(),
        ))

        return PresignUploadResult(
            mediaId = mediaId,
            uploadUrl = uploadUrl,
            imageKey = objectKey,
            downloadUrl = downloadUrl,
        )
    }

    fun presignDownload(opCtx: OperationContext, input: PresignDownloadInput): PresignDownloadResult {
        val duration = Duration.ofSeconds((input.durationSeconds ?: 3600).toLong())
        val url = objectStorage.presignDownload("ugc", input.imageKey, duration)
        return PresignDownloadResult(url = url)
    }
}
