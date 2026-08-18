package com.ifmix.api.core.model

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
    val status: Short,
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
}
