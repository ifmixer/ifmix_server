package com.ifmix.api.core.modules.media.handler

import com.ifmix.api.core.dto.storage.PresignDownloadResult
import com.ifmix.api.core.dto.storage.PresignUploadResult
import com.ifmix.api.core.generated.types.PresignDownloadInput
import com.ifmix.api.core.generated.types.PresignUploadInput
import com.ifmix.api.core.infra.codec.toBase58
import com.ifmix.api.core.infra.db.ModuleCtx
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.storage.ObjectStorage
import com.ifmix.api.core.entity.media.UploadRecord
import com.ifmix.api.core.modules.media.repo.UploadRecordRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class StorageAggHandler(
    private val uploadRecordRepo: UploadRecordRepository,
    private val objectStorage: ObjectStorage,
) {
    fun presignUpload(mc: ModuleCtx, input: PresignUploadInput): PresignUploadResult {
        val appId = mc.op.appId!!
        val installId = mc.op.installId!!
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

        val objectKey = "app/${appId.toBase58()}/$category/install/${installId.toBase58()}/${mediaId.toBase58()}.$ext"
        val uploadUrl = objectStorage.presignUpload("ugc", objectKey, mimeType, Duration.ofSeconds(300))
        val downloadUrl = objectStorage.getPublicUrl("ugc", objectKey)

        val entity = UploadRecord {
            id = mediaId
            this.appId = appId
            this.installId = installId
            this.userId = mc.op.userId
            this.objectKey = objectKey
            this.contentType = mimeType
            this.category = category
            this.clientIp = mc.op.clientIp
            this.createdAt = Instant.now()
        }
        uploadRecordRepo.save(mc, entity)

        return PresignUploadResult(
            mediaId = mediaId,
            uploadUrl = uploadUrl,
            imageKey = objectKey,
            downloadUrl = downloadUrl,
        )
    }

    fun presignDownload(mc: ModuleCtx, input: PresignDownloadInput): PresignDownloadResult {
        validateObjectKey(input.imageKey)
        val duration = Duration.ofSeconds((input.durationSeconds ?: 3600).toLong())
        val url = objectStorage.presignDownload("ugc", input.imageKey, duration)
        return PresignDownloadResult(url = url)
    }

    /**
     * objectKey 格式校验：必须以 "app/" 开头，不得包含 ".." 或绝对路径。
     * 防止客户端传入任意 S3 key 实现路径遍历。
     */
    private fun validateObjectKey(key: String) {
        require(key.startsWith("app/")) { "invalid objectKey: must start with app/" }
        require(!key.contains("..")) { "invalid objectKey: path traversal detected" }
        require(!key.startsWith("/")) { "invalid objectKey: absolute path not allowed" }
    }
}
