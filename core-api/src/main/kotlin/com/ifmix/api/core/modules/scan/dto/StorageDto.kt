package com.ifmix.api.core.modules.scan.dto

import com.ifmix.api.core.infra.dto.ContentType
import com.ifmix.api.core.infra.dto.UploadCategory
import io.swagger.v3.oas.annotations.media.Schema

data class PresignUploadReq(
    val category: UploadCategory,
    val contentType: ContentType,
)

data class PresignDownloadReq(
    /** ScanRecord.imageKeys 中的 key，presignUpload 返回的 imageKey */
    val imageKey: String,
    /** 签名 URL 有效时长（秒），默认 3600，上限 86400 */
    @Schema(defaultValue = "3600", maximum = "86400")
    val durationSeconds: Long? = null,
)

data class PresignedUploadResponse(val uploadUrl: String, val imageKey: String)
data class PresignedDownloadResponse(val downloadUrl: String)
