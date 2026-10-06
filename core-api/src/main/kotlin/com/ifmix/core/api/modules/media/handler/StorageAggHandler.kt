package com.ifmix.core.api.modules.media.handler

import com.ifmix.core.api.dto.storage.PresignDownloadResult
import com.ifmix.core.api.dto.storage.PresignUploadResult
import com.ifmix.core.api.dto.common.ContentTypes
import com.ifmix.core.api.dto.storage.PresignDownloadInput
import com.ifmix.core.api.dto.storage.PresignUploadInput
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
        val projectId = mc.action.mustGetProjectId()
        val actorId = mc.action.mustGetActorId()
        val actorType = mc.action.actorType ?: throw IllegalStateException("actorType missing on authenticated request")
        val mediaId = UuidV7.generate()

        val prefix = sanitizePrefix(input.prefix)
        // images typeGroup 固定为 image（本接口只处理图片上传）
        val typeGroup = "image"
        val ext = ContentTypes.extension(input.contentType)
            ?: throw invalidRequest("unsupported contentType: ${input.contentType}")
        val mimeType = ContentTypes.mimeType(input.contentType)
            ?: throw invalidRequest("unsupported contentType: ${input.contentType}")

        val objectKey = "$typeGroup/p/${projectId}/$prefix/${actorType}/${actorId.toBase58()}/${mediaId.toBase58()}.$ext"
        val uploadUrl = objectStorage.presignUpload("ugc", objectKey, mimeType, Duration.ofSeconds(300))
        val downloadUrl = objectStorage.getPublicUrl("ugc", objectKey)

        val entity = UploadRecord {
            id = mediaId
            this.projectId = projectId
            this.actorId = actorId
            this.actorType = actorType
            this.objectKey = objectKey
            this.contentType = mimeType
            this.clientIp = mc.action.clientIp
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
        val projectId = mc.action.mustGetProjectId()
        val actorId = mc.action.mustGetActorId()
        // owner-scoped：key 必须是调用者本人在本 project 上传的对象（key 内嵌 projectId + actorId）
        validateObjectKey(input.imageKey, projectId, actorId)
        // 封顶 24h，防止铸造长期有效的签名 URL
        val duration = Duration.ofSeconds((input.durationSeconds ?: 3600).coerceIn(60, 24 * 3600).toLong())
        val url = objectStorage.presignDownload("ugc", input.imageKey, duration)
        return PresignDownloadResult(url = url)
    }

    /**
     * objectKey 格式与归属校验。上传生成的格式为：
     *   ${typeGroup}/p/${projectId}/${prefix}/${actorType}/${actorIdBase58}/${mediaId}.ext
     * （历史校验要求 "/project/" 段，与实际生成的 "/p/" 格式自相矛盾，已修正。）
     * 要求 projectId 与 actorId 段等于调用者，防跨租户/跨用户签发下载 URL；并拒绝路径遍历。
     */
    private fun validateObjectKey(key: String, projectId: String, actorId: java.util.UUID) {
        val api = com.ifmix.core.api.infra.http.ApiError(
            com.ifmix.core.api.infra.http.ErrorCode.INVALID_REQUEST, "invalid objectKey",
        )
        if (key.startsWith("/") || key.contains("..")) throw api
        val parts = key.split("/")
        // typeGroup/p/projectId/prefix/actorType/actorIdBase58/mediaId.ext → 至少 7 段
        if (parts.size < 7) throw api
        if (parts[1] != "p") throw api
        if (parts[2] != projectId) throw api
        if (parts[5] != actorId.toBase58()) throw api
    }

    /**
     * prefix 净化：客户端传入的业务分类目录名，会拼进 S3 key。
     * 只允许 [a-zA-Z0-9_-]，防路径遍历/注入。
     */
    private fun sanitizePrefix(raw: String): String {
        if (raw.isBlank()) throw invalidRequest("prefix must not be blank")
        if (raw.length > 64) throw invalidRequest("prefix too long (max 64)")
        if (!raw.matches(PREFIX_REGEX)) throw invalidRequest("invalid prefix: only [a-zA-Z0-9_-] allowed")
        return raw
    }

    /** 客户端错误统一 400 语义（IllegalArgumentException 会逃逸成 500，污染错误码与告警）。 */
    private fun invalidRequest(msg: String) = com.ifmix.core.api.infra.http.ApiError(
        com.ifmix.core.api.infra.http.ErrorCode.INVALID_REQUEST, msg,
    )

    companion object {
        private val PREFIX_REGEX = Regex("^[a-zA-Z0-9_-]+$")
        private const val MAX_PRESIGN_SECONDS = 24L * 3600
    }
}
