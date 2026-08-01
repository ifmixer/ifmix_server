package com.ifmix.api.core.entity.auth

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.MutableProps
import java.util.UUID

/**
 * 认证租户（全局，不按 appId 分片）。
 */
@Entity
@Table(name = "core_auth_tenant")
interface AuthTenant : MutableProps {
    @Id
    val id: UUID

    val jwtPrivateKeyPem: String?
    val jwtIssuer: String?
}
