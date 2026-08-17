package com.ifmix.api.core.model

import java.time.Instant
import java.util.UUID

/**
 * 认证租户模型，对应 core_auth_tenant 表。
 */
data class AuthTenant(
    val id: UUID,
    val jwtPrivateKeyPem: String? = null,
    val jwtIssuer: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
