package com.ifmix.api.core.generated.mybatis.model

import java.time.Instant
import java.util.UUID

data class CoreTodoItem(
    val id: UUID,
    val todoId: UUID,
    val appId: UUID,
    val content: String,
    val done: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
)