package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.types.ContentType
import com.ifmix.api.core.infra.types.UploadCategory
import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.http.mustGetInstallId
import com.ifmix.api.core.service.antique.AntiqueService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.*
import java.time.Duration

/**
 * customer BFF 的对象存储路由。
 *
 * objectKey 格式: app/{appId}/install/{installId}[/user/{userId}]/{category}/{uuid}.{ext}
 * user/{userId} 只在已登录时拼接。
 */
@RestController
@RequestMapping("/customer/core")
@ConditionalOnBean(AntiqueService::class)
class CustomerStorageController(private val antiqueService: AntiqueService) {

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
        ctx: OperationContext,
        @Valid @RequestBody req: PresignUploadReq,
    ): PresignedUploadResponse {
        val appId = ctx.mustGetAppId()
        val installId = ctx.mustGetInstallId()

        // 构建 objectKey: app/{appId}/install/{installId}[/user/{userId}]/{category}/{uuid}.{ext}
        val pathParts = buildList {
            add("app/$appId")
            add("install/$installId")
            if (ctx.userId != null) add("user/${ctx.userId}")
            add(req.category.path)
            add("${UuidV7.generate()}.${req.contentType.extension}")
        }
        val objectKey = pathParts.joinToString("/")

        val url = antiqueService.presignedUploadUrl(ctx, objectKey, req.contentType.mimeType, Duration.ofSeconds(300))
        return PresignedUploadResponse(uploadUrl = url, imageKey = objectKey)
    }

    @Operation(
        summary = "获取预签名下载 URL",
        description = """
            用 imageKey 换取临时下载 URL。durationSeconds 默认 3600，上限 86400。
            服务端校验 imageKey 归属（必须属于当前用户的 installId 或 userId）。
            需要 Bearer token。
        """,
    )
    @PostMapping("/mutation/storage/presignDownload")
    fun presignDownload(
        ctx: OperationContext,
        @Valid @RequestBody req: PresignDownloadReq,
    ): PresignedDownloadResponse {
        validateObjectKey(ctx, req.imageKey)
        val url = antiqueService.presignedDownloadUrl(ctx, req.imageKey, Duration.ofSeconds(req.durationSeconds ?: 3600L))
        return PresignedDownloadResponse(url)
    }

    /**
     * 校验 objectKey 格式和归属：
     * 1. 不能包含路径遍历（..）
     * 2. 必须以 app/{appId}/ 开头且 appId 匹配
     * 3. 必须包含 install/{installId} 且匹配当前用户
     */
    private fun validateObjectKey(ctx: OperationContext, objectKey: String) {
        if (objectKey.contains("..")) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "imageKey: path traversal not allowed")
        }
        val appId = ctx.mustGetAppId()
        if (!objectKey.startsWith("app/$appId/")) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "imageKey: appId mismatch")
        }
        // 校验 installId 归属
        val installId = ctx.installId
        if (installId != null && !objectKey.contains("install/$installId")) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "imageKey: installId mismatch")
        }
    }

    // ---- DTOs ----

    data class PresignUploadReq(
        val category: UploadCategory,
        val contentType: ContentType,
    )

    data class PresignDownloadReq(
        /** ScanRecord.imageKeys 中的 key，presignUpload 返回的 imageKey */
        val imageKey: String,
        /** 签名 URL 有效时长（秒），默认 3600，上限 86400 */
        @io.swagger.v3.oas.annotations.media.Schema(defaultValue = "3600", maximum = "86400")
        val durationSeconds: Long? = null,
    )

    data class PresignedUploadResponse(val uploadUrl: String, val imageKey: String)
    data class PresignedDownloadResponse(val downloadUrl: String)
}
