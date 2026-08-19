package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * 第三方登录身份。
 */
@Entity
@Table(name = "core_auth_provider_identity")
interface AuthProviderIdentity : MutableProps {
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
    val loginInstallId: UUID?
    val loginAppId: UUID?
}
