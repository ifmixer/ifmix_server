package com.ifmix.api.core.entity.auth

import java.time.Instant
import java.util.UUID

/**
 * 第三方登录身份模型，对应 core_auth_provider_identity 表。
 * JSONB 字段以 String? 存储，由 Repository 层序列化/反序列化。
 */
data class AuthProviderIdentity(
    val id: UUID,
    val authTenantId: UUID,
    val authIdentityId: UUID,
    val provider: String,
    val providerAccountId: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val phone: String? = null,
    val userMetadata: String? = null,
    val providerMetadata: String? = null,
    val loginIp: String? = null,
    val loginInstallId: UUID? = null,
    val loginAppId: UUID? = null,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
)
