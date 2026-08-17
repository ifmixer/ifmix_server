package com.ifmix.api.core.model

import java.time.Instant
import java.util.UUID

data class Todo(
    val id: UUID,
    val appId: UUID,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val title: String,
    val done: Boolean,
    val note: String? = null,
    val meta: Map<String, Any?>? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
) {
    fun isOwned(userId: UUID?, installId: UUID?): Boolean =
        this.userId == userId || this.installId == installId
}
