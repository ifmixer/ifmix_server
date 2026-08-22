package com.ifmix.api.core.entity.user

import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.MutableProps
import org.babyfish.jimmer.sql.*
import java.util.UUID

/**
 * App 级用户。
 */
@Entity
@Table(name = "user_appuser")
interface AppUser : AppScopedProps, MutableProps {
    @Id
    val id: UUID

    @Serialized
    val metadata: Map<String, Any?>?
}
