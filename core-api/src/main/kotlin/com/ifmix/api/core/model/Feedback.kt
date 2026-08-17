package com.ifmix.api.core.model

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
)
