package com.ifmix.api.core.model

import java.time.Instant
import java.util.UUID

data class TodoItem(
    val id: UUID,
    val appId: UUID,
    val todoId: UUID,
    val content: String,
    val done: Boolean,
    val note: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
