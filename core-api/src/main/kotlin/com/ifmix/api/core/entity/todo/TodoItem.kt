package com.ifmix.api.core.entity.demo

import java.time.Instant
import java.util.UUID

data class TodoItem(
    val id: UUID,
    val appId: UUID,
    val todoId: UUID,
    val content: String,
    val done: Boolean = false,
    // ponytail: note 列待 Flyway migration 后加回
    // val note: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
