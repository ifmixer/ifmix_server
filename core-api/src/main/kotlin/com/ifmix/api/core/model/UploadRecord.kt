package com.ifmix.api.core.model

import java.time.Instant
import java.util.UUID

/**
 * UploadRecord domain model for jOOQ repository.
 * 字段名与 DB 列 camelCase 对齐，支持 jOOQ newRecord(TABLE, model) 自动映射。
 */
data class UploadRecord(
    val id: UUID,
    val appId: UUID,
    val installId: UUID? = null,
    val userId: UUID? = null,
    val objectKey: String,
    val contentType: String,
    val category: String,
    val clientIp: String? = null,
    val createdAt: Instant? = null,
)
