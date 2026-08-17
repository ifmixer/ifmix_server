package com.ifmix.api.core.model

import java.time.Instant
import java.util.UUID

/**
 * 租户级用户身份模型，对应 core_auth_identity 表。
 * JSONB 字段以 String? 存储，由 Repository 层序列化/反序列化。
 */
data class AuthIdentity(
    val id: UUID,
    val authTenantId: UUID,
    val rawEmail: String? = null,
    val email: String? = null,
    val rawPhone: String? = null,
    val phone: String? = null,
    val contactEmail: String? = null,
    val displayName: String? = null,
    val passwordHash: String? = null,
    val profile: String? = null,
    val metadata: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
