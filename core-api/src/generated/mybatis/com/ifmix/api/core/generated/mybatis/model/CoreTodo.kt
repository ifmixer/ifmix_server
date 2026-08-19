package com.ifmix.api.core.generated.mybatis.model

import java.time.Instant
import java.util.UUID

data class CoreTodo(
    val id: UUID,
    val title: String,
    val done: Boolean,
    val appId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val meta: String? = null,
    val note: String? = null
)