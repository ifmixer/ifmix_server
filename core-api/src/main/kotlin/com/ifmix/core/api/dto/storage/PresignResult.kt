package com.ifmix.core.api.dto.storage

import java.util.UUID

/** presign upload 响应（内部 service 层使用，非 DGS generated type） */
data class PresignUploadResult(
    val mediaId: UUID,
    val uploadUrl: String,
    val imageKey: String,
    val downloadUrl: String,
)

/** presign download 响应（内部 service 层使用，非 DGS generated type） */
data class PresignDownloadResult(
    val url: String,
)
