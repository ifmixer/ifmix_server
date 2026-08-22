package com.ifmix.api.core.entity.user

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import com.ifmix.api.core.entity.auth.AuthIdentity
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * App 级用户。
 */
@Entity
@Table(name = "user_app_user")
interface AppUser : AppScopedProps, MutableProps {
    @Id
    val id: UUID


    @ManyToOne
    @JoinColumn(name = "auth_identity_id")
    val authIdentity: AuthIdentity

    @Serialized
    val metadata: Map<String, Any?>?
}
