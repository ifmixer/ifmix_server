package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * 设备密钥。
 */
@Entity
@Table(name = "auth_device_secret")
interface AuthDeviceSecret : MutableProps {
    @Id
    val id: UUID

    @ManyToOne
    @JoinColumn(name = "auth_tenant_id")
    val authTenant: AuthTenant

    @ManyToOne
    @JoinColumn(name = "auth_identity_id")
    val authIdentity: AuthIdentity

    val secretHash: String
    val loginInstallId: UUID?
    val expiresAt: Instant?
    val revokedAt: Instant?
    val lastUsedAt: Instant?
}
