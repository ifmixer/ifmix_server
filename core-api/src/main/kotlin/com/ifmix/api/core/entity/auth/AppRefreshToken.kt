package com.ifmix.api.core.entity.auth

import java.time.Instant
import java.util.UUID

/**
 * 刷新令牌模型，对应 core_app_refresh_token 表。
 */
data class AppRefreshToken(
    val id: UUID,
    val appId: UUID,
    val appUserId: UUID,
    val deviceSecretId: UUID? = null,
    val tokenHash: String,
    val loginInstallId: UUID? = null,
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val replacedBy: UUID? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
