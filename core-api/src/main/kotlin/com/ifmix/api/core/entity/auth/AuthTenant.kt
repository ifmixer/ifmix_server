package com.ifmix.api.core.entity.auth

import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * 认证租户（全局，不按 appId 分片）。
 */
@Entity
@Table(name = "core_auth_tenant")
interface AuthTenant {
    @Id
    val id: UUID

    val jwtPrivateKeyPem: String?
    val jwtIssuer: String?

    val createdAt: Instant
    val updatedAt: Instant}
