package com.ifmix.api.core.entity.auth

import java.time.Instant
import java.util.UUID

/**
 * App 级用户模型，对应 core_app_user 表。
 * JSONB 字段以 String? 存储，由 Repository 层序列化/反序列化。
 */
data class AppUser(
    val id: UUID,
    val appId: UUID,
    val authIdentityId: UUID,
    val metadata: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
