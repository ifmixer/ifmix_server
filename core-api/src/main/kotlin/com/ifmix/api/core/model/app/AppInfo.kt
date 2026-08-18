package com.ifmix.api.core.model.app

import java.time.Instant
import java.util.UUID

/**
 * App 信息领域模型。
 * 字段名与 DB 列 camelCase 对齐，支持 jOOQ newRecord(TABLE, model) 自动映射。
 */
data class AppInfo(
    val id: UUID,
    val name: String?,
    val description: String?,
    val slug: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
