package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * 认证租户（全局，不按 appId 分片）。
 */
@Entity
@Table(name = "auth_tenant")
interface AuthTenant : MutableProps {
    @Id
    val id: UUID

    val jwtPrivateKeyPem: String?
    val jwtIssuer: String?
}
