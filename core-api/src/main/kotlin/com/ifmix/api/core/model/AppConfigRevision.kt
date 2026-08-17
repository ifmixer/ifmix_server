package com.ifmix.api.core.model

import java.time.Instant
import java.util.UUID

/**
 * App 配置版本领域模型。
 * content 字段为 JSON 字符串，对应 DB JSONB 列。
 */
data class AppConfigRevision(
    val id: UUID,
    val appId: UUID,
    val authTenantId: UUID? = null,
    val appleBundleId: String? = null,
    val androidPackageName: String? = null,
    val revisionNumber: Int,
    val createdAt: Instant,
    val enabled: Boolean,
    val slug: String,
    val content: String,  // JSON 字符串，对应 DB JSONB 列
    val note: String,
)
