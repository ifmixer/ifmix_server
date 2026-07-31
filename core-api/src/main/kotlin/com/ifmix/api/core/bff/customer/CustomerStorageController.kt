package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.infra.http.ApiError
import com.ifmix.api.core.infra.http.ErrorCode
import com.ifmix.api.core.infra.http.RequestContext
import com.ifmix.api.core.service.antique.AntiqueService
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.*
import java.time.Duration

/**
 * customer BFF 的对象存储路由。
 * 仅在 AntiqueService bean 存在时加载。
 *
 * objectKey 格式校验规则：
 * - app_{appId}/i_{installId}/... （匿名用户用 installId）
 * - app_{appId}/u_{userId}/...   （已登录用户用 userId）
 *
 * presignUpload: 不要求登录，但校验 objectKey 格式。
 * presignDownload: 暂不验证。
 */
@RestController
@RequestMapping("/customer/core")
@ConditionalOnBean(AntiqueService::class)
class CustomerStorageController(private val antiqueService: AntiqueService) {

    companion object {
        /**
         * objectKey 合法格式正则：
         * app_{uuid}/i_{non-empty}/...  或  app_{uuid}/u_{non-empty}/...
         */
        private val OBJECT_KEY_PATTERN = Regex(
            "^app_[0-9a-fA-F\\-]{36}/(i_[^/]+|u_[^/]+)/.+"
        )
    }

    /** 生成预签名上传 URL。 */
    @PostMapping("/mutation/storage/presignUpload")
    fun presignUpload(
        ctx: RequestContext,
        @RequestParam objectKey: String,
        @RequestParam contentType: String,
        @RequestParam(defaultValue = "300") durationSeconds: Long,
    ): PresignedUploadResponse {
        // 校验 objectKey 格式
        validateObjectKey(ctx, objectKey)

        val url = antiqueService.presignedUploadUrl(objectKey, contentType, Duration.ofSeconds(durationSeconds))
        return PresignedUploadResponse(url)
    }

    /** 生成预签名下载 URL。暂不验证权限。 */
    @PostMapping("/mutation/storage/presignDownload")
    fun presignDownload(
        ctx: RequestContext,
        @RequestParam objectKey: String,
        @RequestParam(defaultValue = "3600") durationSeconds: Long,
    ): PresignedDownloadResponse {
        val url = antiqueService.presignedDownloadUrl(objectKey, Duration.ofSeconds(durationSeconds))
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

    data class PresignedUploadResponse(val uploadUrl: String)
    data class PresignedDownloadResponse(val downloadUrl: String)
}
