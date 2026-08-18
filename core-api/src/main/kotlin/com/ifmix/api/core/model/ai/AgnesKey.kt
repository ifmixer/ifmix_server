package com.ifmix.api.core.model.ai

import java.time.Instant
import java.util.UUID

/**
 * AgnesKey 领域模型。
 * 字段名与 DB 列 camelCase 对齐，支持 jOOQ newRecord(TABLE, model) 自动映射。
 */
data class AgnesKey(
    val id: UUID,
    val appId: UUID,
    val key: String,
    val email: String? = null,
    val type: Int,
    val rateLimit: Long,
    val windowSec: Long,
    val models: String? = null,
    val unavailableUntil: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val deletedAt: Instant? = null,
) {
    /** AI Key 类型编码 */
    object Type {
        const val UNKNOWN = 0
        const val PERSONAL = 100
        const val ENTERPRISE = 200
        fun fromCode(code: Int): Int = when (code) {
            PERSONAL -> PERSONAL
            ENTERPRISE -> ENTERPRISE
            else -> UNKNOWN
        }
        fun nameOf(code: Int): String = when (code) {
            PERSONAL -> "PERSONAL"
            ENTERPRISE -> "ENTERPRISE"
            else -> "UNKNOWN"
        }
    }
}
