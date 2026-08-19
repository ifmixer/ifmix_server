package com.ifmix.api.core.entity.auth

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * App 级用户。
 */
@Entity
@Table(name = "core_app_user")
interface AppUser : AppScopedProps, MutableProps {
    @Id
    val id: UUID

    override val appId: UUID

    @ManyToOne
    @JoinColumn(name = "auth_identity_id")
    val authIdentity: AuthIdentity

    @Serialized
    val metadata: Map<String, Any?>?
}
