package com.ifmix.core.api.entity.auth

import com.ifmix.core.api.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * Refresh Token（app 级）。主体无关：以 actorId + actorType 关联（customer / manager）。
 */
@Entity
@Table(name = "auth_refreshtoken")
interface RefreshToken : BaseAppEntity {

    /** 逻辑外键 → 主体 id（customer / manager，跨模块） */
    val actorId: UUID

    /** 主体类型：10=customer / 20=manager */
    val actorType: Int

    val tokenHash: String
    val expiresAt: Instant?
    val revokedAt: Instant?
    val replacedBy: UUID?
}
