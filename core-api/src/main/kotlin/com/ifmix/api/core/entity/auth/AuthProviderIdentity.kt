package com.ifmix.api.core.entity.auth

import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * Provider 身份（第三方登录）。
 */
@Entity
@Table(name = "core_auth_provider_identity")
interface AuthProviderIdentity {
    @Id
    val id: UUID

    @ManyToOne
    @JoinColumn(name = "auth_tenant_id")
    val authTenant: AuthTenant

    @ManyToOne
    @JoinColumn(name = "auth_identity_id")
    val authIdentity: AuthIdentity

    val provider: String
    val providerAccountId: String
    val email: String?
    val emailVerified: Boolean
    val phone: String?

    @Serialized
    val userMetadata: Map<String, Any?>?
    @Serialized
    val providerMetadata: Map<String, Any?>?

    val loginIp: String?
    val loginInstallId: String?
    val loginAppId: String?

    val createdAt: Instant
    val updatedAt: Instant}
