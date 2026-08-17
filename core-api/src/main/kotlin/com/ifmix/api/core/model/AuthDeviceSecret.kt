package com.ifmix.api.core.model

import java.time.Instant
import java.util.UUID

/**
 * 设备密钥模型，对应 core_auth_device_secret 表。
 */
data class AuthDeviceSecret(
    val id: UUID,
    val authTenantId: UUID,
    val authIdentityId: UUID,
    val secretHash: String,
    val loginInstallId: UUID? = null,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val lastUsedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
