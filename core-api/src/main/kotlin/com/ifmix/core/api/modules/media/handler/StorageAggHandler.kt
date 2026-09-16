package com.ifmix.core.api.modules.media.handler

import com.ifmix.core.api.dto.storage.PresignDownloadResult
import com.ifmix.core.api.dto.storage.PresignUploadResult
import com.ifmix.core.api.dto.common.ContentTypes
import com.ifmix.core.api.generated.types.PresignDownloadInput
import com.ifmix.core.api.generated.types.PresignUploadInput
import com.ifmix.core.api.infra.codec.toBase58
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.db.UuidV7
import com.ifmix.core.api.infra.storage.ObjectStorage
import com.ifmix.core.api.entity.media.UploadRecord
import com.ifmix.core.api.modules.media.repo.UploadRecordRepository
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class StorageAggHandler(
    private val uploadRecordRepo: UploadRecordRepository,
    private val objectStorage: ObjectStorage,
) {
    fun presignUpload(mc: ModuleCtx, input: PresignUploadInput): PresignUploadResult {
        val projectId = mc.op.mustGetProjectId()
        val actorId = mc.op.mustGetActorId()
        val actorType = mc.op.actorType ?: throw IllegalStateException("actorType missing on authenticated request")
        val mediaId = UuidV7.generate()

        val prefix = sanitizePrefix(input.prefix)
        // images typeGroup 固定为 image（本接口只处理图片上传）
        val typeGroup = "image"
        val ext = ContentTypes.extension(input.contentType)
            ?: throw IllegalArgumentException("unsupported contentType: ${input.contentType}")
        val mimeType = ContentTypes.mimeType(input.contentType)
            ?: throw IllegalArgumentException("unsupported contentType: ${input.contentType}")

        val objectKey = "$typeGroup/project/${projectId}/$prefix/${actorType}/${actorId.toBase58()}/${mediaId.toBase58()}.$ext"
        val uploadUrl = objectStorage.presignUpload("ugc", objectKey, mimeType, Duration.ofSeconds(300))
        val downloadUrl = objectStorage.getPublicUrl("ugc", objectKey)

        val entity = UploadRecord {
            id = mediaId
            this.projectId = projectId
            this.actorId = actorId
            this.actorType = actorType
            this.objectKey = objectKey
            this.contentType = mimeType
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
     * objectKey 格式校验：必须含 "/project/" 段，不得包含 ".." 或绝对路径。
     * 新格式 ${typeGroup}/project/${projectId}/${prefix}/${mediaId}.ext
     * 防止客户端传入任意 S3 key 实现路径遍历。
     */
    private fun validateObjectKey(key: String) {
        require(!key.startsWith("/")) { "invalid objectKey: absolute path not allowed" }
        require(!key.contains("..")) { "invalid objectKey: path traversal detected" }
        require(key.contains("/project/")) { "invalid objectKey: must contain /project/ segment" }
    }

    /**
     * prefix 净化：客户端传入的业务分类目录名，会拼进 S3 key。
     * 只允许 [a-zA-Z0-9_-]，防路径遍历/注入。
     */
    private fun sanitizePrefix(raw: String): String {
        require(raw.isNotBlank()) { "prefix must not be blank" }
        require(raw.length <= 64) { "prefix too long (max 64)" }
        require(raw.matches(PREFIX_REGEX)) { "invalid prefix: only [a-zA-Z0-9_-] allowed" }
        return raw
    }

    companion object {
        private val PREFIX_REGEX = Regex("^[a-zA-Z0-9_-]+$")
    }
}
