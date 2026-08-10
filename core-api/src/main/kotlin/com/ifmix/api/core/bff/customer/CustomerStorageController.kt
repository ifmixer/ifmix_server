package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.modules.antique.AntiqueService
import com.ifmix.api.core.modules.storage.UploadRecordDocument
import com.ifmix.api.core.modules.storage.UploadRecordRepo
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Lazy
import org.springframework.web.bind.annotation.*
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Duration

/**
 * customer BFF 的对象存储路由。
 * 仅在 AntiqueService bean 存在时加载。
 */
@RestController
@RequestMapping("/customer/core")
@ConditionalOnBean(AntiqueService::class)
class CustomerStorageController(
    private val antiqueService: AntiqueService,
    @Lazy private val uploadRecordRepo: UploadRecordRepo,
) {

    /** 生成预签名上传 URL，并异步记录上传行为。 */
    @PostMapping("/mutation/storage/presignUpload")
    fun presignUpload(
        ctx: RequestContext,
        @RequestParam objectKey: String,
        @RequestParam contentType: String,
        @RequestParam(defaultValue = "300") durationSeconds: Long,
        @RequestParam(defaultValue = "scan") category: String,
    ): PresignedUploadResponse {
        val url = antiqueService.presignedUploadUrl(objectKey, contentType, Duration.ofSeconds(durationSeconds))
        recordUpload(ctx, objectKey, contentType, category)
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

    /**
     * 异步记录上传行为（best-effort，失败不影响主流程）。
     * 使用 RequestContextHolder 获取当前 HTTP 请求以提取客户端 IP。
     */
    private fun recordUpload(ctx: RequestContext, objectKey: String, contentType: String, category: String) {
        try {
            val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            val clientIp = request?.let {
                com.ifmix.api.core.common.http.ClientIpResolver.resolve(it)
            }
            val doc = UploadRecordDocument().apply {
                this.appId = ctx.appId
                this.installId = ctx.installId
                this.userId = ctx.userId
                this.objectKey = objectKey
                this.contentType = contentType
                this.category = category
                this.clientIp = clientIp
                createdAt = java.time.Instant.now()
                updatedAt = java.time.Instant.now()
            }
            uploadRecordRepo.insert(ctx, doc)
        } catch (_: Exception) {
            // best-effort，不中断上传流程
        }
    }

    data class PresignedUploadResponse(val uploadUrl: String)
    data class PresignedDownloadResponse(val downloadUrl: String)
}
