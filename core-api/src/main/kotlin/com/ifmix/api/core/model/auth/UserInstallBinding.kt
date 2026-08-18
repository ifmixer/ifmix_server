package com.ifmix.api.core.model.auth

import java.time.Instant
import java.util.UUID

/**
 * 用户-安装绑定模型，对应 core_user_install_binding 表。
 */
data class UserInstallBinding(
    val id: UUID,
    val appId: UUID,
    val userId: UUID,
    val installId: UUID,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val loginCount: Int,
    val clientIp: String? = null,
    val clientPlatform: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
