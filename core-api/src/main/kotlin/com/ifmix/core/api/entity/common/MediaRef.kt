package com.ifmix.core.api.entity.common

/**
 * 通用媒体引用（存于各表的 JSONB 附件列，如 cs_support_request.attachments）。
 *
 * - key：对象存储 key（同 scan 的 imageKey，指向 ugc bucket 内对象）。
 * - type：媒体大类编码（Int 全链路透传）。码表见 [MediaTypes]。
 * - category：业务分类编码（Int，语义由使用方定义，可空）。
 *
 * 顺序即数组顺序。参考 ai 模块 ImageRef 的 JSONB 存法。
 */
data class MediaRef(
    val key: String,
    val type: Int? = null,
    val category: Int? = null,
)

/** 媒体大类码表（0 保留，从 10 起步长 10）。 */
object MediaTypes {
    const val UNKNOWN: Int = 0
    const val IMAGE: Int = 10
    const val VIDEO: Int = 20
    const val AUDIO: Int = 30
    const val DOCUMENT: Int = 40
}
