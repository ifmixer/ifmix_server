package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.antique.AntiqueService
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.*
import java.time.Duration

/**
 * customer BFF 的对象存储路由。
 * 仅在 AntiqueService bean 存在时加载。
 */
@RestController
@RequestMapping("/customer/core")
@ConditionalOnBean(AntiqueService::class)
class CustomerStorageController(private val antiqueService: AntiqueService) {

    /** 生成预签名上传 URL。 */
    @PostMapping("/mutation/storage/presignUpload")
    fun presignUpload(
        ctx: RequestContext,
        @RequestParam objectKey: String,
        @RequestParam contentType: String,
        @RequestParam(defaultValue = "300") durationSeconds: Long,
    ): PresignedUploadResponse {
        val url = antiqueService.presignedUploadUrl(objectKey, contentType, Duration.ofSeconds(durationSeconds))
        return PresignedUploadResponse(url)
    }

    /** 生成预签名下载 URL。 */
    @PostMapping("/mutation/storage/presignDownload")
    fun presignDownload(
        @RequestParam objectKey: String,
        @RequestParam(defaultValue = "3600") durationSeconds: Long,
    ): PresignedDownloadResponse {
        val url = antiqueService.presignedDownloadUrl(objectKey, Duration.ofSeconds(durationSeconds))
        return PresignedDownloadResponse(url)
    }

    data class PresignedUploadResponse(val uploadUrl: String)
    data class PresignedDownloadResponse(val downloadUrl: String)
}
