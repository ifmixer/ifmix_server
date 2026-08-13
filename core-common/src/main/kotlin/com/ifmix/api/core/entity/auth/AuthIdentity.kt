package com.ifmix.api.core.entity.auth

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.MutableProps
import java.util.UUID

/**
 * 租户级用户身份。
 */
@Entity
@Table(name = "core_auth_identity")
interface AuthIdentity : MutableProps {
    @Id
    val id: UUID

    @ManyToOne
    @JoinColumn(name = "auth_tenant_id")
    val authTenant: AuthTenant

    val rawEmail: String?
    val email: String?
    val rawPhone: String?
    val phone: String?
    val contactEmail: String?
    val displayName: String?
    val passwordHash: String?

    @Serialized
    val profile: Map<String, Any?>?
    @Serialized
    val metadata: Map<String, Any?>?
}
