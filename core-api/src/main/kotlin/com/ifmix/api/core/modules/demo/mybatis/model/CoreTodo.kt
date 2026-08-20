package com.ifmix.api.core.modules.demo.mybatis.model

import com.ifmix.api.core.entity.demo.Meta
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
    val meta: Meta? = null,
    val note: String? = null
)
