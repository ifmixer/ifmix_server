package com.ifmix.api.core.entity.demo

import java.time.Instant
import java.util.UUID

/**
 * JOIN 演示投影 DTO。
 * 由 Todo LEFT JOIN TodoItem + GROUP BY 产生，不对应单张表。
 */
data class TodoWithStats(
    val id: UUID,
    val title: String,
    val done: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    val itemCount: Int = 0,
    val pendingItemCount: Int = 0,
)
