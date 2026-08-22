package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.BaseAppEntity
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * Refresh Token（app 级）。
 */
@Entity
@Table(name = "auth_appuser_refreshtoken")
interface AppUserRefreshToken : BaseAppEntity {

    /** 逻辑外键 → user_appuser（跨模块） */
    val appUserId: UUID

    val tokenHash: String
    val loginInstallId: UUID?
    val expiresAt: Instant?
    val revokedAt: Instant?
    val replacedBy: UUID?
}
