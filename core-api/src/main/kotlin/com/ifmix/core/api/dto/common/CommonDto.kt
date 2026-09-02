package com.ifmix.core.api.dto.common

import java.util.UUID

/** 通用 ID 请求体 */
data class ByIdRequest(val id: UUID)

/** 通用批量 ID 请求体 */
data class ByIdsRequest(val ids: List<UUID>)

/** 通用操作结果 */
data class OperationResult(val success: Boolean = true, val modifiedCount: Int?=null)

/** 上传分类（决定 objectKey 路径中的目录名） */
enum class UploadCategory(val path: String) {
    ANTIQUE_SCAN("antique_scan"),
}

/** 支持的上传 MIME 类型 */
enum class ContentType(val mimeType: String, val extension: String) {
    IMAGE_JPEG("image/jpeg", "jpg"),
    IMAGE_PNG("image/png", "png"),
    IMAGE_WEBP("image/webp", "webp"),
}


enum class SortOrder { ASC, DESC }

data class ToggleRevisionRequest(
    val id: UUID,
    val enabled: Boolean,
)

data class CreateOneRes(val id: UUID)
