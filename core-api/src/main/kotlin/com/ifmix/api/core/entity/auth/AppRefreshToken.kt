package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * Refresh Token.
 */
@Entity
@Table(name = "core_app_refresh_token")
interface AppRefreshToken : AppScopedProps, MutableProps {
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
    val loginInstallId: UUID?
    val expiresAt: Instant?
    val revokedAt: Instant?
    val replacedBy: UUID?
}
