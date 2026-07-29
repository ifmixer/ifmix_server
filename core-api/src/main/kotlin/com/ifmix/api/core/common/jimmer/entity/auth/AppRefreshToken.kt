package com.ifmix.api.core.common.jimmer.entity.auth

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import java.time.Instant
import java.util.UUID

/**
 * Refresh Token.
 */
@Entity
@Table(name = "app_refresh_token")
interface AppRefreshToken : AppScopedProps {
    @Id
    val id: UUID

    override val appId: UUID

    @ManyToOne
    @JoinColumn(name = "app_user_id")
    val appUser: AppUser

    @ManyToOne
    @JoinColumn(name = "device_secret_id")
    val deviceSecret: AuthDeviceSecret?

    val tokenHash: String
    val loginInstallId: String?
    val expiresAt: Instant?
    val revokedAt: Instant?
    val replacedBy: UUID?

    val createdAt: Instant
    val updatedAt: Instant}
