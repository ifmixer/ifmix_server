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
)
