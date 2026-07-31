package com.ifmix.api.core.entity.auth

import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * 设备密钥。
 */
@Entity
@Table(name = "core_auth_device_secret")
interface AuthDeviceSecret {
    @Id
    val id: UUID

    @ManyToOne
    @JoinColumn(name = "auth_tenant_id")
    val authTenant: AuthTenant

    @ManyToOne
    @JoinColumn(name = "auth_identity_id")
    val authIdentity: AuthIdentity

    val secretHash: String
    val loginInstallId: String?
    val expiresAt: Instant?
    val revokedAt: Instant?
    val lastUsedAt: Instant?

    val createdAt: Instant
    val updatedAt: Instant}
