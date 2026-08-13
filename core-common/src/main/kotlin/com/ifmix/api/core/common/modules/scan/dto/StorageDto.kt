package com.ifmix.api.core.common.modules.scan.dto

import com.ifmix.api.core.common.infra.dto.ContentType
import com.ifmix.api.core.common.infra.dto.UploadCategory
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

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

data class PresignedUploadResponse(val mediaId: UUID, val uploadUrl: String, val imageKey: String, val downloadUrl: String)
data class PresignedDownloadResponse(val downloadUrl: String)
