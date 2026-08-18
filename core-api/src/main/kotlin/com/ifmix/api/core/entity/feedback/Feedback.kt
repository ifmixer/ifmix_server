package com.ifmix.api.core.entity.cms

import java.time.Instant
import java.util.UUID

/**
 * Feedback domain model。
 * 字段名与 DB 列 camelCase 对齐，支持 jOOQ newRecord(TABLE, model) 自动映射。
 */
data class Feedback(
    val id: UUID,
    val appId: UUID,
    val installId: UUID,
    val userId: UUID? = null,
    val scanRecordId: UUID? = null,
    val category: Short,
    val comment: String? = null,
    val createdAt: Instant,
) {
    /** 反馈分类编码 */
    object Category {
        const val UNKNOWN = 0
        const val LIKED = 100
        const val PRICE_TOO_HIGH = 200
        const val PRICE_TOO_LOW = 210
        const val PRICE_MISSING = 220
        const val WRONG_IDENTIFICATION = 300
        const val FEATURE_REQUEST = 400
        const val MORE_RECOMMENDATIONS = 410
    }
}
