package com.ifmix.core.api.dto.common

import java.util.UUID

/** 通用 ID 请求体 */
data class ByIdRequest(val id: UUID)

/** 通用批量 ID 请求体 */
data class ByIdsRequest(val ids: List<UUID>)

/** 通用操作结果 */
data class OperationResult(val success: Boolean = true, val modifiedCount: Int?=null)

/** 上传内容类型编码。typealias（Int 全链路透传），码表 + mime/ext 映射见 [ContentTypes]。 */
typealias ContentType = Int

/** 支持的上传 MIME 类型码表（0 保留，从 10 起步长 10）。 */
object ContentTypes {
    const val IMAGE_JPEG: ContentType = 10
    const val IMAGE_PNG: ContentType = 20
    const val IMAGE_WEBP: ContentType = 30

    /** code → MIME type；未知返回 null。 */
    fun mimeType(code: ContentType): String? = when (code) {
        IMAGE_JPEG -> "image/jpeg"
        IMAGE_PNG -> "image/png"
        IMAGE_WEBP -> "image/webp"
        else -> null
    }

    /** code → 文件扩展名；未知返回 null。 */
    fun extension(code: ContentType): String? = when (code) {
        IMAGE_JPEG -> "jpg"
        IMAGE_PNG -> "png"
        IMAGE_WEBP -> "webp"
        else -> null
    }
}


enum class SortOrder { ASC, DESC }

data class ToggleRevisionRequest(
    val id: UUID,
    val enabled: Boolean,
)

data class CreateOneRes(val id: UUID)
