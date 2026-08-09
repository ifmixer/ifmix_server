package com.ifmix.api.core.bff.customer.storage

import com.ifmix.api.core.infra.db.UuidV7
import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.OperationContext
import com.ifmix.api.core.infra.http.mustGetAppId
import com.ifmix.api.core.infra.http.mustGetInstallId
import com.ifmix.api.core.modules.scan.dto.PresignDownloadReq
import com.ifmix.api.core.modules.scan.dto.PresignUploadReq
import com.ifmix.api.core.modules.scan.dto.PresignedDownloadResponse
import com.ifmix.api.core.modules.scan.dto.PresignedUploadResponse
import com.ifmix.api.core.modules.scan.service.AntiqueService
import com.ifmix.api.core.modules.storage.repo.UploadRecordRepository
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * customer BFF 的对象存储路由。
 *
 * objectKey 格式: app_{appId}/{category}/i_{installId}/{uuid}.{ext}
 */
@RestController
@RequestMapping("/customer")
@ConditionalOnBean(AntiqueService::class)
class CustomerStorageController(
    private val antiqueService: AntiqueService,
    private val uploadRecordRepo: UploadRecordRepository,
) {

    @Operation(
        summary = "获取预签名上传 URL",
        description = """
            服务端生成 objectKey 并返回预签名上传 URL + imageKey。
            客户端上传完成后用 imageKey 调 antique/newScan。
            不要求登录；user 未登录时 objectKey 中 user 段为 "none"。
        """,
    )
    @PostMapping("/mutation/core/storage/presignUpload")
    fun presignUpload(
        ctx: OperationContext,
        @Valid @RequestBody req: PresignUploadReq,
    ): PresignedUploadResponse {
        val appId = ctx.mustGetAppId()
        val installId = ctx.mustGetInstallId()
        val mediaId = UuidV7.generate()

        // objectKey: app_{appId}/{category}/i_{installId}//{id}.{ext}
        val objectKey = "app_$appId/${req.category.path}/i_$installId/$mediaId.${req.contentType.extension}"

        val url = antiqueService.presignedUploadUrl(ctx, objectKey, req.contentType.mimeType, Duration.ofSeconds(300))
        val downloadUrl = antiqueService.presignedDownloadUrl(ctx, objectKey, Duration.ZERO)

        // 记录上传信息到 DB（id 与文件名一致）
        uploadRecordRepo.create(
            ctx = ctx.repoCtx,
            id = mediaId,
            appId = appId,
            installId = installId,
            userId = ctx.userId,
            objectKey = objectKey,
            contentType = req.contentType.mimeType,
            category = req.category.name,
            clientIp = ctx.clientIp,
        )

        return PresignedUploadResponse(mediaId=mediaId,uploadUrl = url, imageKey = objectKey, downloadUrl = downloadUrl)
    }

    @Operation(
        summary = "获取预签名下载 URL",
        description = """
            用 imageKey 换取临时下载 URL。durationSeconds 默认 3600，上限 86400。
            服务端校验 imageKey 归属（必须属于当前用户的 installId 或 userId）。
            需要 Bearer token。
        """,
    )
    @PostMapping("/mutation/core/storage/presignDownload")
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
     * 2. 必须以 app_{appId}/ 开头且 appId 匹配
     * 3. 必须包含 i_{installId} 且匹配当前用户
     */
    private fun validateObjectKey(ctx: OperationContext, objectKey: String) {
        if (objectKey.contains("..")) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "imageKey: path traversal not allowed")
        }
        val appId = ctx.mustGetAppId()
        if (!objectKey.startsWith("app_$appId/")) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "imageKey: appId mismatch")
        }
        // 校验 installId 归属
        val installId = ctx.installId
        if (installId != null && !objectKey.contains("i_$installId/")) {
            throw ApiError(ErrorCode.INVALID_REQUEST, "imageKey: installId mismatch")
        }
    }

}
