package com.ifmix.api.core.entity.todo

import java.time.Instant
import java.util.UUID

/**
 * Todo domain model。
 * 字段名与 DB 列 camelCase 对齐，支持 jOOQ newRecord(TABLE, model) 自动映射。
 */
data class Todo(
    val id: UUID,
    val appId: UUID,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val title: String,
    val done: Boolean = false,
    // ponytail: note 列待 Flyway migration 后加回
    // val note: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
) {
    fun isOwned(userId: UUID?, installId: UUID?): Boolean =
        this.userId == userId || this.installId == installId
}
