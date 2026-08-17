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
import com.ifmix.api.core.model.UploadRecord
import com.ifmix.api.core.modules.storage.repo.UploadRecordJooqRepository
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
 */
@RestController
@RequestMapping("/customer")
@ConditionalOnBean(AntiqueService::class)
class CustomerStorageController(
    private val scanService: AntiqueService,
    private val uploadRecordRepo: UploadRecordJooqRepository,
) {

    @Operation(summary = "获取预签名上传 URL")
    @PostMapping("/mutation/core/storage/presignUpload")
    fun presignUpload(
        ctx: OperationContext,
        @Valid @RequestBody req: PresignUploadReq,
    ): PresignedUploadResponse {
        val appId = ctx.mustGetAppId()
        val installId = ctx.mustGetInstallId()
        val mediaId = UuidV7.generate()
        val objectKey = "app/$appId/${req.category.path}/install/$installId/$mediaId.${req.contentType.extension}"

        val url = scanService.presignedUploadUrl(ctx, objectKey, req.contentType.mimeType, Duration.ofSeconds(300))
        val downloadUrl = scanService.getPublicUrl(ctx, objectKey)

        val uploadRecord = UploadRecord(
            id = mediaId, appId = appId, installId = installId, userId = ctx.userId,
            objectKey = objectKey, contentType = req.contentType.mimeType,
            category = req.category.name, clientIp = ctx.clientIp,
        )
        uploadRecordRepo.insert(ctx.repoCtx, uploadRecord)

        return PresignedUploadResponse(mediaId = mediaId, uploadUrl = url, imageKey = objectKey, downloadUrl = downloadUrl)
    }

    @Operation(summary = "获取预签名下载 URL")
    @PostMapping("/mutation/core/storage/presignDownload")
    fun presignDownload(
        ctx: OperationContext,
        @Valid @RequestBody req: PresignDownloadReq,
    ): PresignedDownloadResponse {
        validateObjectKey(ctx, req.imageKey)
        val url = scanService.presignedDownloadUrl(ctx, req.imageKey, Duration.ofSeconds(req.durationSeconds ?: 3600L))
        return PresignedDownloadResponse(url)
    }

    private fun validateObjectKey(ctx: OperationContext, objectKey: String) {
        if (objectKey.contains("..")) throw ApiError(ErrorCode.INVALID_REQUEST, "path traversal not allowed")
        val appId = ctx.mustGetAppId()
        val normalized = objectKey.removePrefix("/")
        if (!normalized.startsWith("app/$appId/")) throw ApiError(ErrorCode.INVALID_REQUEST, "appId mismatch")
        val installId = ctx.installId
        if (installId != null && !normalized.contains("/install/$installId/")) throw ApiError(ErrorCode.INVALID_REQUEST, "installId mismatch")
    }
}
