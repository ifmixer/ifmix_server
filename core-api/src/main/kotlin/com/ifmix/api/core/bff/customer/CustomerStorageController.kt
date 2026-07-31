package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.antique.AntiqueService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Duration

/**
 * customer BFF 的对象存储路由。
 * 仅在 AntiqueService bean 存在时加载。
 *
 * P0-2: objectKey 由服务端生成，客户端只指定 contentType。
 * objectKey 格式: app_{appId}/i_{installId}/scan/{uuid}.{ext}
 */
@RestController
@RequestMapping("/customer/core", produces = ["application/json"])
@ConditionalOnBean(AntiqueService::class)
class CustomerStorageController(private val antiqueService: AntiqueService) {

    companion object {
        /**
         * objectKey 合法格式正则（用于 presignDownload 校验）：
         * app_{uuid}/i_{non-empty}/...  或  app_{uuid}/u_{non-empty}/...
         */
        private val OBJECT_KEY_PATTERN = Regex(
            "^app_[0-9a-fA-F\\-]{36}/(i_[^/]+|u_[^/]+)/.+"
        )
    }

    /**
     * 生成预签名上传 URL。
     * objectKey 由服务端生成，格式: app_{appId}/i_{installId}/scan/{uuid}.{ext}
     */
    @Operation(
        summary = "获取预签名上传 URL",
        description = """
            服务端生成 objectKey 并返回预签名上传 URL + imageKey。
            客户端上传完成后用 imageKey 调 antique/newScan。
            需要 Bearer token。
        """,
    )
    @PostMapping("/mutation/storage/presignUpload")
    fun presignUpload(
        ctx: RequestContext,
        @Valid @RequestBody req: PresignUploadReq,
    ): PresignedUploadResponse {
        val installId = ctx.installId
            ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-install-id header is required")

        // 根据 contentType 决定文件扩展名
        val ext = when (req.contentType) {
            ContentType.IMAGE_JPEG -> "jpg"
            ContentType.IMAGE_PNG -> "png"
            ContentType.IMAGE_WEBP -> "webp"
        }

        // 服务端生成 objectKey
        val objectKey = "app_${ctx.appId}/i_$installId/scan/${UuidV7.generate()}.$ext"

        val contentTypeStr = when (req.contentType) {
            ContentType.IMAGE_JPEG -> "image/jpeg"
            ContentType.IMAGE_PNG -> "image/png"
            ContentType.IMAGE_WEBP -> "image/webp"
        }

        val url = antiqueService.presignedUploadUrl(objectKey, contentTypeStr, Duration.ofSeconds(300))
        return PresignedUploadResponse(uploadUrl = url, imageKey = objectKey)
    }

    /** 生成预签名下载 URL。暂不验证权限。 */
    @Operation(
        summary = "获取预签名下载 URL",
        description = """
            用 imageKey 换取临时下载 URL。durationSeconds 默认 3600，上限 86400。
            服务端校验 objectKey 归属（必须属于当前 appId）。
            需要 Bearer token。
        """,
    )
    @PostMapping("/mutation/storage/presignDownload")
    fun presignDownload(
        ctx: RequestContext,
        @Valid @RequestBody req: PresignDownloadReq,
    ): PresignedDownloadResponse {
        // 校验 objectKey 格式
        validateObjectKey(ctx, req.imageKey)

        val url = antiqueService.presignedDownloadUrl(req.imageKey, Duration.ofSeconds(req.durationSeconds))
        return PresignedDownloadResponse(url)
    }

    /**
     * 校验 objectKey 格式：
     * 1. 必须匹配 app_{appId}/(i_{installId}|u_{userId})/... 格式
     * 2. objectKey 中的 appId 必须与请求头中的 appId 一致
     * 3. 不能包含路径遍历字符（..）
     */
    private fun validateObjectKey(ctx: RequestContext, objectKey: String) {
        // 防止路径遍历
        if (objectKey.contains("..")) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "objectKey: path traversal not allowed")
        }

        // 格式校验
        if (!OBJECT_KEY_PATTERN.matches(objectKey)) {
            throw ApiError(
                ErrorCode.INVALID_REQUEST,
                "objectKey: must match format app_{appId}/i_{installId}/... or app_{appId}/u_{userId}/..."
            )
        }

        // appId 一致性校验：objectKey 中的 appId 必须与请求头中的 appId 一致
        val keyAppId = objectKey.substringAfter("app_").substringBefore("/")
        if (!keyAppId.equals(ctx.appId, ignoreCase = true)) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "objectKey: appId mismatch")
        }
    }

    // ---- DTOs ----

    data class PresignUploadReq(
        val contentType: ContentType,
    )

    enum class ContentType {
        @JsonProperty("image/jpeg") IMAGE_JPEG,
        @JsonProperty("image/png") IMAGE_PNG,
        @JsonProperty("image/webp") IMAGE_WEBP,
    }

    data class PresignDownloadReq(
        /** 即 ScanDto.imageKey，presignUpload 返回的 imageKey */
        val imageKey: String,
        /** 签名 URL 有效时长（秒），默认 3600，上限 86400 */
        val durationSeconds: Long = 3600,
    )

    data class PresignedUploadResponse(val uploadUrl: String, val imageKey: String)
    data class PresignedDownloadResponse(val downloadUrl: String)
}
