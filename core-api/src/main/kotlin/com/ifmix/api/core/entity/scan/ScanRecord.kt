package com.ifmix.api.core.entity.scan

import com.ifmix.api.core.entity.ImageRef
import java.time.Instant
import java.util.UUID

/**
 * Scan 领域模型。
 * 字段名与 DB 列 camelCase 对齐，支持 jOOQ newRecord(TABLE, model) 自动映射：
 *   imageKeys -> IMAGE_KEYS (JSONB)
 *   resultJson -> RESULT_JSON (JSONB)
 */
data class ScanRecord(
    val id: UUID,
    val appId: UUID,
    val imageKeys: List<ImageRef>,           // DB: IMAGE_KEYS (JSONB)
    val result: Any? = null,             // DB: RESULT_JSON (JSONB)
    val status: Int,
    val clientIp: String? = null,
    val lang: String? = null,
    val country: String? = null,
    val currency: String? = null,
    val userDisplayName: String? = null,
    val userNotes: String? = null,
    val collected: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val deletedAt: Instant? = null,
) {
    /** Aliased as [imageKeys] — exposes the same list under the GraphQL field name. */
    val images: List<ImageRef> get() = imageKeys

    /** 扫描状态编码 */
    object Status {
        const val UNKNOWN = 0
        const val PENDING = 100
        const val PROCESSING = 110
        const val COMPLETED = 200
        const val FAILED = 300
        fun isTerminal(code: Int) = code >= COMPLETED
    }
}
