package com.ifmix.core.api.entity.auth

import com.ifmix.core.api.entity.common.BaseAppEntity
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * Refresh Token（app 级）。主体无关：以 actorId + actorType 关联（customer / 未来 manager）。
 * 表名保持 auth_appuser_refreshtoken（避免动表）。
 */
@Entity
@Table(name = "auth_appuser_refreshtoken")
interface RefreshToken : BaseAppEntity {

    /** 逻辑外键 → 主体 id（customer / manager，跨模块） */
    val actorId: UUID

    /** 主体类型："customer" / 未来 "manager" */
    val actorType: String

    val tokenHash: String
    val expiresAt: Instant?
    val revokedAt: Instant?
    val replacedBy: UUID?
}
