package com.ifmix.core.api.dto.storage

/**
 * presign upload 协议入参（手写，替代 DGS generated type；字段与 schema/customer/media.graphqls
 * 的 `input PresignUploadInput` 一致）。
 */
data class PresignUploadInput(
    /** 对象存储路径前缀（业务分类，如 antique_scan）。 */
    val prefix: String,
    /** 内容类型。10=IMAGE_JPEG, 20=IMAGE_PNG, 30=IMAGE_WEBP。 */
    val contentType: Int,
)
