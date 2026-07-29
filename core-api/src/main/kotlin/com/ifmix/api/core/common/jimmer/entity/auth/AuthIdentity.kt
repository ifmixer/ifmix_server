package com.ifmix.api.core.common.jimmer.entity.auth

import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * 租户级用户身份。
 */
@Entity
@Table(name = "auth_identity")
interface AuthIdentity {
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

    val createdAt: Instant
    val updatedAt: Instant}
