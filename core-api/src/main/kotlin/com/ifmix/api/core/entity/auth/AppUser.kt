package com.ifmix.api.core.entity.auth

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import java.time.Instant
import java.util.UUID

/**
 * App 级用户。
 */
@Entity
@Table(name = "app_user")
interface AppUser : AppScopedProps {
    @Id
    val id: UUID

    override val appId: UUID

    @ManyToOne
    @JoinColumn(name = "auth_identity_id")
    val authIdentity: AuthIdentity

    @Serialized
    val metadata: Map<String, Any?>?

    val createdAt: Instant
    val updatedAt: Instant}
